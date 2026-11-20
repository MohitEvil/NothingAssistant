package com.demon.nothingassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.data.AiPresets
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: Settings,
    palette: GlyphPalette,
    perf: PerfMode,
    onBubbleToggle: (Boolean) -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLog: () -> Unit,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
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
            Text(
                "CLOSE",
                color = palette.muted,
                fontFamily = mono,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.squish(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        DotText("SETTINGS", palette.fg, palette.dim.copy(alpha = 0.45f), 4.dp)
        Spacer(Modifier.height(24.dp))

        Card(palette) {
            ToggleRow("Spoken replies", "The assistant talks back and asks you questions", settings.speakReplies, palette) { settings.setSpeakReplies(it) }
            Divider(palette)
            // One switch for both old options: they did the same job.
            ToggleRow("Keep listening", "The mic reopens after each answer or task, so you can just reply. Say ok, thanks or stop to close", settings.followUp || settings.stayOpen, palette) {
                settings.setFollowUp(it); settings.setStayOpen(it)
            }
            Divider(palette)
            ToggleRow("Use haptics", "Short taps for key moments only", settings.haptics, palette) { settings.setHaptics(it) }
            Divider(palette)
            ToggleRow("Floating bubble", "Draws over other apps. Tap to talk.", settings.bubble, palette, onBubbleToggle)
        }

        Spacer(Modifier.height(16.dp))
        Card(palette) {
            AppPickRow("Default music app", "Used for \"play a song\" when you don't name an app", settings.musicApp, palette) { settings.setMusicApp(it) }
            Divider(palette)
            AppPickRow("Default video app", "Used for \"play a video\" when you don't name an app", settings.videoApp, palette) { settings.setVideoApp(it) }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("CUSTOMISE", palette)
        Card(palette) {
            NavRow(DotIcons.VOICE, "Voice", "Voice, language, speech recognition and speed", palette, onOpenVoice)
            Divider(palette)
            NavRow(
                DotIcons.AI, "AI settings",
                "Chat: " + (if (!settings.aiEnabled) "off" else settings.providers.firstOrNull { it.ready }?.let { it.name.lowercase() } ?: "on, no provider") +
                    "  ·  Voice: " + (if (!settings.aiVoiceEnabled) "off" else settings.voiceProviders.firstOrNull { it.ready }?.let { it.name.lowercase() } ?: "on, no provider"),
                palette, onOpenAi
            )
            Divider(palette)
            NavRow(DotIcons.APPEARANCE, "Appearance", "Theme, liquid glass, blob and animations", palette, onOpenAppearance)
            Divider(palette)
            NavRow(DotIcons.ACCESS, "Access and permissions", "Phone control, app permissions, Shizuku and root", palette, onOpenAccess)
            Divider(palette)
            NavRow(DotIcons.LOG, "Debug log", "Temporary: see what the app and voices are doing", palette, onOpenLog)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "VISUALS: ${perf.name}",
            color = palette.muted,
            fontFamily = mono,
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Nothing Assistant 1.0. A Nothing-inspired design made for the CMF Phone 1. Not affiliated with Nothing Technology.",
            color = palette.muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** A row that opens a page: dot icon, title, one line of state, chevron. */
@Composable
internal fun NavRow(icon: List<String>, title: String, subtitle: String, palette: GlyphPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().squish(onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DotIcon(icon, palette.fg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.fg, fontSize = 15.sp)
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        Text("›", color = palette.muted, fontSize = 20.sp)
    }
}

@Composable
internal fun Card(palette: GlyphPalette, content: @Composable () -> Unit) {
    val glass = LocalUiStyle.current.isGlass
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (glass) Modifier.glassFill(palette, shape) else Modifier.background(palette.card, shape).border(BorderStroke(1.dp, palette.dim), shape))
    ) { content() }
}

@Composable
internal fun Divider(palette: GlyphPalette) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(1.dp)
            .background(palette.dim.copy(alpha = 0.6f))
    )
}

@Composable
internal fun SectionLabel(text: String, palette: GlyphPalette) {
    Text(
        text,
        color = palette.muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    palette: GlyphPalette,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .squish { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.fg, fontSize = 15.sp)
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.red,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = palette.muted,
                uncheckedTrackColor = palette.dim,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
internal fun Chip(text: String, selected: Boolean, palette: GlyphPalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (selected) palette.bg else palette.fg,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .then(
                if (selected) Modifier.background(palette.fg, shape)
                else if (LocalUiStyle.current.glass == 1) Modifier.glassFill(palette, shape)
                else Modifier.border(BorderStroke(1.dp, palette.muted), shape)
            )
            .squish(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
internal fun Field(
    label: String,
    value: String,
    palette: GlyphPalette,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    readOnly: Boolean = false,
    onChange: (String) -> Unit
) {
    Text(label, color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        readOnly = readOnly,
        singleLine = true,
        textStyle = TextStyle(color = palette.fg, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(palette.red),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.bg, RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}


/** Optional Shizuku / root. Off by default behaviour is "auto": used only when it's there and allowed. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedAccessCard(settings: Settings, palette: GlyphPalette) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.demon.nothingassistant.priv.Priv.describe(ctx)) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val needle = androidx.compose.runtime.remember { com.demon.nothingassistant.agent.NeedleEngine.bundled(ctx) }
    fun refresh() { status = com.demon.nothingassistant.priv.Priv.describe(ctx) }
    Card(palette) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("Advanced access", color = palette.fg, fontSize = 15.sp)
            Text(
                "Optional. With Shizuku or root I can switch Wi-Fi and data directly, clear caches, remove permissions and more. Without them everything still works through normal access and screen control.",
                color = palette.muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("AUTO" to "auto", "OFF" to "off", "SHIZUKU" to "shizuku", "ROOT" to "root").forEach { (name, mode) ->
                    Chip(name, settings.privMode == mode, palette) {
                        settings.setPrivMode(mode)
                        if (mode == "root" || mode == "auto") scope.launch {
                            busy = true
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.demon.nothingassistant.priv.Priv.probe() }
                            busy = false
                            refresh()
                        } else refresh()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (busy) "Checking…" else status, color = palette.fg, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("GRANT SHIZUKU", false, palette) {
                    if (com.demon.nothingassistant.priv.Priv.shizukuRunning()) com.demon.nothingassistant.priv.Priv.requestShizuku()
                    else runCatching {
                        ctx.startActivity(
                            (ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    refresh()
                }
                Chip("CHECK AGAIN", false, palette) {
                    scope.launch {
                        busy = true
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.demon.nothingassistant.priv.Priv.probe() }
                        busy = false
                        refresh()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (needle) "Tool calling: Needle 3 (on-device) is included." else "Tool calling: Needle 3 isn't in this build, so your AI provider (if set up) plans multi-step requests.",
                color = palette.muted, fontSize = 12.sp
            )
        }
    }
}
