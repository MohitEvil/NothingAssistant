package com.demon.nothingassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.core.LastAnswer

/** A small bordered button used for suggestions and answer actions. */
@Composable
fun MiniChip(text: String, palette: GlyphPalette, onClick: () -> Unit) {
    Box(
        Modifier
            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = palette.fg, fontFamily = NFont.label, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

/** What the assistant just said, with copy / share / repeat and ready-made follow-ups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerCard(
    answer: LastAnswer,
    palette: GlyphPalette,
    onFollowUp: (String) -> Unit,
    onRepeat: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.card, RoundedCornerShape(24.dp))
            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ANSWER", color = palette.muted, fontFamily = NFont.label, fontSize = 10.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            Text(
                "CLOSE", color = palette.muted, fontFamily = NFont.label, fontSize = 10.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 4.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
            Text(answer.answer, color = palette.fg, fontFamily = NFont.headline, fontSize = 16.sp, lineHeight = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniChip("COPY", palette) { copyText(ctx, answer.answer) }
            MiniChip("SHARE", palette) { shareText(ctx, answer.answer) }
            MiniChip("SAY AGAIN", palette, onRepeat)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            followUps(answer).forEach { (label, ask) -> MiniChip(label, palette) { onFollowUp(ask) } }
        }
    }
}

private fun followUps(a: LastAnswer): List<Pair<String, String>> = buildList {
    add("TELL ME MORE" to "Tell me more about that")
    add("SIMPLER" to "Explain that in simpler words")
    if (a.answer.length > 160) add("SHORTER" to "Say that again in one short sentence")
    add("IN HINDI" to "Say that again in Hindi")
}

private fun copyText(ctx: Context, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nothing Assistant", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}

private fun shareText(ctx: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, "Share answer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private val SUGGESTIONS = listOf(
    "turn on the flashlight", "set a timer for 5 minutes", "what's the weather today", "text Mom saying on my way",
    "open the camera", "what can you do", "how much battery is left", "tell me a joke",
    "play some music", "summarize this screen", "set an alarm for 7 am", "turn on do not disturb",
    "explain what's on my screen", "help me reply to this message"
)

/** Four tappable examples that change through the day, so the idle screen teaches what the assistant can do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionChips(palette: GlyphPalette, onPick: (String) -> Unit) {
    val picks = remember {
        val start = ((System.currentTimeMillis() / 3_600_000L) % SUGGESTIONS.size).toInt()
        val mine = runCatching { com.demon.nothingassistant.ai.Mem.suggestions() }.getOrDefault(emptyList()).shuffled().take(2)
        mine + List(4 - mine.size) { SUGGESTIONS[(start + it * 3) % SUGGESTIONS.size] }
    }
    Text("TRY", color = palette.muted, fontFamily = NFont.label, fontSize = 10.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        picks.forEach { MiniChip("“$it”", palette) { onPick(it) } }
    }
}

/** A one-line box to type a request instead of speaking it. */
@Composable
fun TypeBox(palette: GlyphPalette, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    fun send() { val t = text.trim(); if (t.isNotEmpty()) { text = ""; onSend(t) } }
    Row(
        modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(50))
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (text.isEmpty()) Text("or type a request", color = palette.muted, fontSize = 14.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.fg, fontSize = 14.sp, fontFamily = NFont.body),
                cursorBrush = SolidColor(palette.fg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            "SEND", color = if (text.isBlank()) palette.dim else palette.fg, fontFamily = NFont.label, fontSize = 11.sp, letterSpacing = 2.sp,
            modifier = Modifier.clickable(enabled = text.isNotBlank()) { send() }.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

/** "Good evening, Demon" from the hour of day. */
fun greeting(name: String): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val g = when { h < 5 -> "Still up"; h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; h < 22 -> "Good evening"; else -> "Good night" }
    return if (name.isBlank()) g else "$g, $name"
}
