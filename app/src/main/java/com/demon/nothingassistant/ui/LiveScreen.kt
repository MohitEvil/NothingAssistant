package com.demon.nothingassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.core.AssistantController
import com.demon.nothingassistant.core.AssistantState
import com.demon.nothingassistant.core.UiState
import com.demon.nothingassistant.data.Settings

/**
 * Live mode: a hands-free spoken conversation. It listens, answers out loud, and goes straight back to listening.
 * Anything the assistant can do on the phone still works ("open Telegram", "set a timer"), and the AI answers the rest.
 */
@Composable
fun LiveScreen(
    controller: AssistantController,
    ui: UiState,
    settings: Settings,
    palette: GlyphPalette,
    onClose: () -> Unit
) {
    val mono = NFont.label
    val log = controller.liveLog
    val paused = controller.livePaused
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val engine = settings.providers.firstOrNull { it.ready }?.takeIf { settings.aiEnabled }

    LaunchedEffect(log.size, ui.transcript) {
        if (log.isNotEmpty() || ui.transcript.isNotBlank()) runCatching { list.animateScrollBy(100_000f) }
    }

    val status = when {
        paused -> "Paused. Tap to keep talking"
        else -> when (ui.state) {
            AssistantState.LISTENING -> "Listening…"
            AssistantState.RECOGNIZING -> "Hearing you…"
            AssistantState.THINKING -> ui.status.ifBlank { "Thinking…" }
            AssistantState.SPEAKING -> "Speaking. Tap to interrupt"
            AssistantState.EXECUTING -> ui.status.ifBlank { "Working on it…" }
            AssistantState.CONFIRMING -> "Waiting for your answer"
            AssistantState.SUCCESS -> ui.status.ifBlank { "Done" }
            AssistantState.FAILURE -> ui.status.ifBlank { "Something went wrong" }
            AssistantState.IDLE -> "Starting…"
        }
    }

    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        text = ""; typing = false
        controller.liveSend(t)
    }

    Column(
        Modifier.fillMaxSize().glassBackdrop(palette).systemBarsPadding().imePadding().padding(horizontal = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", color = palette.fg, fontSize = 22.sp, modifier = Modifier.squish(onClick = onClose).padding(end = 14.dp, top = 6.dp, bottom = 6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE MODE", color = palette.fg, fontFamily = mono, fontSize = 13.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "EXPERIMENTAL", color = palette.muted, fontFamily = mono, fontSize = 8.sp, letterSpacing = 1.sp,
                        modifier = Modifier.border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(engine?.let { it.model } ?: "no AI · phone commands and small talk", color = palette.muted, fontFamily = mono, fontSize = 10.sp, maxLines = 1)
            }
            Text(
                "CAN START TASKS", color = palette.muted, fontFamily = mono, fontSize = 8.sp, letterSpacing = 1.sp,
                modifier = Modifier.background(palette.card, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (log.isEmpty()) item {
                Text(
                    "Just talk. I answer out loud and keep listening. Say things like \"open Telegram\", \"set a timer for 5 minutes\" or ask me anything. " +
                        "Say \"stop\" to pause.",
                    color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp)
                )
            }
            items(log.size) { i ->
                val line = log[i]
                if (line.user) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            line.text, color = palette.fg, fontSize = 16.sp,
                            modifier = Modifier.background(palette.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    Text(line.text, color = palette.fg, fontFamily = NFont.headline, fontSize = 18.sp, lineHeight = 27.sp)
                }
            }
            if (ui.transcript.isNotBlank() && (ui.state == AssistantState.RECOGNIZING || ui.state == AssistantState.LISTENING) && log.lastOrNull()?.text != ui.transcript) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(ui.transcript, color = palette.muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }

        Text(status, color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))

        if (typing) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = TextStyle(color = palette.fg, fontSize = 15.sp, fontFamily = NFont.body),
                    cursorBrush = SolidColor(palette.red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        Box { if (text.isEmpty()) Text("Type a message…", color = palette.muted, fontSize = 15.sp); inner() }
                    },
                    modifier = Modifier.weight(1f).background(palette.card, RoundedCornerShape(50)).border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 13.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SideButton(if (paused) "▶" else "❚❚", palette) {
                if (paused) controller.liveResume() else controller.livePauseNow()
            }
            Box(
                Modifier.size(84.dp).background(palette.fg.copy(alpha = if (paused) 0.35f else 0.85f), CircleShape).squish {
                    when {
                        paused -> controller.liveResume()
                        else -> controller.onGlyphTap() // stop listening and send / interrupt the answer
                    }
                },
                contentAlignment = Alignment.Center
            ) { Bars(controller, palette, ui.state == AssistantState.LISTENING || ui.state == AssistantState.RECOGNIZING) }
            SideButton(if (typing && text.isNotBlank()) "➤" else if (typing) "⌨" else "⌨", palette) {
                if (typing && text.isNotBlank()) send() else typing = !typing
            }
        }
    }
}

@Composable
private fun SideButton(glyph: String, palette: GlyphPalette, onClick: () -> Unit) {
    Box(Modifier.size(58.dp).background(palette.card, CircleShape).border(BorderStroke(1.dp, palette.dim), CircleShape).squish(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = palette.fg, fontSize = 18.sp)
    }
}

@Composable
private fun Bars(controller: AssistantController, palette: GlyphPalette, listening: Boolean) {
    val level = controller.amplitude.floatValue
    Canvas(Modifier.size(40.dp)) {
        val heights = listOf(0.45f, 0.8f, 1f, 0.7f, 0.4f)
        val w = size.width / 9f
        heights.forEachIndexed { i, h ->
            val k = if (listening) (0.35f + level * 0.65f) else 0.55f
            val bh = size.height * h * k
            drawRoundRect(
                palette.bg,
                topLeft = Offset(i * 2f * w, (size.height - bh) / 2f),
                size = Size(w, bh),
                cornerRadius = CornerRadius(w / 2f)
            )
        }
    }
}
