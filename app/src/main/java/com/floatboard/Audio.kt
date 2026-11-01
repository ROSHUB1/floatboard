package com.floatboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Playback with a "now playing" state the UI can observe.
 * Overlap off (default): a new tap replaces the current sound. Overlap on: up to 8 clips at once.
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private const val MAX_ACTIVE = 8
    private val active = mutableListOf<MediaPlayer>()
    private var primary: MediaPlayer? = null

    var nowPlaying by mutableStateOf<Sound?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    fun play(sound: Sound) {
        val file = SoundStore.fileOf(sound)
        if (!file.exists()) return
        if (!AppSettings.overlap) {
            stopAll()
        } else {
            val oldest = synchronized(active) { if (active.size >= MAX_ACTIVE) active.first() else null }
            oldest?.let { drop(it) }
        }

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
                p.start()
                if (p === primary) {
                    durationMs = p.duration
                    isPlaying = true
                }
            }
            mp.setOnCompletionListener { drop(it) }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "player error what=$what extra=$extra")
                drop(p)
                true
            }
            synchronized(active) { active.add(mp) }
            primary = mp
            nowPlaying = sound
            isPlaying = false
            durationMs = sound.durationMs.toInt()
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "play failed for ${sound.name}", e)
            drop(mp)
        }
    }

    /** Pause/resume the current sound, or replay it if it already finished. */
    fun togglePause() {
        val mp = primary
        if (mp == null) {
            nowPlaying?.let { play(it) }
            return
        }
        try {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "toggle before ready", e)
        }
    }

    fun positionMs(): Int = try {
        primary?.currentPosition ?: 0
    } catch (e: IllegalStateException) {
        0
    }

    fun stopAll() {
        val copy = synchronized(active) { active.toList() }
        copy.forEach {
            try { if (it.isPlaying) it.stop() } catch (_: IllegalStateException) {}
            drop(it)
        }
        primary = null
        nowPlaying = null
        isPlaying = false
    }

    private fun drop(mp: MediaPlayer) {
        synchronized(active) { active.remove(mp) }
        if (mp === primary) {
            primary = null
            isPlaying = false
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
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(app, "Mic permission missing. Open FloatBoard, allow mic, relaunch the board.")
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
