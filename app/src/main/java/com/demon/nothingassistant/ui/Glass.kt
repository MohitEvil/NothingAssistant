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
import androidx.compose.ui.draw.shadow
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
            blob(p.red, w * (0.80f + 0.10f * kotlin.math.sin(a)), h * (0.12f + 0.06f * kotlin.math.cos(a)), w * 0.9f, if (dark) 0.34f else 0.20f)
            blob(Color(0xFF3B82F6), w * (0.10f + 0.10f * kotlin.math.cos(a + 1.6f)), h * (0.40f + 0.08f * kotlin.math.sin(a + 1.6f)), w * 0.85f, if (dark) 0.30f else 0.18f)
            blob(Color(0xFF8B5CF6), w * (0.70f + 0.12f * kotlin.math.sin(a + 3.1f)), h * (0.66f + 0.07f * kotlin.math.cos(a + 3.1f)), w * 0.8f, if (dark) 0.26f else 0.16f)
            blob(Color(0xFF22D3EE), w * (0.30f + 0.14f * kotlin.math.sin(a * 2f + 0.8f)), h * (0.90f + 0.04f * kotlin.math.cos(a)), w * 0.7f, if (dark) 0.18f else 0.14f)
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
        if (liquid) {
            // iOS-style liquid glass: a clear, slightly milky body, a bright edge that catches light on two opposite corners,
            // a soft inner glow just inside the edge, and a faint shadow so the pane floats.
            val d = p.isDark
            val body = Brush.linearGradient(listOf(Color.White.copy(alpha = if (d) 0.20f else 0.55f), Color.White.copy(alpha = if (d) 0.07f else 0.28f), Color.White.copy(alpha = if (d) 0.13f else 0.40f)))
            val edge = Brush.linearGradient(
                0f to Color.White.copy(alpha = if (d) 0.85f else 1f),
                0.28f to Color.White.copy(alpha = 0.10f),
                0.62f to Color.White.copy(alpha = 0.06f),
                1f to Color.White.copy(alpha = if (d) 0.60f else 0.95f)
            )
            val inner = Brush.linearGradient(listOf(Color.White.copy(alpha = if (d) 0.22f else 0.5f), Color.Transparent, Color.White.copy(alpha = if (d) 0.12f else 0.3f)))
            return@composed this
                .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                .background(body, shape)
                .border(androidx.compose.foundation.BorderStroke(3.dp, inner), shape)
                .border(androidx.compose.foundation.BorderStroke(1.2.dp, edge), shape)
        }
        val base = this.background(Brush.verticalGradient(listOf(top, bot)), shape)
        val sheened = base
        sheened
            .border(androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(rim, Color.White.copy(alpha = 0.03f), p.red.copy(alpha = if (p.isDark) 0.16f else 0.10f)))), shape)
    }
}
