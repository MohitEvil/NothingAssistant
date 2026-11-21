package com.demon.nothingassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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

/** The live blur state every frosted pane reads. Null where nothing can be blurred (plain looks, and the panel that floats over other apps). */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

private fun DrawScope.paintBackdrop(p: GlyphPalette, st: UiStyle, driftValue: Float) {
    val liquid = st.glass == 1
    drawRect(p.bg)
    if (liquid) {
        // Soft colour lights drifting behind the glass, so there is always something moving to blur and refract.
        val a = driftValue * 6.2831855f
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
                while (x < size.width) { drawCircle(Color.White.copy(alpha = if (liquid) 0.07f else 0.045f), 1.1.dp.toPx(), Offset(x, y)); x += pitch }
                y += pitch
            }
        }
    }
}

/**
 * The root of an app screen. In Liquid Glass it puts the drifting-light background in its own layer BEHIND the screen
 * (a sibling, as Haze requires) and shares the blur state, so every glass pane on the screen blurs it live.
 */
@Composable
fun GlassRoot(palette: GlyphPalette, content: @Composable () -> Unit) {
    val st = LocalUiStyle.current
    if (st.glass != 1) { content(); return }
    val hazeState = remember { HazeState() }
    val drift = rememberInfiniteTransition(label = "liquid").animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().hazeSource(hazeState).drawBehind { paintBackdrop(palette, st, drift.value) })
            content()
        }
    }
}

/** Screen background. Nothing / Minimal paint it here; Liquid Glass gets it from GlassRoot, and paints it itself only where there is no GlassRoot. */
fun Modifier.glassBackdrop(p: GlyphPalette): Modifier = composed {
    val st = LocalUiStyle.current
    val hz = LocalHazeState.current
    if (st.glass == 1 && hz != null) this
    else {
        val drift = if (st.glass == 1) rememberInfiniteTransition(label = "liquid2").animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "drift2") else null
        this.drawBehind { paintBackdrop(p, st, drift?.value ?: 0f) }
    }
}

/** A glass pane. Nothing: nothing added. Minimal: a faint tint and rim. Liquid Glass: live blur, smoked tint, bright edge, soft shadow. */
fun Modifier.glassFill(p: GlyphPalette, shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    val st = LocalUiStyle.current
    if (!st.isGlass) this
    else if (st.glass == 1) {
        val hz = LocalHazeState.current
        val d = p.isDark
        val tint = if (d) Color(0xFF1C1C1E).copy(alpha = 0.40f) else Color.White.copy(alpha = 0.30f)
        val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (d) 0.42f else 0.85f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = if (d) 0.26f else 0.55f)))
        val base = this
            .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.30f))
            .clip(shape)
        val blurred = if (hz != null) base.hazeEffect(state = hz, style = HazeStyle(backgroundColor = Color.Transparent, tint = HazeTint(tint), blurRadius = 24.dp, noiseFactor = 0.04f)) else base.background(tint, shape)
        blurred
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (d) 0.10f else 0.35f), Color.Transparent)), shape)
            .border(androidx.compose.foundation.BorderStroke(1.dp, edge), shape)
    } else {
        val top = if (p.isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.75f)
        val bot = if (p.isDark) Color.White.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.45f)
        val rim = if (p.isDark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.9f)
        this.background(Brush.verticalGradient(listOf(top, bot)), shape)
            .border(androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(rim, Color.White.copy(alpha = 0.03f), p.red.copy(alpha = if (p.isDark) 0.16f else 0.10f)))), shape)
    }
}
