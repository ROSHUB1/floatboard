package com.floatboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shared between the service (which resizes the window) and the UI (which shows the size badge). */
object OverlayUiState {
    var resizing by mutableStateOf(false)
}

@Composable
fun OverlayRoot(
    onDragStart: () -> Unit,
    onDragMove: () -> Unit,
    onWindowMode: (bubble: Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var bubble by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }

    // Fade/scale out, resize the window while it's invisible, then fade/scale back in.
    fun switchMode(toBubble: Boolean) {
        scope.launch {
            show = false
            delay(140)
            onWindowMode(toBubble)
            bubble = toBubble
            show = true
        }
    }

    fun close() {
        scope.launch {
            show = false
            delay(150)
            onClose()
        }
    }

    FloatTheme {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = show,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.9f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f)
            ) {
                if (bubble) {
                    Bubble(onDragStart, onDragMove) { switchMode(false) }
                } else {
                    Board(
                        onDragStart = onDragStart,
                        onDragMove = onDragMove,
                        onMinimize = { switchMode(true) },
                        onClose = { close() },
                        onOpenApp = onOpenApp
                    )
                }
            }
        }
    }
}

private fun Modifier.dragHandle(onStart: () -> Unit, onMove: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDrag = { change, _ ->
                change.consume()
                onMove()
            }
        )
    }

@Composable
private fun Bubble(onDragStart: () -> Unit, onDragMove: () -> Unit, onRestore: () -> Unit) {
    val ring by animateColorAsState(if (SoundPlayer.isPlaying) Pal.Accent else Pal.Outline, tween(200), label = "bubbleRing")
    Box(
        Modifier.fillMaxSize().clip(CircleShape).background(Pal.Surface)
            .border(2.dp, ring, CircleShape)
            .dragHandle(onDragStart, onDragMove).clickable(onClick = onRestore),
        contentAlignment = Alignment.Center
    ) { AppIcon(R.drawable.ic_logo, 36.dp, description = "Open soundboard", modifier = Modifier.pulse(SoundPlayer.isPlaying)) }
}

@Composable
private fun Board(
    onDragStart: () -> Unit,
    onDragMove: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val sounds = SoundStore.sounds
    val favsOnly = AppSettings.overlayFavsOnly
    val shown = sounds.filter { !favsOnly || it.fav }.sortedByDescending { it.fav }
    val recording = VoiceRecorder.isRecording
    val resizing = OverlayUiState.resizing
    val shape = RoundedCornerShape(20.dp)
    val borderColor by animateColorAsState(if (resizing) Pal.Accent else Pal.Outline, tween(150), label = "boardBorder")
    var windowPx by remember { mutableStateOf(IntSize.Zero) }
    val thumb = AppSettings.tileThumbDp

    Box(Modifier.fillMaxSize().onSizeChanged { windowPx = it }) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Pal.Bg.copy(alpha = AppSettings.opacity))
                .border(if (resizing) 2.dp else 1.dp, borderColor, shape)
                .padding(12.dp)   // the padding is the "edge gutter": hold it to resize
        ) {
            Column(Modifier.dragHandle(onDragStart, onDragMove)) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Pal.Outline))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_logo, 26.dp, modifier = Modifier.pulse(SoundPlayer.isPlaying))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "FloatBoard", color = Pal.TextHi, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    IconBtn(
                        R.drawable.ic_star, "Favorites only", { AppSettings.updateOverlayFavsOnly(!favsOnly) },
                        size = 32.dp,
                        container = if (favsOnly) Pal.Accent else Pal.SurfaceHi,
                        tint = if (favsOnly) Pal.Bg else Pal.TextLo
                    )
                    Spacer(Modifier.width(6.dp))
                    IconBtn(R.drawable.ic_minimize, "Minimize", onMinimize, size = 32.dp)
                    Spacer(Modifier.width(6.dp))
                    IconBtn(R.drawable.ic_close, "Close", onClose, size = 32.dp)
                }
            }

            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(
                            R.drawable.ic_speaker, 56.dp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).pulse(true, 0.05f, 1400)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (favsOnly && sounds.isNotEmpty()) "No favorites yet.\nStar sounds in the Sounds tab."
                            else "No sounds yet.\nOpen the app to add some, or tap the mic to record one.",
                            color = Pal.TextLo, fontSize = 12.sp, textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(thumb.dp + 20.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.id }) { sound ->
                        val active = SoundPlayer.isBusy && SoundPlayer.nowPlaying?.id == sound.id
                        SoundTile(sound, thumb, active) { SoundPlayer.play(sound) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImageBtn(R.drawable.ic_stop, "Stop all", { SoundPlayer.stopAll() }, size = 46.dp)
                Spacer(Modifier.width(10.dp))
                MicButton(recording) { VoiceRecorder.toggle(context) }
                Spacer(Modifier.width(10.dp))
                QueueStrip(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ImageBtn(R.drawable.ic_grid, "Open app", onOpenApp, size = 46.dp)
            }
        }

        AnimatedVisibility(
            visible = resizing,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.9f),
            exit = fadeOut(tween(160))
        ) {
            Text(
                "${(windowPx.width / density).roundToInt()} \u00D7 ${(windowPx.height / density).roundToInt()}",
                color = Pal.Bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Pal.Accent).padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun SoundTile(sound: Sound, thumbDp: Int, active: Boolean, onTap: () -> Unit) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "tileScale"
    )
    val borderColor by animateColorAsState(if (active) Pal.Accent else Pal.Outline, tween(160), label = "tileBorder")
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(Pal.Surface)
            .border(if (active) 1.5.dp else 1.dp, borderColor, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onTap()
                    }
                )
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SoundThumb(sound, thumbDp.dp, 10.dp, progress = if (active) ({ SoundPlayer.progress }) else null)
        Spacer(Modifier.height(5.dp))
        Text(
            sound.name, color = if (active) Pal.Accent else Pal.TextHi, fontSize = 10.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            lineHeight = 12.sp, minLines = 2
        )
    }
}

private data class QueueEntry(val sound: Sound, val queueIndex: Int)

/** Sits beside the mic: the playing sound first (accent ring), then everything waiting in line. */
@Composable
private fun QueueStrip(modifier: Modifier = Modifier) {
    val current = if (SoundPlayer.isBusy) SoundPlayer.nowPlaying else null
    val queued = SoundPlayer.queue.toList()

    BoxWithConstraints(modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
        if (current == null && queued.isEmpty()) {
            Text(
                "Tap sounds to queue them", color = Pal.TextLo, fontSize = 10.sp,
                lineHeight = 12.sp, maxLines = 2
            )
        } else {
            val entries = buildList {
                if (current != null) add(QueueEntry(current, -1))
                queued.forEachIndexed { i, s -> add(QueueEntry(s, i)) }
            }
            val slots = ((maxWidth + 6.dp) / 40.dp).toInt().coerceAtLeast(1)
            val visibleCount = if (entries.size > slots) (slots - 1).coerceAtLeast(0) else entries.size
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                entries.take(visibleCount).forEach { QueueChip(it) }
                if (entries.size > visibleCount) MoreChip(entries.size - visibleCount)
            }
        }
    }
}

@Composable
private fun QueueChip(e: QueueEntry) {
    val isCurrent = e.queueIndex < 0
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f), initialScale = 0.5f)
    ) {
        val shape = RoundedCornerShape(9.dp)
        Box(
            Modifier.size(34.dp)
                .graphicsLayer { alpha = if (isCurrent && !SoundPlayer.isPlaying) 0.55f else 1f }
                .clip(shape)
                .border(if (isCurrent) 2.dp else 1.dp, if (isCurrent) Pal.Accent else Pal.Outline, shape)
                .pointerInput(isCurrent, e.queueIndex) {
                    detectTapGestures(
                        onTap = {
                            if (isCurrent) SoundPlayer.togglePause() else SoundPlayer.removeFromQueue(e.queueIndex)
                        },
                        onLongPress = { if (isCurrent) SoundPlayer.skip() }
                    )
                }
        ) {
            SoundThumb(e.sound, 34.dp, 8.dp, progress = if (isCurrent) ({ SoundPlayer.progress }) else null)
        }
    }
}

@Composable
private fun MoreChip(count: Int) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Pal.SurfaceHi),
        contentAlignment = Alignment.Center
    ) { Text("+$count", color = Pal.TextHi, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun MicButton(recording: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (recording) PulseRing()
        IconBtn(
            res = if (recording) R.drawable.ic_stop_flat else R.drawable.ic_mic,
            description = if (recording) "Stop recording" else "Record",
            onClick = onClick,
            size = 56.dp,
            container = if (recording) Pal.Danger else Pal.SurfaceHi
        )
    }
}

@Composable
private fun PulseRing() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val ringScale by transition.animateFloat(
        1f, 1.45f, infiniteRepeatable(tween(950), RepeatMode.Restart), label = "ringScale"
    )
    val ringAlpha by transition.animateFloat(
        0.5f, 0f, infiniteRepeatable(tween(950), RepeatMode.Restart), label = "ringAlpha"
    )
    Box(
        Modifier.size(56.dp)
            .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
            .clip(CircleShape).background(Pal.Danger)
    )
}
