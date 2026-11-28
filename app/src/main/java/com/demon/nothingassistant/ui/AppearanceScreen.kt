package com.demon.nothingassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.data.ThemeMode

/** Theme, glass look, accent colour, blob and text animations in one page. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(settings: Settings, palette: GlyphPalette, onClose: () -> Unit) {
    val mono = NFont.label
    Column(
        Modifier
            .fillMaxSize()
            .glassBackdrop(palette)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOTHING ASSISTANT", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("BACK", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.squish(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        DotText("LOOK", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
        Spacer(Modifier.height(22.dp))

        SectionLabel("THEME", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("SYSTEM" to ThemeMode.SYSTEM, "DARK" to ThemeMode.DARK, "LIGHT" to ThemeMode.LIGHT).forEach { (name, mode) ->
                Chip(name, settings.theme == mode, palette) { settings.setTheme(mode) }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("STYLE", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("NOTHING" to 0, "LIQUID GLASS" to 1, "MINIMAL GLASS" to 2).forEach { (name, v) ->
                Chip(name, settings.glassStyle == v, palette) { settings.setGlassStyle(v) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (settings.glassStyle) {
                0 -> "Flat dark cards, exactly like Nothing OS."
                1 -> "Translucent glass cards with a bright rim, a soft glow and a dot grid behind."
                else -> "Quiet glass: thin translucent cards, faint glow, no dot grid."
            },
            color = palette.muted, fontSize = 12.sp
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("ACCENT", palette)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AccentChoices.forEach { (name, color) ->
                val sel = settings.accent == name
                Box(
                    Modifier
                        .size(38.dp)
                        .background(color, CircleShape)
                        .border(BorderStroke(if (sel) 3.dp else 1.dp, if (sel) palette.fg else palette.dim), CircleShape)
                        .squish { settings.setAccent(name) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("MOTION", palette)
        Card(palette) {
            ToggleRow("Dot blob", "A living dot-matrix blob while it listens, thinks and works. Off draws the ring", settings.blob, palette) { settings.setBlob(it) }
            Divider(palette)
            ToggleRow("Typing text", "Words appear one by one as the assistant speaks", settings.typing, palette) { settings.setTyping(it) }
            Divider(palette)
            ToggleRow("Show animations", "Off draws static glyphs", settings.animations, palette) { settings.setAnimations(it) }
            Divider(palette)
            ToggleRow("Battery saver mode", "Simpler visuals, lower power", settings.batterySaver, palette) { settings.setBatterySaver(it) }
        }
        Spacer(Modifier.height(32.dp))
    }
}
