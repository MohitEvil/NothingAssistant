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
import com.demon.nothingassistant.audio.VoiceBands
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
    val pts = remember { sphere(900) }
    val ring = remember { FloatArray(48) { (it * 2 * PI / 48).toFloat() } }
    val smooth = remember { floatArrayOf(0f) }
    val anim = remember { floatArrayOf(0f, 0f) } // accumulated turn angle, accumulated ripple phase
    val lastMs = remember { longArrayOf(0L) }
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = if (animate) (clock.longValue % 1_000_000L) / 1000f else 0f
        val el = if (animate) ((clock.longValue - since).coerceAtLeast(0L)) / 1000f else 10f
        // Quick to swell with your voice, slow to settle: it breathes instead of flickering.
        val rawAmp = if (animate) amplitude.floatValue.coerceIn(0f, 1f) else 0.2f
        smooth[0] += (rawAmp - smooth[0]) * (if (rawAmp > smooth[0]) 0.5f else 0.10f)
        val voiceState = state == AssistantState.LISTENING || state == AssistantState.SPEAKING
        // What the ball follows: your loudness while you speak, the real sound of the answer while it speaks.
        val amp = if (!animate) 0.2f else when (state) {
            AssistantState.LISTENING -> maxOf(smooth[0], if (VoiceBands.real()) VoiceBands.level() else 0f)
            AssistantState.SPEAKING -> if (VoiceBands.real()) VoiceBands.level() else (0.42f + 0.30f * sin(t * 7.3f) * sin(t * 2.3f + 1f)).coerceIn(0f, 1f)
            else -> smooth[0]
        }
        if (animate) VoiceBands.tick(if (voiceState) amp else 0f, t) else if (!voiceState) VoiceBands.tick(0f, t)
        val bands = VoiceBands.levels
        val nowMs = clock.longValue
        val dt = if (!animate || lastMs[0] == 0L) 0f else ((nowMs - lastMs[0]).coerceIn(0L, 100L)) / 1000f
        lastMs[0] = nowMs
        val accent = palette.red
        val fg = palette.fg

        // How the ball behaves in each state: size, ripple strength, ripple speed, turning speed, how hot (accent) it runs.
        var radius = 0.34f; var ripple = 0.05f; var speed = 1.0f; var spin = 0.35f; var heat = 0f
        when (state) {
            AssistantState.IDLE -> { radius = 0.27f + 0.008f * sin(t * 1.4f); ripple = 0.03f; speed = 0.35f; spin = 0.05f }
            AssistantState.LISTENING -> { radius = 0.29f + 0.07f * amp; ripple = 0.03f + 0.10f * amp; speed = 0.25f + 3.2f * amp; spin = 0.04f + 1.0f * amp; heat = amp }
            AssistantState.SPEAKING -> { radius = 0.29f + 0.07f * amp; ripple = 0.03f + 0.10f * amp; speed = 0.25f + 3.0f * amp; spin = 0.04f + 0.9f * amp; heat = 0.2f + 0.5f * amp }
            AssistantState.RECOGNIZING -> { radius = 0.32f; ripple = 0.13f; speed = 2.4f; spin = 1.5f; heat = 0.25f }
            AssistantState.THINKING -> { radius = 0.31f; ripple = 0.16f; speed = 1.8f; spin = -1.2f; heat = 0.2f }
            AssistantState.EXECUTING -> { radius = 0.32f; ripple = 0.08f; speed = 3.0f; spin = 2.2f; heat = 0.3f }
            AssistantState.CONFIRMING -> { radius = 0.30f; ripple = 0.05f; speed = 1.0f; spin = 0.3f }
            AssistantState.SUCCESS -> { val k = (el / 0.45f).coerceIn(0f, 1f); radius = 0.30f + 0.12f * (1f - (1f - k) * (1f - k)) * (1f - 0.55f * (el / 1.3f).coerceIn(0f, 1f)); ripple = 0.03f; speed = 1.0f; spin = 0.5f; heat = 0.1f }
            AssistantState.FAILURE -> { radius = 0.31f; ripple = 0.10f * (1f - (el / 0.9f).coerceIn(0f, 1f)); speed = 14f; spin = 0.2f; heat = 1f }
        }
        anim[0] += dt * spin
        anim[1] += dt * speed
        val base = s * radius
        val persp = 2.6f

        // soft glow under the ball
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = (0.10f + 0.30f * heat) * (if (palette.isDark) 1f else 0.7f)), Color.Transparent), center = c, radius = base * 2.3f),
            base * 2.3f, c
        )

        // Siri-style aurora: soft coloured lights drifting around the ball, brighter the more it is doing something.
        val activity = when (state) {
            AssistantState.IDLE -> 0.12f
            AssistantState.LISTENING -> 0.35f + 0.65f * amp
            AssistantState.SPEAKING -> 0.7f
            AssistantState.RECOGNIZING, AssistantState.THINKING, AssistantState.EXECUTING -> 0.6f
            else -> 0.25f
        }
        val auroras = listOf(accent, Color(0xFF4FC3F7), Color(0xFFB388FF))
        for (k in 0 until 3) {
            val ang = anim[1] * (0.30f + 0.12f * k) + k * 2.1f
            val off = base * (0.45f + 0.25f * amp)
            val pc = Offset(c.x + cos(ang) * off, c.y + sin(ang * 1.2f) * off * 0.8f)
            val rad = base * (1.15f + 0.25f * k) * (1f + 0.25f * amp)
            drawCircle(
                Brush.radialGradient(listOf(auroras[k].copy(alpha = 0.22f * activity * (if (palette.isDark) 1f else 0.7f)), Color.Transparent), center = pc, radius = rad),
                rad, pc
            )
        }

        val ay = anim[0]
        val ax = 0.45f * sin(t * 0.5f) + 0.25f
        val cy = cos(ay); val sy = sin(ay); val cx = cos(ax); val sx = sin(ax)
        val maxDot = s * 0.0155f
        val lx = -0.45f; val ly = -0.55f; val lz = 0.70f
        val tt = anim[1]

        // Two passes: far side first, then near side over it.
        for (pass in 0..1) {
            for (i in pts.x.indices) {
                val px = pts.x[i]; val py = pts.y[i]; val pz = pts.z[i]
                // liquid skin: three crossing waves
                val w = sin(px * 3.1f + tt * 1.3f) * sin(py * 2.7f - tt * 1.1f) + 0.6f * sin(pz * 3.9f + py * 2.1f + tt * 1.7f) + 0.4f * sin(px * 5.3f - pz * 4.1f - tt * 2.3f)
                var rr = 1f + ripple * w * 0.55f
                if (voiceState) {
                    // pitch map: deep sounds swell the lower half of the ball, high sounds the top
                    val fi = (1f - (py * 0.5f + 0.5f)) * (VoiceBands.N - 1)
                    val i0 = fi.toInt().coerceIn(0, VoiceBands.N - 2)
                    val fr = (fi - i0).coerceIn(0f, 1f)
                    rr += 0.34f * (bands[i0] * (1f - fr) + bands[i0 + 1] * fr) * (0.7f + 0.3f * w)
                }
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
                var a = (0.10f + 0.90f * depth * depth) * (0.30f + 0.70f * light)
                var sz = maxDot * (0.28f + 0.72f * depth) * k * (0.85f + 0.3f * light)
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
                    val ang = a0 + t * 2.4f
                    val ox = cos(ang); val oz = sin(ang)
                    val tilt = 0.35f
                    val oy = oz * sin(tilt); val ozz = oz * cos(tilt)
                    val front = ozz >= 0f
                    if ((pass == 1) != front) continue
                    val rad = base * 1.32f
                    drawCircle(accent.copy(alpha = if (front) 0.95f else 0.35f), maxDot * (if (front) 0.8f else 0.5f), Offset(c.x + ox * rad, c.y + oy * rad))
                }
            }
        }
    }
}
