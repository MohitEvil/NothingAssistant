package com.demon.nothingassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.audio.SpeechInfo
import com.demon.nothingassistant.audio.VoskAsr
import com.demon.nothingassistant.data.Settings
import java.util.Locale

/** Shows which speech recogniser the phone has and its languages (detected), or guides the person when there is none. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SpeechStatus(settings: Settings, palette: GlyphPalette) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { SpeechInfo.autoConfigure(ctx, settings) }
    val available = remember(tick) { SpeechInfo.available(ctx) }
    val label = remember(tick) { SpeechInfo.label(ctx) }
    val hasVosk = VoskAsr.get(ctx).anyInstalled()

    if (available) {
        Text(
            "Detected: ${label ?: "speech recogniser"}." + if (settings.langChosen) " Language set by you." else " Language picked automatically.",
            color = palette.muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        var all by remember { mutableStateOf(false) }
        val langs = settings.supportedLangs
        val sys = Locale.getDefault().toLanguageTag()
        val common = listOf("en-US", "en-IN", "en-GB", "hi-IN", sys, settings.language)
        val shown = if (langs.isEmpty()) listOf("en-US", "en-IN", "hi-IN")
            else if (all) langs else langs.filter { l -> common.any { it.equals(l, true) } }.ifEmpty { langs.take(8) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.forEach { tag -> Chip(tag.uppercase(), settings.language.equals(tag, true), palette) { settings.setLanguage(tag) } }
            if (langs.size > shown.size || all) Chip(if (all) "FEWER" else "ALL LANGUAGES", false, palette) { all = !all }
            Chip("REFRESH", false, palette) { tick++ }
        }
        if (!settings.langChosen) {
            Spacer(Modifier.height(6.dp))
            Text("Tap a language to choose it yourself. Offline packs for the language are in your phone's Voice input settings.", color = palette.muted, fontSize = 11.sp)
        }
    } else {
        Text("No speech recogniser found on this phone.", color = palette.red, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasVosk) "Your offline recogniser (Vosk) will be used. To get better results you can also install Google's:"
            else "To talk to me you need one. Easiest: install or enable the Google app, then pick it as Voice input. Or download my offline recogniser below.",
            color = palette.muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Text("1. Install or update the Google app (it includes speech recognition).\n2. Open Voice input settings and choose Google.\n3. Come back here and tap REFRESH.", color = palette.fg, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("OPEN GOOGLE APP PAGE", false, palette) {
                val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.googlequicksearchbox")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(play) }.onFailure {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            Chip("VOICE INPUT SETTINGS", false, palette) { openVoiceSettings(ctx) }
            Chip("REFRESH", false, palette) { tick++ }
        }
    }
}

private fun openVoiceSettings(ctx: Context) {
    val i = Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
