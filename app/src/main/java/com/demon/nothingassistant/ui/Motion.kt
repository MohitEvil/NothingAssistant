package com.demon.nothingassistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Words appear one by one, like it is typing as it speaks. The layout is reserved up front, so nothing jumps. */
@Composable
fun TypeText(text: String, color: Color, fontSize: TextUnit = 16.sp, textAlign: TextAlign = TextAlign.Center, modifier: Modifier = Modifier, fontFamily: androidx.compose.ui.text.font.FontFamily? = null, lineHeight: TextUnit = TextUnit.Unspecified) {
    val on = LocalUiStyle.current.typing
    val words = remember(text) { text.split(" ") }
    var n by remember(text) { mutableIntStateOf(if (on) 0 else words.size) }
    LaunchedEffect(text, on) {
        if (!on) { n = words.size; return@LaunchedEffect }
        // about 4 words a second, never more than 9 seconds in total
        val per = (250L).coerceAtMost(9000L / words.size.coerceAtLeast(1))
        for (i in 1..words.size) { n = i; delay(per) }
    }
    val shown = words.take(n).joinToString(" ")
    val rest = if (n < words.size) " " + words.drop(n).joinToString(" ") else ""
    Text(
        buildAnnotatedString {
            append(shown)
            withStyle(SpanStyle(color = Color.Transparent)) { append(rest) }
        },
        color = color, fontSize = fontSize, textAlign = textAlign, modifier = modifier, fontFamily = fontFamily, lineHeight = lineHeight
    )
}

/** Three dots that pulse in turn: something is happening. */
@Composable
fun DotLoader(color: Color, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "dots")
    val t by tr.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(modifier.size(width = 26.dp, height = 8.dp)) {
        for (i in 0 until 3) {
            val d = ((t - i + 3f) % 3f)
            val a = if (d < 1f) 1f - d * 0.65f else 0.35f
            val r = size.height * (if (d < 1f) 0.5f - d * 0.12f else 0.36f)
            drawCircle(color.copy(alpha = a), r, Offset(size.height / 2f + i * size.width / 3f, size.height / 2f))
        }
    }
}

/** A status line such as "Searching the web" with live dots after it. */
@Composable
fun StatusLine(text: String, color: Color, loading: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text.trimEnd('…', '.', ' '), color = color, fontFamily = NFont.label, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (loading) {
            Spacer(Modifier.width(8.dp))
            DotLoader(color)
        }
    }
}
