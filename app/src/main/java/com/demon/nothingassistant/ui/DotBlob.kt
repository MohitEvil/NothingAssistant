package com.demon.nothingassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.demon.nothingassistant.core.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private class Pts(val x: FloatArray, val y: FloatArray, val z: FloatArray)

/** Evenly spread points on a sphere (golden-angle spiral). */
private fun sphere(n: Int): Pts {
    val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
    val ga = (PI * (3.0 - sqrt(5.0))).toFloat()
    for (i in 0 until n) {
        val yy = 1f - 2f * (i + 0.5f) / n
        val r = sqrt(1f - yy * yy)
        val th = ga * i
        x[i] = cos(th) * r; y[i] = yy; z[i] = sin(th) * r
    }
    return Pts(x, y, z)
}

/**
 * The Nothing blob: a liquid 3D ball made of dots. It turns in space, its skin ripples with the voice, and it is lit from one side,
 * so the near side is big and bright, the far side small and faint, with a soft glow and a highlight on top. Siri's feel, drawn in Nothing's dots.
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
    val pts = remember { sphere(1800) }
    val ring = remember { FloatArray(48) { (it * 2 * PI / 48).toFloat() } }
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = if (animate) (clock.longValue % 1_000_000L) / 1000f else 0f
        val el = if (animate) ((clock.longValue - since).coerceAtLeast(0L)) / 1000f else 10f
        val amp = if (animate) amplitude.floatValue.coerceIn(0f, 1f) else 0.2f
        val accent = palette.red
        val fg = palette.fg

        // How the ball behaves in each state: size, ripple strength, ripple speed, turning speed, how hot (accent) it runs.
        var radius = 0.34f; var ripple = 0.05f; var speed = 1.0f; var spin = 0.35f; var heat = 0f
        when (state) {
            AssistantState.IDLE -> { radius = 0.27f + 0.01f * sin(t * 1.1f); ripple = 0.035f; speed = 0.7f; spin = 0.18f }
            AssistantState.LISTENING -> { radius = 0.31f + 0.05f * amp; ripple = 0.08f + 0.26f * amp; speed = 1.6f + 1.4f * amp; spin = 0.5f + 0.5f * amp; heat = amp }
            AssistantState.SPEAKING -> { radius = 0.33f + 0.015f * sin(t * 2.4f); ripple = 0.13f; speed = 1.8f; spin = 0.6f; heat = 0.35f }
            AssistantState.RECOGNIZING -> { radius = 0.32f; ripple = 0.10f; speed = 1.8f; spin = 1.0f; heat = 0.25f }
            AssistantState.THINKING -> { radius = 0.31f; ripple = 0.12f; speed = 1.5f; spin = -0.9f; heat = 0.2f }
            AssistantState.EXECUTING -> { radius = 0.32f; ripple = 0.07f; speed = 2.0f; spin = 1.6f; heat = 0.3f }
            AssistantState.CONFIRMING -> { radius = 0.30f; ripple = 0.05f; speed = 1.0f; spin = 0.3f }
            AssistantState.SUCCESS -> { val k = (el / 0.45f).coerceIn(0f, 1f); radius = 0.30f + 0.12f * (1f - (1f - k) * (1f - k)) * (1f - 0.55f * (el / 1.3f).coerceIn(0f, 1f)); ripple = 0.03f; speed = 1.0f; spin = 0.5f; heat = 0.1f }
            AssistantState.FAILURE -> { radius = 0.31f; ripple = 0.10f * (1f - (el / 0.9f).coerceIn(0f, 1f)); speed = 7f; spin = 0.2f; heat = 1f }
        }
        val base = s * radius
        val persp = 2.6f

        // soft glow under the ball
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = (0.10f + 0.30f * heat) * (if (palette.isDark) 1f else 0.7f)), Color.Transparent), center = c, radius = base * 2.3f),
            base * 2.3f, c
        )

        val ay = t * spin * 0.3f
        val ax = 0.3f * sin(t * 0.18f) + 0.2f
        val cy = cos(ay); val sy = sin(ay); val cx = cos(ax); val sx = sin(ax)
        val maxDot = s * 0.0072f
        val lx = -0.45f; val ly = -0.55f; val lz = 0.70f
        val tt = t * speed * 0.28f

        // Two passes: far side first, then near side over it.
        for (pass in 0..1) {
            for (i in pts.x.indices) {
                val px = pts.x[i]; val py = pts.y[i]; val pz = pts.z[i]
                // liquid skin: three crossing waves
                val w = sin(px * 1.7f + tt * 1.3f) * sin(py * 1.5f - tt * 1.1f) + 0.5f * sin(pz * 2.0f + py * 1.2f + tt * 1.5f)
                val rr = 1f + ripple * w * 0.55f
                // rotate around Y, then X
                val x1 = px * cy + pz * sy
                val z1 = -px * sy + pz * cy
                val y2 = py * cx - z1 * sx
                val z2 = py * sx + z1 * cx
                val front = z2 >= 0f
                if ((pass == 1) != front) continue
                val k = persp / (persp - z2 * 0.5f * rr)
                val sxp = x1 * rr * base * k
                val syp = y2 * rr * base * k
                // lighting from the normal (the unrotated direction, rotated)
                val light = (x1 * lx + y2 * ly + z2 * lz).coerceIn(-1f, 1f) * 0.5f + 0.5f
                val depth = 0.5f + 0.5f * z2
                var a = (0.12f + 0.88f * depth * depth) * (0.35f + 0.65f * light)
                var sz = maxDot * (0.40f + 0.60f * depth) * k * (0.9f + 0.2f * light)
                var col: Color = fg
                var hot = (heat * (0.5f + 0.5f * w).coerceIn(0f, 1f))
                // sweep of light across the ball while it thinks or listens to the words
                if (state == AssistantState.THINKING || state == AssistantState.RECOGNIZING) {
                    val band = exp(-(((y2 + 1f) % 2f - ((tt * 0.55f) % 2f)).let { it * it }) * 9f)
                    a = (a + 0.55f * band).coerceAtMost(1f)
                    sz *= 1f + 0.6f * band
                    hot = maxOf(hot, band * 0.8f)
                }
                if (state == AssistantState.FAILURE) hot = 0.9f
                if (hot > 0.01f) col = lerp(fg, accent, hot.coerceIn(0f, 1f))
                // specular highlight: the brightest dots near the light, white
                val spec = ((light - 0.82f) / 0.18f).coerceIn(0f, 1f)
                if (spec > 0f && front) { a = (a + 0.5f * spec).coerceAtMost(1f); sz *= 1f + 0.25f * spec }
                drawCircle(col.copy(alpha = a.coerceIn(0f, 1f)), sz, Offset(c.x + sxp, c.y + syp))
            }
            // the working state: an orbit of dots around the equator, passing in front of and behind the ball
            if (state == AssistantState.EXECUTING) {
                for (a0 in ring) {
                    val ang = a0 + t * 1.1f
                    val ox = cos(ang); val oz = sin(ang)
                    val tilt = 0.35f
                    val oy = oz * sin(tilt); val ozz = oz * cos(tilt)
                    val front = ozz >= 0f
                    if ((pass == 1) != front) continue
                    val rad = base * 1.32f
                    drawCircle(accent.copy(alpha = if (front) 0.95f else 0.35f), maxDot * (if (front) 1.5f else 0.9f), Offset(c.x + ox * rad, c.y + oy * rad))
                }
            }
        }
    }
}
