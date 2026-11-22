package com.demon.nothingassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row in the voice picker. */
class PickItem(val id: String, val title: String, val sub: String = "", val tag: String = "")

/**
 * The voice list used everywhere: title with a refresh button, a search field, a count, one radio row per voice
 * (name, gender, optional tag, a speaker button to hear it) and a Done button.
 */
@Composable
fun VoicePicker(
    title: String,
    items: List<PickItem>,
    selected: String,
    palette: GlyphPalette,
    loading: Boolean,
    status: String,
    onRefresh: () -> Unit,
    onSelect: (PickItem) -> Unit,
    onPreview: (PickItem) -> Unit,
    onDone: () -> Unit
) {
    val mono = FontFamily.Monospace
    var q by remember { mutableStateOf("") }
    val shown = remember(items, q) {
        val t = q.trim()
        if (t.isEmpty()) items else items.filter { it.title.contains(t, true) || it.sub.contains(t, true) || it.tag.contains(t, true) || it.id.contains(t, true) }
    }
    Column(Modifier.fillMaxSize().glassBackdrop(palette).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = palette.fg, fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).border(BorderStroke(1.dp, palette.muted.copy(alpha = 0.6f)), CircleShape).clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center
            ) { Text(if (loading) "…" else "↻", color = palette.fg, fontSize = 20.sp) }
        }
        Box(
            Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            if (q.isEmpty()) Text("Search voices", color = palette.muted, fontSize = 15.sp)
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = TextStyle(color = palette.fg, fontSize = 15.sp),
                cursorBrush = SolidColor(palette.red), modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (status.isNotBlank()) status else "${shown.size} voices",
            color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.id }) { v ->
                val on = v.id == selected
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(v) }.padding(vertical = 12.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(22.dp).border(BorderStroke(2.dp, if (on) palette.red else palette.muted), CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(10.dp).background(palette.red, CircleShape))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, color = palette.fg, fontSize = 16.sp)
                        if (v.sub.isNotBlank() || v.tag.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (v.sub.isNotBlank()) Text(v.sub, color = palette.muted, fontSize = 13.sp)
                                if (v.tag.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        v.tag, color = palette.fg, fontSize = 11.sp,
                                        modifier = Modifier.background(palette.dim.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        Modifier.size(40.dp).border(BorderStroke(1.dp, palette.muted.copy(alpha = 0.6f)), CircleShape).clickable { onPreview(v) },
                        contentAlignment = Alignment.Center
                    ) { Text("♪", color = palette.fg, fontSize = 18.sp) }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        PillButton("DONE", filled = true, palette = palette, onClick = onDone, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
    }
}

/** A rounded search box in the same style as the voice picker. */
@Composable
fun SearchField(value: String, hint: String, palette: GlyphPalette, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, color = palette.muted, fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = palette.fg, fontSize = 15.sp),
            cursorBrush = SolidColor(palette.red), modifier = Modifier.fillMaxWidth()
        )
    }
}
