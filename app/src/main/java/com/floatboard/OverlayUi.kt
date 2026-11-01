package com.floatboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

@Composable
fun OverlayRoot(onDrag: (Int, Int) -> Unit, onClose: () -> Unit, onOpenApp: () -> Unit) {
    var minimized by remember { mutableStateOf(false) }
    var large by remember { mutableStateOf(false) }
    FloatTheme {
        if (minimized) {
            Bubble(onDrag) { minimized = false }
        } else {
            Board(
                large = large,
                onDrag = onDrag,
                onMinimize = { minimized = true },
                onToggleSize = { large = !large },
                onClose = onClose,
                onOpenApp = onOpenApp
            )
        }
    }
}

private fun Modifier.dragHandle(onDrag: (Int, Int) -> Unit): Modifier =
    pointerInput(Unit) {
        detectDragGestures { change, amount ->
            change.consume()
            onDrag(amount.x.roundToInt(), amount.y.roundToInt())
        }
    }

@Composable
private fun Bubble(onDrag: (Int, Int) -> Unit, onRestore: () -> Unit) {
    val ring = if (SoundPlayer.isPlaying) Pal.Accent else Pal.Outline
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Pal.Surface)
            .border(2.dp, ring, CircleShape)
            .dragHandle(onDrag).clickable(onClick = onRestore),
        contentAlignment = Alignment.Center
    ) { AppIcon(R.drawable.ic_logo, 36.dp, description = "Open soundboard") }
}

@Composable
private fun Board(
    large: Boolean,
    onDrag: (Int, Int) -> Unit,
    onMinimize: () -> Unit,
    onToggleSize: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit
) {
    val context = LocalContext.current
    val sounds = SoundStore.sounds
    val recording = VoiceRecorder.isRecording
    val shape = RoundedCornerShape(20.dp)

    Column(
        Modifier
            .size(if (large) 360.dp else 320.dp, if (large) 600.dp else 480.dp)
            .clip(shape)
            .background(Pal.Bg.copy(alpha = AppSettings.opacity))
            .border(1.dp, Pal.Outline, shape)
            .padding(12.dp)
    ) {
        Column(Modifier.dragHandle(onDrag)) {
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Pal.Outline))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_logo, 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "FloatBoard", color = Pal.TextHi, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                IconBtn(R.drawable.ic_expand, "Resize", onToggleSize, size = 32.dp)
                Spacer(Modifier.width(6.dp))
                IconBtn(R.drawable.ic_minimize, "Minimize", onMinimize, size = 32.dp)
                Spacer(Modifier.width(6.dp))
                IconBtn(R.drawable.ic_close, "Close", onClose, size = 32.dp)
            }
        }

        NowPlayingCard()

        if (sounds.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No sounds yet.\nOpen the app to add some, or tap the mic to record one.",
                    color = Pal.TextLo, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(AppSettings.columns),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sounds, key = { it.id }) { sound ->
                    SoundTile(sound, SoundPlayer.nowPlaying?.id == sound.id && SoundPlayer.isPlaying) {
                        SoundPlayer.play(sound)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(R.drawable.ic_stop, "Stop all", { SoundPlayer.stopAll() }, size = 44.dp)
            IconBtn(
                res = if (recording) R.drawable.ic_stop else R.drawable.ic_mic,
                description = if (recording) "Stop recording" else "Record",
                onClick = { VoiceRecorder.toggle(context) },
                size = 60.dp,
                container = if (recording) Pal.Danger else Pal.SurfaceHi
            )
            IconBtn(R.drawable.ic_grid, "Open app", onOpenApp, size = 44.dp)
        }
    }
}

@Composable
private fun NowPlayingCard() {
    val sound = SoundPlayer.nowPlaying
    val playing = SoundPlayer.isPlaying
    var pos by remember { mutableIntStateOf(0) }

    LaunchedEffect(sound?.id, playing) {
        if (playing) {
            while (isActive) {
                pos = SoundPlayer.positionMs()
                delay(50)
            }
        } else {
            pos = SoundPlayer.positionMs()
        }
    }

    val duration = SoundPlayer.durationMs.coerceAtLeast(1)
    val progress = (pos.toFloat() / duration).coerceIn(0f, 1f)

    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp)).background(Pal.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SoundThumb(sound, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                sound?.name ?: "Nothing playing", color = Pal.TextHi, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (sound != null) "${fmt(pos)} / ${fmt(duration)}" else "Tap a sound to play",
                color = Pal.TextLo, fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Pal.Outline)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Pal.Accent))
            }
        }
        if (sound != null) {
            IconBtn(
                res = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                description = if (playing) "Pause" else "Play",
                onClick = { SoundPlayer.togglePause() },
                size = 40.dp, container = Pal.Accent, tint = Pal.Bg
            )
        }
    }
}

@Composable
private fun SoundTile(sound: Sound, active: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().height(88.dp).clip(shape).background(Pal.Surface)
            .border(if (active) 1.5.dp else 1.dp, if (active) Pal.Accent else Pal.Outline, shape)
            .clickable(onClick = onTap).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SoundThumb(sound, 40.dp, 8.dp)
        Spacer(Modifier.height(5.dp))
        Text(
            sound.name, color = if (active) Pal.Accent else Pal.TextHi, fontSize = 10.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 12.sp
        )
    }
}
