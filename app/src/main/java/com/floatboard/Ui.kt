package com.floatboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One accent color (amber), reserved for "this is what's playing". Everything else is flat dark. */
object Pal {
    val Bg = Color(0xFF0E1015)
    val Surface = Color(0xFF171A21)
    val SurfaceHi = Color(0xFF1F232C)
    val Outline = Color(0xFF2A2F3A)
    val TextHi = Color(0xFFEDEFF3)
    val TextLo = Color(0xFF9AA3B2)
    val Accent = Color(0xFFFFB84D)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun FloatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Accent, onPrimary = Pal.Bg,
            background = Pal.Bg, onBackground = Pal.TextHi,
            surface = Pal.Surface, onSurface = Pal.TextHi,
            surfaceVariant = Pal.SurfaceHi, onSurfaceVariant = Pal.TextLo,
            outline = Pal.Outline, error = Pal.Danger
        ),
        content = content
    )
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmt(ms: Int): String = fmt(ms.toLong())

/**
 * Every button icon in the app goes through here. To use your own artwork, drop a file with the same
 * name into res/drawable (e.g. ic_play.png) and delete the matching .xml. Default tint is none,
 * so colored PNGs show as-is.
 */
@Composable
fun AppIcon(
    @DrawableRes res: Int,
    size: Dp = 22.dp,
    tint: Color = Color.Unspecified,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    Icon(painterResource(res), contentDescription = description, tint = tint, modifier = modifier.size(size))
}

@Composable
fun IconBtn(
    @DrawableRes res: Int,
    description: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    container: Color = Pal.SurfaceHi,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.size(size).clip(CircleShape).background(container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(res, size * 0.5f, tint, description) }
}

/**
 * Sound image: picked cover if there is one, otherwise a muted colored tile with initials.
 * [progress] (read while drawing, so it never recomposes) paints a soft left-to-right sweep over the image.
 */
@Composable
fun SoundThumb(sound: Sound?, size: Dp, corner: Dp = 10.dp, progress: (() -> Float)? = null) {
    val cover = if (sound != null) rememberCover(sound) else null
    val bg = if (sound == null) Pal.SurfaceHi
    else Color.hsv(((sound.id.hashCode() and 0x7fffffff) % 360).toFloat(), 0.35f, 0.42f)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(bg)
            .drawWithContent {
                drawContent()
                val p = progress?.invoke() ?: 0f
                if (p > 0f) {
                    val w = this.size.width
                    val h = this.size.height
                    val x = w * p
                    drawRect(Pal.Accent.copy(alpha = 0.30f), size = Size(x, h))
                    val soft = minOf(x, w * 0.22f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Pal.Accent.copy(alpha = 0f), Pal.Accent.copy(alpha = 0.6f)),
                            startX = x - soft, endX = x
                        ),
                        topLeft = Offset(x - soft, 0f), size = Size(soft, h)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            cover != null -> Image(
                bitmap = cover, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            sound != null -> Text(
                sound.name.take(2).uppercase(), color = Pal.TextHi,
                fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp
            )
            else -> AppIcon(R.drawable.ic_music, size * 0.5f, Pal.TextLo)
        }
    }
}

@Composable
fun ActionTile(
    @DrawableRes res: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.clip(shape).background(Pal.Surface)
            .border(1.dp, if (highlight) Pal.Danger else Pal.Outline, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(res, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = Pal.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ActionRow(@DrawableRes res: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(res, 22.dp, if (danger) Pal.Danger else Pal.TextHi)
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (danger) Pal.Danger else Pal.TextHi, fontSize = 15.sp)
    }
}
