package com.floatboard

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import java.util.Locale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One accent color (amber), reserved for "this is what's playing". Everything else is flat dark. */
object Pal {
    // One cohesive dark system. Purple/blue stay only in the logo and mascot art, not scattered
    // through the UI, so the single accent below is what communicates "interactive" everywhere.
    val Bg = Color(0xFF111214)
    val Surface = Color(0xFF1A1C1F)
    val SurfaceHi = Color(0xFF22252A)
    val Elevated = Color(0xFF292C31)
    val Outline = Color(0xFF303338)
    val TextHi = Color(0xFFF2F2F0)
    val TextLo = Color(0xFF92969D)
    val Accent = Color(0xFF75B9C2)
    val AccentSoft = Color(0x2975B9C2)   // accent at ~16%, for icon chips and selected backgrounds
    val Accent2 = Color(0xFFA7D7DD)      // lighter tint of the same accent (leading edge of the playback sweep)
    val Danger = Color(0xFFE5584E)
}

/** Type scale. Every text in the app picks one of these. */
object Ty {
    val LargeTitle = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val Headline = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val Callout = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val Overline = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
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
    if (ms in 1..9_999) return String.format(Locale.US, "%.1fs", ms / 1000f)   // short clips read 0.4s, not 0:00
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
 * Sound image: the picked cover if there is one, otherwise a two-tone gradient tile with an equalizer glyph.
 * [progress] (read while drawing, so it never recomposes) paints a soft left-to-right sweep over the image.
 */
@Composable
fun SoundThumb(sound: Sound?, size: Dp, corner: Dp = 10.dp, progress: (() -> Float)? = null) {
    val cover = if (sound != null) rememberCover(sound) else null
    val tile: Brush = if (sound == null) SolidColor(Pal.SurfaceHi) else {
        val hue = ((sound.id.hashCode() and 0x7fffffff) % 360).toFloat()
        Brush.linearGradient(listOf(Color.hsv(hue, 0.42f, 0.62f), Color.hsv((hue + 28f) % 360f, 0.5f, 0.34f)))
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(tile)
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
                            listOf(Pal.Accent.copy(alpha = 0f), Pal.Accent2.copy(alpha = 0.7f)),
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
            sound != null -> AppIcon(R.drawable.ic_bars, size * 0.46f, Color.White.copy(alpha = 0.88f))
            else -> AppIcon(R.drawable.ic_music, size * 0.5f, Pal.TextLo)
        }
    }
}

/** Card surface with a soft top-lit gradient and a hairline highlight, so cards read as raised, not flat. */
fun Modifier.premiumSurface(shape: Shape = RoundedCornerShape(14.dp)): Modifier =
    this.clip(shape).background(Pal.Surface).border(1.dp, Pal.Outline.copy(alpha = 0.6f), shape)

/** A flat elevated surface one step brighter than [premiumSurface], for the one hero element on a screen. */
fun Modifier.elevatedSurface(shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    this.clip(shape).background(Pal.Elevated)

/** Rounded square with an accent-tinted icon inside: the one icon treatment for flat (single-colour) icons. */
@Composable
fun IconChip(
    @DrawableRes res: Int,
    tint: Color = Pal.Accent,
    container: Color = Pal.AccentSoft,
    size: Dp = 40.dp
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(container),
        contentAlignment = Alignment.Center
    ) { AppIcon(res, size * 0.5f, tint) }
}

@Composable
fun ActionTile(
    @DrawableRes res: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Row(
        modifier.bouncyClick(onClick).premiumSurface(RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(
            res,
            tint = if (highlight) Pal.Danger else Pal.Accent,
            container = if (highlight) Pal.Danger.copy(alpha = 0.16f) else Pal.AccentSoft
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = Pal.TextHi, style = Ty.Body.copy(fontWeight = FontWeight.Medium))
    }
}

/**
 * Square tile for the Home "Quick Access" row: an icon chip centered above a label, in a raised
 * card. Matches the reference layout (icon on top, label below, four across).
 */
@Composable
fun QuickAccessTile(
    @DrawableRes res: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Pal.Accent,
    container: Color = Pal.AccentSoft
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.bouncyClick(onClick)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C1C22), Color(0xFF151518))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x0AFFFFFF))), shape)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconChip(res, tint = tint, container = container, size = 38.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            label, color = Pal.TextHi, style = Ty.Caption.copy(fontWeight = FontWeight.Medium),
            textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp
        )
    }
}

@Composable
fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Pal.Outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Pal.Accent))
    }
}

/** One compact, low-emphasis row: icon chip, label, tap target. Several of these grouped together
 * (with a hairline divider between) read as one organized list instead of separate cards. */
@Composable
fun CompactRow(
    @DrawableRes res: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(res, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = Pal.TextHi, style = Ty.Body, modifier = Modifier.weight(1f))
    }
}

/**
 * A full-width pill button for the one or two most important actions on a screen, visually distinct
 * from the smaller [QuickAccessTile] grid: filled when primary, outlined when secondary, and a danger
 * fill while recording.
 */
@Composable
fun PrimaryActionButton(
    @DrawableRes res: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    danger: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = when {
        danger -> Pal.Danger
        filled -> Pal.Accent
        else -> Color.Transparent
    }
    val fg = if (filled || danger) Pal.Bg else Pal.TextHi
    Row(
        modifier.bouncyClick(onClick).clip(shape).background(bg)
            .then(if (!filled && !danger) Modifier.border(1.dp, Pal.Outline, shape) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        AppIcon(res, 18.dp, fg)
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, style = Ty.Body.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun ActionRow(
    @DrawableRes res: Int,
    label: String,
    danger: Boolean = false,
    keepColors: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(res, 22.dp, if (keepColors) Color.Unspecified else if (danger) Pal.Danger else Pal.TextHi)
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (danger) Pal.Danger else Pal.TextHi, fontSize = 15.sp)
    }
}

// ───────────────────────── Motion helpers ─────────────────────────

/** Gentle breathing scale. Only runs while [enabled], so it costs nothing when idle. */
fun Modifier.pulse(enabled: Boolean, amount: Float = 0.08f, periodMs: Int = 700): Modifier = composed {
    if (!enabled) {
        this
    } else {
        val transition = rememberInfiniteTransition(label = "pulse")
        val s by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f + amount,
            animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseScale"
        )
        this.graphicsLayer { scaleX = s; scaleY = s }
    }
}

/** Squishes to ~84% while pressed and springs back with a little overshoot. */
fun Modifier.bouncyClick(onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(onClick)
    val view = LocalView.current
    val s by animateFloatAsState(
        if (pressed) 0.84f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "bouncy"
    )
    this.graphicsLayer { scaleX = s; scaleY = s }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    current()
                }
            )
        }
}

/** A picture used directly as a button (no circle behind it). */
@Composable
fun ImageBtn(
    @DrawableRes res: Int,
    description: String,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(size).bouncyClick(onClick), contentAlignment = Alignment.Center) {
        AppIcon(res, size * 0.82f, description = description)
    }
}

/** Play and pause pictures that pop into each other. */
@Composable
fun PlayPauseBtn(playing: Boolean, onClick: () -> Unit, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).bouncyClick(onClick).clip(CircleShape).background(Pal.SurfaceHi),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.55f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.55f))
            },
            label = "playPause"
        ) { p ->
            AppIcon(
                if (p) R.drawable.ic_pause else R.drawable.ic_play,
                size * 0.56f,
                description = if (p) "Pause" else "Play"
            )
        }
    }
}

/** Slim slider: thin track, round thumb. Replaces the stock Material slider, which looked heavy here. */
@Composable
fun PremiumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val update by rememberUpdatedState(onValueChange)
    Box(
        modifier.fillMaxWidth().height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    update(valueRange.start + span * (offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    update(valueRange.start + span * (change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val thumb = 22.dp
            val travel = maxWidth - thumb
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Pal.SurfaceHi).align(Alignment.CenterStart))
            Box(Modifier.width(thumb / 2 + travel * fraction).height(4.dp).clip(CircleShape).background(Pal.Accent).align(Alignment.CenterStart))
            Box(Modifier.offset(x = travel * fraction).size(thumb).clip(CircleShape).background(Color.White).align(Alignment.CenterStart))
        }
    }
}
