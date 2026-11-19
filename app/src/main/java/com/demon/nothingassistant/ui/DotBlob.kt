package com.demon.nothingassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.demon.nothingassistant.core.AssistantState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Nothing "blob": a Siri-like living shape made of dots. Its outline wobbles with the voice,
 * swirls while it thinks, orbits while it works and breathes while it waits. Dots are bigger and whiter toward the middle.
 */
@Composable
fun DotBlob(
    state: AssistantState,
    since: Long,
    clock: LongState,
    amplitude: FloatState,
    animate: Boolean,
    palette: GlyphPalette,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = if (animate) (clock.longValue % 1_000_000L) / 1000f else 0f
        val el = if (animate) ((clock.longValue - since).coerceAtLeast(0L)) / 1000f else 10f
        val amp = if (animate) amplitude.floatValue.coerceIn(0f, 1f) else 0.2f
        val base = s * 0.36f
        val cols = 31
        val pitch = s * 0.9f / cols
        val maxDot = pitch * 0.46f
        val accent = palette.red
        val hot = when (state) {
            AssistantState.LISTENING -> amp
            AssistantState.SPEAKING -> 0.35f
            AssistantState.FAILURE -> 1f
            else -> 0.0f
        }
        for (iy in 0 until cols) for (ix in 0 until cols) {
            val x = (ix - (cols - 1) / 2f) * pitch
            val y = (iy - (cols - 1) / 2f) * pitch
            val rho = sqrt(x * x + y * y)
            val th = atan2(y, x)
            // outline radius for this angle
            var rad = base
            var spin = 0f
            when (state) {
                AssistantState.IDLE -> rad = base * (0.34f + 0.025f * sin(t * 2.2f)) + 0f
                AssistantState.LISTENING -> {
                    val k = 0.10f + 0.5f * amp
                    rad = base * (0.62f + 0.10f * amp) * (1f + k * (0.5f * sin(3 * th + t * 3.1f) + 0.35f * sin(5 * th - t * 2.3f) + 0.25f * sin(2 * th + t * 1.7f)))
                }
                AssistantState.SPEAKING -> {
                    val pulse = 0.5f + 0.5f * sin(t * 6.5f)
                    rad = base * (0.66f + 0.07f * pulse) * (1f + 0.12f * sin(4 * th + t * 2.6f) + 0.07f * sin(2 * th - t * 1.9f))
                }
                AssistantState.RECOGNIZING -> { rad = base * 0.66f * (1f + 0.10f * sin(3 * th + t * 5f)); spin = t * 1.4f }
                AssistantState.THINKING -> { rad = base * 0.62f * (1f + 0.14f * sin(2 * th - t * 3.4f) + 0.06f * sin(5 * th + t * 2f)); spin = -t * 1.1f }
                AssistantState.EXECUTING -> rad = base * 0.70f * (1f + 0.05f * sin(6 * th + t * 4f))
                AssistantState.CONFIRMING -> rad = base * 0.60f * (1f + 0.04f * sin(t * 3f))
                AssistantState.SUCCESS -> rad = base * (0.6f + 0.5f * (1f - (1f - (el / 0.5f).coerceIn(0f, 1f)).let { it * it })).coerceAtMost(base * 1.1f) * (1f - 0.18f * (el / 1.2f).coerceIn(0f, 1f))
                AssistantState.FAILURE -> rad = base * 0.64f * (1f + 0.07f * sin(11 * th + t * 30f) * (1f - (el / 0.8f).coerceIn(0f, 1f)))
            }
            if (rho > rad) continue
            val depth = (1f - rho / rad).coerceIn(0f, 1f)
            var r = maxDot * (0.22f + 0.78f * Math.pow(depth.toDouble(), 0.55).toFloat())
            // a swirl of brightness around the blob while it thinks, orbit highlight while it works
            var glow = depth
            if (state == AssistantState.THINKING || state == AssistantState.RECOGNIZING) {
                val w = 0.5f + 0.5f * cos(th * 2 - spin * 2.2f)
                glow = (depth * 0.55f + w * 0.45f * (0.3f + depth))
                r *= 0.8f + 0.35f * w
            } else if (state == AssistantState.EXECUTING) {
                val w = 0.5f + 0.5f * cos(th - t * 4.2f)
                val ring = 1f - kotlin.math.abs(rho / rad - 0.78f) * 4f
                glow = if (ring > 0f) (ring * w) else depth * 0.35f
                r *= if (ring > 0f) 0.7f + 0.5f * w else 0.7f
            } else if (state == AssistantState.IDLE) {
                glow = 0.35f + 0.4f * depth
            }
            val edge = (1f - depth)
            val col = lerp(palette.fg.copy(alpha = (0.28f + 0.72f * glow).coerceIn(0f, 1f)), accent, (hot * edge * 1.4f).coerceIn(0f, 1f).let { if (state == AssistantState.FAILURE) 0.85f else it })
            drawCircle(col, r, c + Offset(x, y))
        }
        // faint resting grid so the blob sits on a dot field, like the Nothing glyph matrix
        if (state == AssistantState.IDLE || state == AssistantState.CONFIRMING) {
            val g = palette.dim.copy(alpha = 0.25f)
            for (iy in 0 until cols step 2) for (ix in 0 until cols step 2) {
                val x = (ix - (cols - 1) / 2f) * pitch
                val y = (iy - (cols - 1) / 2f) * pitch
                if (sqrt(x * x + y * y) in (base * 0.45f)..(base * 1.15f)) drawCircle(g, pitch * 0.1f, c + Offset(x, y))
            }
        }
    }
}
