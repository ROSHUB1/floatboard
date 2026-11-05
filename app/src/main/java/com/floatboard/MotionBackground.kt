package com.floatboard

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies the bundled looping clip (assets/motion/vybe_bg.mp4) to a cache file once, so MediaPlayer
 * can open it by Uri. Cheap after the first call: later calls just stat the existing file.
 */
private object MotionAsset {
    private const val NAME = "vybe_bg.mp4"

    suspend fun file(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val out = File(context.applicationContext.cacheDir, NAME)
            if (!out.exists() || out.length() == 0L) {
                context.applicationContext.assets.open("motion/$NAME").use { src ->
                    out.outputStream().use { dst -> src.copyTo(dst) }
                }
            }
            out
        } catch (e: Exception) {
            Log.e("MotionAsset", "failed to stage motion background", e)
            null
        }
    }
}

/**
 * A silent, looping video behind a screen's content, with a dark scrim on top so text stays readable.
 * Respects AppSettings.motionBg: when off, this draws nothing and the caller's own background shows
 * through. The decoder only runs while this composable is in the tree, so leaving the screen (tab
 * switch) releases it via the view's onSurfaceTextureDestroyed callback.
 */
@Composable
fun MotionBackground(modifier: Modifier = Modifier) {
    if (!AppSettings.motionBg) return
    val context = LocalContext.current
    val file by produceState<File?>(null) { value = MotionAsset.file(context) }
    var ready by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        file?.let { f ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> CropTextureView(ctx, f) { ready = true } }
            )
        }
        // Dark scrim so content laid over the video (titles, cards) stays legible.
        AnimatedVisibility(visible = ready, enter = fadeIn(tween(400))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xCC08080A), Color(0x9908080A), Color(0xE608080A)))
                )
            )
        }
    }
}

/**
 * Plays [file] on loop, muted, filling itself the way CSS `background-size: cover` would: the content
 * is scaled up and center-cropped via a transform Matrix, while the view itself stays exactly the size
 * Compose gives it (no custom measuring, so it plays safely inside Compose's view interop).
 */
private class CropTextureView(
    context: Context,
    private val file: File,
    private val onReady: () -> Unit
) : TextureView(context) {

    private var player: MediaPlayer? = null
    private var videoW = 0
    private var videoH = 0

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isOpaque = false
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = start(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCrop()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                release()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCrop() }
    }

    private fun start(st: SurfaceTexture) {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, Uri.fromFile(file))
            mp.setSurface(Surface(st))
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyCrop()
            }
            mp.setOnPreparedListener {
                it.start()
                onReady()
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Log.e("MotionBackground", "player setup failed", e)
        }
    }

    private fun applyCrop() {
        if (videoW == 0 || videoH == 0 || width == 0 || height == 0) return
        val viewRatio = width.toFloat() / height
        val videoRatio = videoW.toFloat() / videoH
        val sx: Float
        val sy: Float
        if (videoRatio > viewRatio) {
            sx = videoRatio / viewRatio
            sy = 1f
        } else {
            sx = 1f
            sy = viewRatio / videoRatio
        }
        val matrix = Matrix()
        matrix.setScale(sx, sy, width / 2f, height / 2f)
        setTransform(matrix)
    }

    private fun release() {
        player?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        player = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
