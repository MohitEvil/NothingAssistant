package com.demon.nothingassistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** How the app looks: plain Nothing, liquid glass, or minimal glass; blob and typing animations on or off. */
data class UiStyle(val glass: Int = 1, val blob: Boolean = true, val typing: Boolean = true) {
    val isGlass get() = glass > 0
    val minimal get() = glass == 2
}

val LocalUiStyle = compositionLocalOf { UiStyle() }

/** Press = a quick squish that springs back (squishy, liquid feel). */
fun Modifier.squish(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium), label = "squish")
    this.graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/** Screen background: pure bg, and in glass looks a soft accent glow and a faint dot grid so the glass has something to refract. */
fun Modifier.glassBackdrop(p: GlyphPalette): Modifier = composed {
    val st = LocalUiStyle.current
    val liquid = st.glass == 1
    val drift = if (liquid) rememberInfiniteTransition(label = "liquid").animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "drift") else null
    this.drawBehind {
        drawRect(p.bg)
        if (liquid && drift != null) {
            // Liquid glass: slow, soft light blobs drifting behind the glass so it always seems to be moving and refracting.
            val a = drift.value * 6.2831855f
            val w = size.width; val h = size.height
            val dark = p.isDark
            fun blob(c: Color, cx: Float, cy: Float, r: Float, al: Float) =
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = al), Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
            blob(p.red, w * (0.78f + 0.12f * kotlin.math.sin(a)), h * (0.10f + 0.06f * kotlin.math.cos(a)), w * 0.85f, if (dark) 0.26f else 0.16f)
            blob(Color(0xFF7AA8FF), w * (0.12f + 0.10f * kotlin.math.cos(a + 1.6f)), h * (0.52f + 0.08f * kotlin.math.sin(a + 1.6f)), w * 0.75f, if (dark) 0.13f else 0.12f)
            blob(Color.White, w * (0.55f + 0.18f * kotlin.math.sin(a * 2f + 0.8f)), h * (0.88f + 0.05f * kotlin.math.cos(a)), w * 0.7f, if (dark) 0.07f else 0.30f)
        }
        if (st.isGlass && p.isDark) {
            if (!liquid) drawRect(Brush.radialGradient(listOf(p.red.copy(alpha = if (st.minimal) 0.10f else 0.20f), Color.Transparent), center = Offset(size.width * 0.92f, size.height * 0.04f), radius = size.width * 0.9f))
            if (!st.minimal) {
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.55f), radius = size.width * 0.8f))
                val pitch = 22.dp.toPx()
                var y = pitch / 2
                while (y < size.height) {
                    var x = pitch / 2
                    while (x < size.width) { drawCircle(Color.White.copy(alpha = 0.045f), 1.1.dp.toPx(), Offset(x, y)); x += pitch }
                    y += pitch
                }
            }
        }
    }
}

/** Fill and edge of a glass card: translucent gradient, bright rim at the top-left fading out. */
fun Modifier.glassFill(p: GlyphPalette, shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    val st = LocalUiStyle.current
    if (!st.isGlass) this
    else {
        val top = if (p.isDark) Color.White.copy(alpha = if (st.minimal) 0.07f else 0.11f) else Color.White.copy(alpha = 0.75f)
        val bot = if (p.isDark) Color.White.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.45f)
        val rim = if (p.isDark) Color.White.copy(alpha = if (st.glass == 1) 0.48f else 0.30f) else Color.White.copy(alpha = 0.9f)
        val liquid = st.glass == 1
        val base = this.background(Brush.verticalGradient(listOf(top, bot)), shape)
        val sheened = if (!liquid) base else base
            .background(Brush.linearGradient(
                0f to Color.White.copy(alpha = if (p.isDark) 0.16f else 0.55f),
                0.22f to Color.White.copy(alpha = if (p.isDark) 0.04f else 0.15f),
                0.5f to Color.Transparent,
                0.86f to p.red.copy(alpha = if (p.isDark) 0.07f else 0.05f),
                1f to Color.White.copy(alpha = if (p.isDark) 0.06f else 0.25f)), shape)
        sheened
            .border(androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(rim, Color.White.copy(alpha = 0.03f), p.red.copy(alpha = if (p.isDark) 0.16f else 0.10f)))), shape)
    }
}
