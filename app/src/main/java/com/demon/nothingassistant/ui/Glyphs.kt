package com.demon.nothingassistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24 by 24 grid (the Lucide set, ISC licence, the same family AndroidHarness uses). Every icon is a few strokes, so it
 * can be drawn on stroke by stroke when it appears and can breathe while it is the centre of attention. No two things share an icon.
 */
class Ic(vararg val d: String) {
    companion object {
        val MIC = Ic("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")
        val SPARKLES = Ic(
            "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z",
            "M20 3v4", "M22 5h-4", "M4 17v2", "M5 18H3"
        )
        val BRAIN = Ic(
            "M12 18V5", "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4",
            "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5",
            "M17.997 5.125a4 4 0 0 1 2.526 5.77",
            "M18 18a4 4 0 0 0 2-7.464",
            "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517",
            "M6 18a4 4 0 0 1-2-7.464",
            "M6.003 5.125a4 4 0 0 0-2.526 5.77"
        )
        val AUDIO_LINES = Ic("M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3")
        val WAVEFORM = Ic("M2 13a2 2 0 0 0 2-2V7a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0V4a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0v-4a2 2 0 0 1 2-2")
        val VOLUME = Ic("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728")
        val RADIO = Ic("M4.9 19.1C1 15.2 1 8.8 4.9 4.9", "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5", "M19.1 4.9C23 8.8 23 15.1 19.1 19")
        val SLIDERS = Ic("M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4")
        val GLOBE = Ic("M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
        val PHONE = Ic("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01")
        val DOWNLOAD = Ic("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")
        val CLOUD = Ic("M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9z")
        val PALETTE = Ic(
            "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
            "M13.5 6.5h.01", "M17.5 10.5h.01", "M6.5 12.5h.01", "M8.5 7.5h.01"
        )
        val SHIELD = Ic(
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
            "M9 12l2 2 4-4"
        )
        val TERMINAL = Ic("M4 17l6-6-6-6", "M12 19h8")
        val GRID = Ic(
            "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z", "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z"
        )
        val USER = Ic("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M16 7a4 4 0 1 0-8 0a4 4 0 0 0 8 0z")
        val BELL = Ic("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
        val LAYERS = Ic(
            "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z",
            "M22 17.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65", "M22 12.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"
        )
        val BOT = Ic("M12 8V4H8", "M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2")
        val HAND = Ic(
            "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2", "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2", "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8",
            "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"
        )
        val CHECK = Ic("M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M9 12l2 2 4-4")
    }
}

/** A line icon that draws itself on, stroke by stroke, the first time it shows. With [live] it also breathes softly and glows. */
@Composable
fun LineIcon(
    ic: Ic,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    stroke: Float = 1.8f,
    accent: Color = color,
    live: Boolean = false
) {
    val paths = remember(ic) {
        ic.d.flatMap { it.split(Regex("(?=M)")).filter { s -> s.isNotBlank() } }.map { PathParser().parsePathString(it).toPath() }
    }
    val progress = remember(ic) { Animatable(0f) }
    LaunchedEffect(ic) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
    }
    val breathe = if (live) {
        rememberInfiniteTransition(label = "ic").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b").value
    } else 0f
    val measure = remember { PathMeasure() }
    val seg = remember { Path() }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        if (live) {
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.16f + 0.12f * breathe), Color.Transparent), center = center, radius = this.size.minDimension * 0.62f),
                radius = this.size.minDimension * 0.62f, center = center
            )
        }
        val brush = Brush.linearGradient(listOf(color, accent), start = Offset(0f, 0f), end = Offset(24f, 24f))
        val n = paths.size
        val stagger = if (n > 1) 0.5f / n else 0f
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(1f + 0.05f * breathe, pivot = center) {
            scale(k, k, pivot = Offset.Zero) {
                paths.forEachIndexed { i, p ->
                    val local = ((progress.value - i * stagger) / (1f - stagger * (n - 1)).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                    if (local >= 1f) drawPath(p, brush, style = style)
                    else if (local > 0f) {
                        measure.setPath(p, false)
                        seg.reset()
                        measure.getSegment(0f, measure.length * local, seg, true)
                        drawPath(seg, brush, style = style)
                    }
                }
            }
        }
    }
}

/** An icon sitting on a soft rounded tile, for list rows and headers. */
@Composable
fun IconTile(ic: Ic, palette: GlyphPalette, modifier: Modifier = Modifier, tile: Dp = 40.dp, live: Boolean = false) {
    Box(
        modifier.size(tile).background(palette.fg.copy(alpha = 0.07f), RoundedCornerShape(tile * 0.34f)),
        contentAlignment = Alignment.Center
    ) { LineIcon(ic, palette.fg, size = tile * 0.55f, accent = palette.muted, live = live) }
}
