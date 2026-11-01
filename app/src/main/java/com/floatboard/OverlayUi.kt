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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Cyan = Color(0xFF00E5FF)
private val PanelBg = Color(0xFF0A0C12)
private val ChipBg = Color(0xFF1B2030)
private val SoftText = Color(0xFFB8C0CC)

@Composable
fun OverlayRoot(onDrag: (Int, Int) -> Unit, onClose: () -> Unit, onOpenApp: () -> Unit) {
    var minimized by remember { mutableStateOf(false) }
    var large by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme()) {
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
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Cyan, Color(0xFF0077B6))))
            .dragHandle(onDrag)
            .clickable(onClick = onRestore),
        contentAlignment = Alignment.Center
    ) { Text("\uD83E\uDD54", fontSize = 26.sp) }
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
    val shape = RoundedCornerShape(22.dp)

    Column(
        Modifier
            .size(if (large) 340.dp else 300.dp, if (large) 560.dp else 440.dp)
            .clip(shape)
            .background(PanelBg)
            .border(1.dp, Color(0xFF1F2430), shape)
            .padding(10.dp)
    ) {
        // Header: expand, minimize, drag bar, close
        Row(Modifier.fillMaxWidth().dragHandle(onDrag), verticalAlignment = Alignment.CenterVertically) {
            ChromeButton("\u2922", onToggleSize)
            Spacer(Modifier.width(6.dp))
            ChromeButton("\u2013", onMinimize)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(4.dp).clip(CircleShape).background(Color(0xFF3B82F6)))
            }
            ChromeButton("\u2715", onClose)
        }

        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                "My soundboard \u00B7 ${sounds.size}",
                color = Color(0xFFE6EAF2),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(ChipBg).padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        if (sounds.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No sounds yet.\nHit \u2795 to open FloatBoard and add some, or tap the mic to record one.",
                    color = SoftText, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sounds, key = { it.id }) { sound -> SoundTile(sound) { SoundPlayer.play(sound) } }
            }
        }

        // Bottom bar: stop all, mic, add
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton("\u25A0", 40.dp) { SoundPlayer.stopAll() }
            MicButton(recording) { VoiceRecorder.toggle(context) }
            RoundButton("\uFF0B", 40.dp, onOpenApp)
        }
    }
}

@Composable
private fun SoundTile(sound: Sound, onTap: () -> Unit) {
    val hue = ((sound.id.hashCode() and 0x7fffffff) % 360).toFloat()
    val c1 = Color.hsv(hue, 0.55f, 0.85f)
    val c2 = Color.hsv((hue + 40f) % 360f, 0.7f, 0.55f)
    Column(
        Modifier.clickable(onClick = onTap).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center
        ) {
            Text(sound.name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text(
            sound.name, color = SoftText, fontSize = 9.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp).padding(top = 3.dp)
        )
    }
}

@Composable
private fun ChromeButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(ChipBg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFFD0D6E2), fontSize = 12.sp) }
}

@Composable
private fun RoundButton(label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(ChipBg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFFD0D6E2), fontSize = 16.sp) }
}

@Composable
private fun MicButton(recording: Boolean, onClick: () -> Unit) {
    val glow = if (recording) listOf(Color(0xFFFF5470), Color(0xFFB00020)) else listOf(Cyan, Color(0xFF0077B6))
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(glow))
            .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(if (recording) "\u25A0" else "\uD83C\uDF99", fontSize = 26.sp, color = Color.White) }
}
