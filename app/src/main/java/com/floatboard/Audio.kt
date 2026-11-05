package com.floatboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Playback with observable state for the UI: what's playing, what's queued, and 0..1 progress.
 * Tap behavior comes from AppSettings.playMode (queue / replace / overlap).
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private const val MAX_ACTIVE = 8
    private const val MAX_QUEUE = 20

    private val active = mutableListOf<MediaPlayer>()
    private var primary: MediaPlayer? = null
    private var durationMs = 0
    private val handler = Handler(Looper.getMainLooper())
    private val boosters = HashMap<MediaPlayer, LoudnessEnhancer>()

    /** Sounds waiting to play after the current one (queue mode). */
    val queue = mutableStateListOf<Sound>()

    var nowPlaying by mutableStateOf<Sound?>(null)
        private set
    /** True from the moment a sound is started until it ends or is stopped (including paused). */
    var isBusy by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private val ticker = object : Runnable {
        override fun run() {
            val d = durationMs
            progress = if (d > 0) (positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
            if (isPlaying) handler.postDelayed(this, 33)
        }
    }

    fun play(sound: Sound) {
        if (!SoundStore.fileOf(sound).exists()) return
        SoundStore.markUsed(sound.id)
        when (AppSettings.playMode) {
            PlayMode.QUEUE ->
                if (primary != null) { if (queue.size < MAX_QUEUE) queue.add(sound) } else start(sound)
            PlayMode.REPLACE -> { stopAll(); start(sound) }
            else -> start(sound)
        }
    }

    private fun start(sound: Sound) {
        val file = SoundStore.fileOf(sound)
        if (!file.exists()) { advance(); return }
        val oldest = synchronized(active) { if (active.size >= MAX_ACTIVE) active.first() else null }
        oldest?.let { drop(it) }

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                attachBoost(p)
                p.start()
                if (p === primary) {
                    durationMs = p.duration
                    isPlaying = true
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                }
            }
            mp.setOnCompletionListener { finished(it) }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "player error what=$what extra=$extra")
                finished(p)
                true
            }
            synchronized(active) { active.add(mp) }
            primary = mp
            nowPlaying = sound
            isBusy = true
            isPlaying = false
            progress = 0f
            durationMs = sound.durationMs.toInt()
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "play failed for ${sound.name}", e)
            drop(mp)
            advance()
        }
    }

    /** Adds about +8 dB when Call boost is on, so a speaker-to-mic setup (calls, game voice chat) hears it better. */
    private fun attachBoost(mp: MediaPlayer) {
        if (!AppSettings.callBoost) return
        try {
            val enhancer = LoudnessEnhancer(mp.audioSessionId)
            enhancer.setTargetGain(800)
            enhancer.setEnabled(true)
            boosters[mp] = enhancer
        } catch (e: Exception) {
            Log.w(TAG, "loudness boost unavailable on this device", e)
        }
    }

    private fun finished(mp: MediaPlayer) {
        val wasPrimary = mp === primary
        drop(mp)
        if (wasPrimary) advance()
    }

    private fun advance() {
        if (AppSettings.playMode == PlayMode.QUEUE && queue.isNotEmpty()) start(queue.removeAt(0))
    }

    /** Pause/resume the current sound, or replay the last one if nothing is active. */
    fun togglePause() {
        val mp = primary
        if (mp == null) {
            nowPlaying?.let { start(it) }
            return
        }
        try {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
                handler.removeCallbacks(ticker)
            } else {
                mp.start()
                isPlaying = true
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "toggle before ready", e)
        }
    }

    /** Ends the current sound and moves on to the next queued one. */
    fun skip() {
        val mp = primary ?: return
        try { if (mp.isPlaying) mp.stop() } catch (_: IllegalStateException) {}
        finished(mp)
    }

    fun removeFromQueue(index: Int) {
        if (index in queue.indices) queue.removeAt(index)
    }

    fun positionMs(): Int = try {
        primary?.currentPosition ?: 0
    } catch (e: IllegalStateException) {
        0
    }

    fun stopAll() {
        queue.clear()
        val copy = synchronized(active) { active.toList() }
        copy.forEach {
            try { if (it.isPlaying) it.stop() } catch (_: IllegalStateException) {}
            drop(it)
        }
        primary = null
        nowPlaying = null
        isBusy = false
        isPlaying = false
        progress = 0f
        handler.removeCallbacks(ticker)
    }

    private fun drop(mp: MediaPlayer) {
        synchronized(active) { active.remove(mp) }
        boosters.remove(mp)?.let { try { it.release() } catch (_: Exception) {} }
        if (mp === primary) {
            primary = null
            isBusy = false
            isPlaying = false
            progress = 0f
            handler.removeCallbacks(ticker)
        }
        try { mp.release() } catch (_: Exception) {}
    }
}

/** Tap-to-start, tap-to-stop mic recorder. Saves AAC/m4a straight into the sound library. */
object VoiceRecorder {
    private const val TAG = "VoiceRecorder"

    var isRecording by mutableStateOf(false)
        private set

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    fun toggle(context: Context) {
        if (isRecording) stop(context) else start(context)
    }

    private fun start(context: Context) {
        val app = context.applicationContext
        if (!Entitlement.canAddSound()) {
            toast(app, "Free plan holds ${Entitlement.FREE_SOUND_LIMIT} sounds. Go Pro for unlimited.")
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(app, "Mic permission missing. Open VYBE, allow mic, relaunch the board.")
            return
        }
        val file = SoundStore.newRecordingFile()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            target = file
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            try { r.release() } catch (_: Exception) {}
            file.delete()
            toast(app, "Couldn't start recording")
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        val r = recorder ?: return
        val file = target
        recorder = null
        target = null
        isRecording = false
        var ok = true
        try { r.stop() } catch (e: RuntimeException) { ok = false } finally { r.release() }
        if (ok && file != null && file.length() > 0) {
            SoundStore.addRecording(file)
            toast(app, "Saved to your board")
        } else {
            file?.delete()
            toast(app, "Recording too short, discarded")
        }
    }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
