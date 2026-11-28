package com.demon.nothingassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.ai.Mem
import com.demon.nothingassistant.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the assistant knows, as readable files: Profile, Topics, Areas, and System (how things are done on this phone). */
@Composable
fun MemoryScreen(settings: Settings, palette: GlyphPalette, open: String?, onOpen: (String?) -> Unit, onClose: () -> Unit) {
    val mono = NFont.label
    var tick by remember { mutableStateOf(0) }
    val files = remember(tick, Mem.version) { Mem.list() }
    val current = open?.let { p -> files.firstOrNull { it.path == p } }
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().glassBackdrop(palette).systemBarsPadding().imePadding().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOTHING ASSISTANT", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("BACK", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable { if (current != null) onOpen(null) else onClose() }.padding(vertical = 8.dp, horizontal = 4.dp))
        }
        Spacer(Modifier.height(20.dp))

        if (current != null) {
            DotText(if (current.path == "profile") "PROFILE" else Mem.title(current.path).uppercase().take(14), palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
            Spacer(Modifier.height(10.dp))
            Text(current.desc.ifBlank { Mem.group(current.path) }, color = palette.muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("${current.count} notes · updated ${fmt.format(Date(current.updated))}", color = palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(18.dp)).border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                current.lines.forEach { Text(it.removePrefix("- "), color = palette.fg, fontSize = 14.sp, lineHeight = 21.sp) }
            }
            Spacer(Modifier.height(32.dp))
            return@Column
        }

        DotText("MEMORY", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
        Spacer(Modifier.height(12.dp))
        Text("What I've saved so far. It stays on this phone.", color = palette.muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Card(palette) {
            ToggleRow("Learn automatically", "Keep notes as we talk and as I use your phone", settings.memoryOn, palette) { settings.setMemoryOn(it); tick++ }
        }

        if (files.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("No files yet. They appear as we talk and as I do things on your phone.", color = palette.muted, fontSize = 13.sp)
        }
        var last = ""
        files.forEach { f ->
            val g = Mem.group(f.path)
            if (g != last) {
                last = g
                Spacer(Modifier.height(22.dp))
                Text(g, color = palette.muted, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
            } else Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(palette.card, RoundedCornerShape(22.dp))
                    .border(BorderStroke(1.dp, palette.dim.copy(alpha = 0.6f)), RoundedCornerShape(22.dp))
                    .clickable { onOpen(f.path) }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(Mem.title(f.path), color = palette.fg, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(f.desc.ifBlank { g }, color = palette.muted, fontSize = 13.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text("${f.count} notes · Updated ${fmt.format(Date(f.updated))}", color = palette.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
