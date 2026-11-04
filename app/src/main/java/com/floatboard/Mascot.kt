package com.floatboard

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The mascot is a short loop stored as WebP frames with transparency (assets/mascot/mascot_000.webp, ...).
 * Frames are decoded once when the mascot first appears and freed when it leaves the screen,
 * so it costs nothing while you're on another tab.
 */
object MascotFrames {
    private const val DIR = "mascot"
    const val FPS = 10

    @Volatile
    private var cache: List<ImageBitmap>? = null

    private fun names(context: Context): List<String> =
        (context.applicationContext.assets.list(DIR) ?: emptyArray()).filter { it.endsWith(".webp") }.sorted()

    private fun decode(context: Context, name: String): ImageBitmap? = try {
        context.applicationContext.assets.open("$DIR/$name").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    /** First frame only, cheap enough to decode right away so the card never looks empty. */
    fun still(context: Context): ImageBitmap? = names(context).firstOrNull()?.let { decode(context, it) }

    suspend fun load(context: Context): List<ImageBitmap> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val frames = names(context).mapNotNull { decode(context, it) }
            cache = frames
            frames
        }
    }

    fun release() {
        cache = null
    }
}

@Composable
fun Mascot(size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember { MascotFrames.still(context) }
    val frames by produceState<List<ImageBitmap>>(emptyList()) { value = MascotFrames.load(context) }
    DisposableEffect(Unit) { onDispose { MascotFrames.release() } }

    val count = frames.size.coerceAtLeast(1)
    val transition = rememberInfiniteTransition(label = "mascot")
    val position by transition.animateFloat(
        initialValue = 0f,
        targetValue = count.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(durationMillis = count * 1000 / MascotFrames.FPS, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "mascotFrame"
    )

    // The frame index is read while drawing, so only the drawing is redone each frame, not the layout.
    Canvas(modifier.size(size)) {
        val list = frames
        val image = if (list.isEmpty()) still else list[position.toInt().coerceIn(0, list.size - 1)]
        if (image != null) {
            drawImage(
                image,
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                filterQuality = FilterQuality.Medium
            )
        }
    }
}
