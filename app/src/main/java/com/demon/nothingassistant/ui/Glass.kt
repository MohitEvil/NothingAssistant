package com.demon.nothingassistant.ui

import androidx.compose.animation.core.Spring
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
    this.drawBehind {
        drawRect(p.bg)
        if (st.isGlass && p.isDark) {
            drawRect(Brush.radialGradient(listOf(p.red.copy(alpha = if (st.minimal) 0.10f else 0.20f), Color.Transparent), center = Offset(size.width * 0.92f, size.height * 0.04f), radius = size.width * 0.9f))
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
        val rim = if (p.isDark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.9f)
        this.background(Brush.verticalGradient(listOf(top, bot)), shape)
            .border(androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(rim, Color.White.copy(alpha = 0.03f), p.red.copy(alpha = if (p.isDark) 0.16f else 0.10f)))), shape)
    }
}
