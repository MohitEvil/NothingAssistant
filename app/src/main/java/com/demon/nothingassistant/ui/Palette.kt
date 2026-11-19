package com.demon.nothingassistant.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class GlyphPalette(
    val bg: Color,
    val fg: Color,
    val dim: Color,
    val muted: Color,
    val red: Color,
    val card: Color,
    val isDark: Boolean
)

/** Pure-black background so AMOLED pixels stay off. */
val DarkPalette = GlyphPalette(
    bg = Color(0xFF000000),
    fg = Color(0xFFFFFFFF),
    dim = Color(0xFF3A3A3A),
    muted = Color(0xFF8C8C8C),
    red = Color(0xFFE5232B),
    card = Color(0xFF141414),
    isDark = true
)

val LightPalette = GlyphPalette(
    bg = Color(0xFFF2F0EC),
    fg = Color(0xFF0B0B0B),
    dim = Color(0xFFCDCAC4),
    muted = Color(0xFF6F6D69),
    red = Color(0xFFD71921),
    card = Color(0xFFFFFFFF),
    isDark = false
)

/** Accent colour choices for the Appearance page. */
val AccentChoices = listOf("red" to Color(0xFFE5232B), "amber" to Color(0xFFFFB000), "mint" to Color(0xFF2DD4A4), "blue" to Color(0xFF4C8DFF), "white" to Color(0xFFEDEDED))

fun GlyphPalette.withAccent(name: String): GlyphPalette {
    val c = AccentChoices.firstOrNull { it.first == name }?.second ?: return this
    return if (name == "red") this else copy(red = c)
}
