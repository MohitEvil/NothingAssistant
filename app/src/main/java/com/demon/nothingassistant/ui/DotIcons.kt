package com.demon.nothingassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Nothing-style dot-matrix icons: 9x9 grids of dots, lit dots filled, the rest faint. */
object DotIcons {
    val VOICE = listOf(
        "....#....", "...###...", "...###...", "...###...", ".#.###.#.", ".#.###.#.", "..#####..", "....#....", "...###..."
    )
    val AI = listOf(
        "....#....", "....#....", "..#.#.#..", "...###...", "#########", "...###...", "..#.#.#..", "....#....", "....#...."
    )
    val APPEARANCE = listOf(
        "..#####..", ".##...##.", "##..####.", "#...#####", "#...#####", "#...#####", "##..####.", ".##...##.", "..#####.."
    )
    val ACCESS = listOf(
        "...###...", "..#...#..", "..#...#..", "..#...#..", ".#######.", ".###.###.", ".###.###.", ".###.###.", ".#######."
    )
    val LOG = listOf(
        "#########", "#.......#", "#.#####.#", "#.......#", "#.#####.#", "#.......#", "#.###...#", "#.......#", "#########"
    )
    val GENERAL = listOf(
        "...#.#...", ".#.###.#.", "..#####..", "#.#...#.#", "###...###", "#.#...#.#", "..#####..", ".#.###.#.", "...#.#..."
    )
    val APPS = listOf(
        ".##...##.", ".##...##.", ".........", ".##...##.", ".##...##.", ".........", "...###...", "...###...", "...###..."
    )
}

@Composable
fun DotIcon(pattern: List<String>, color: Color, modifier: Modifier = Modifier, size: Dp = 26.dp, off: Color = color.copy(alpha = 0.16f)) {
    Canvas(modifier.size(size)) {
        val n = pattern.size
        val pitch = this.size.minDimension / n
        val r = pitch * 0.36f
        for (y in 0 until n) for (x in 0 until pattern[y].length) {
            drawCircle(if (pattern[y][x] == '#') color else off, r, Offset(pitch * (x + 0.5f), pitch * (y + 0.5f)))
        }
    }
}
