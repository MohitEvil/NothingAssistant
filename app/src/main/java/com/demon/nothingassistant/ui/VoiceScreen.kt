package com.demon.nothingassistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.audio.VoskLang
import com.demon.nothingassistant.audio.VoskAsr
import androidx.compose.ui.platform.LocalContext
import com.demon.nothingassistant.audio.InstallPhase
import com.demon.nothingassistant.audio.InstallState
import com.demon.nothingassistant.audio.NeuralVoice
import com.demon.nothingassistant.audio.VoiceCatalog
import com.demon.nothingassistant.audio.VoiceHub
import com.demon.nothingassistant.data.Settings

/** Offline voice: pick an engine, download a voice once, choose the speaker and speed. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoiceScreen(
    settings: Settings,
    voice: VoiceHub,
    palette: GlyphPalette,
    onOpenLog: () -> Unit,
    onClose: () -> Unit
) {
    val mono = FontFamily.Monospace
    val neural = voice.neural
    val selected = VoiceCatalog.byId(settings.voiceModelId)

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
                "LOG",
                color = palette.muted,
                fontFamily = mono,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onOpenLog).padding(vertical = 8.dp, horizontal = 12.dp)
            )
            Text(
                "BACK",
                color = palette.muted,
                fontFamily = mono,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        DotText("VOICE", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Download a voice once and it works fully offline, with no internet needed after that. " +
                "Hindi in Devanagari is read by Supertonic; other voices hand Hindi to your phone's own voice.",
            color = palette.muted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("SPEECH RECOGNITION", palette)
        SpeechStatus(settings, palette)
        Spacer(Modifier.height(12.dp))
        Card(palette) {
            ToggleRow("Prefer offline speech", "Uses the on-device model when installed", settings.preferOffline, palette) { settings.setPreferOffline(it) }
        }

        val vosk = VoskAsr.get(LocalContext.current)
        Spacer(Modifier.height(20.dp))
        SectionLabel("RECOGNISER", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("AUTO" to "auto", "GOOGLE / SYSTEM" to "system", "ON-DEVICE (OFFLINE)" to "device").forEach { (name, id) ->
                Chip(name, (if (settings.asrEngine in listOf("live", "accurate", "twopass")) "device" else settings.asrEngine) == id, palette) { settings.setAsrEngine(id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (settings.asrEngine) {
                "system" -> "Uses the speech service of your ROM (Google). Needs internet unless offline speech is installed."
                "device" -> "My own offline recogniser (Vosk). Works with no internet and no Google. Download the language you speak below."
                else -> "Uses Google speech when your ROM has it, otherwise my offline recogniser. Recommended."
            },
            color = palette.muted, fontSize = 12.sp
        )
        VoskLang.values().forEach { m ->
            val st = vosk.states[m.id] ?: InstallState(InstallPhase.NOT_INSTALLED)
            Spacer(Modifier.height(10.dp))
            Card(palette) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, color = palette.fg, fontFamily = mono, fontSize = 12.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when (st.phase) { InstallPhase.INSTALLED -> "INSTALLED"; InstallPhase.DOWNLOADING -> "${(st.progress * 100).toInt()}%"; InstallPhase.INSTALLING -> "UNPACKING…"; else -> m.size.uppercase() },
                            color = if (st.phase == InstallPhase.INSTALLED) palette.red else palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp
                        )
                    }
                    if (st.phase == InstallPhase.DOWNLOADING) { Spacer(Modifier.height(8.dp)); ProgressBar(st.progress, palette) }
                    if (st.phase == InstallPhase.FAILED) { Spacer(Modifier.height(6.dp)); Text(st.message, color = palette.red, fontSize = 12.sp) }
                    Spacer(Modifier.height(10.dp))
                    when (st.phase) {
                        InstallPhase.INSTALLED -> PillButton("DELETE", filled = false, palette = palette, onClick = { vosk.delete(m) }, modifier = Modifier.fillMaxWidth())
                        InstallPhase.DOWNLOADING, InstallPhase.INSTALLING -> Unit
                        else -> PillButton(if (st.phase == InstallPhase.FAILED) "TRY AGAIN" else "DOWNLOAD (${m.size.uppercase()})", filled = true, palette = palette, onClick = { vosk.download(m) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("ENGINE", palette)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("NEURAL (OFFLINE)", settings.voiceEngine == "neural", palette) {
                settings.setVoiceEngine("neural")
                selected?.let { if (voice.manager.isInstalled(it)) voice.useModel(it) }
            }
            Chip("PHONE VOICE", settings.voiceEngine == "system", palette) { voice.useDeviceVoice() }
        }

        Spacer(Modifier.height(24.dp))
        PhoneVoices(settings, voice, palette)

        if (!neural.libraryPresent) {
            Spacer(Modifier.height(12.dp))
            Text(
                "The neural engine isn't part of this build, so the phone voice is used. " +
                    "Build with the included GitHub workflow (or run scripts/fetch-sherpa.sh first) to add it.",
                color = palette.red,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("VOICES", palette)
        VoiceCatalog.all.forEach { model ->
            val state = voice.manager.states[model.id] ?: InstallState(InstallPhase.NOT_INSTALLED)
            val inUse = settings.voiceEngine == "neural" && settings.voiceModelId == model.id
            Card(palette) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, color = palette.fg, fontFamily = mono, fontSize = 13.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                inUse && neural.phase == NeuralVoice.Phase.READY -> "● IN USE"
                                inUse && neural.phase == NeuralVoice.Phase.LOADING -> "LOADING…"
                                inUse && neural.phase == NeuralVoice.Phase.ERROR -> "ERROR"
                                state.phase == InstallPhase.INSTALLED -> "INSTALLED"
                                else -> model.sizeLabel.uppercase()
                            },
                            color = if (inUse && neural.phase == NeuralVoice.Phase.READY) palette.red else palette.muted,
                            fontFamily = mono,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(model.blurb, color = palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))

                    when (state.phase) {
                        InstallPhase.NOT_INSTALLED, InstallPhase.FAILED -> {
                            if (state.phase == InstallPhase.FAILED) {
                                Text(state.message, color = palette.red, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                            }
                            PillButton(
                                if (state.phase == InstallPhase.FAILED) "TRY AGAIN" else "DOWNLOAD (${model.sizeLabel.uppercase()})",
                                filled = true,
                                palette = palette,
                                onClick = { voice.manager.download(model) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        InstallPhase.DOWNLOADING -> {
                            val pct = (state.progress * 100).toInt()
                            Text(
                                if (state.progress > 0f) "DOWNLOADING $pct%" else "DOWNLOADING…",
                                color = palette.fg,
                                fontFamily = mono,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            ProgressBar(state.progress, palette)
                            Spacer(Modifier.height(10.dp))
                            PillButton("CANCEL", filled = false, palette = palette, onClick = { voice.manager.cancel(model) }, modifier = Modifier.fillMaxWidth())
                        }
                        InstallPhase.INSTALLING -> Text(
                            "UNPACKING… ONE MOMENT",
                            color = palette.fg,
                            fontFamily = mono,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                        InstallPhase.INSTALLED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                if (inUse) "IN USE" else "USE THIS VOICE",
                                filled = !inUse,
                                palette = palette,
                                onClick = { if (!inUse) voice.useModel(model) },
                                modifier = Modifier.weight(1f)
                            )
                            PillButton("DELETE", filled = false, palette = palette, onClick = { voice.deleteModel(model) })
                        }
                    }

                    if (inUse && neural.phase == NeuralVoice.Phase.ERROR) {
                        Spacer(Modifier.height(8.dp))
                        Text("Couldn't start this voice: ${neural.error}. The phone voice is used instead.", color = palette.red, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (selected != null && settings.voiceEngine == "neural" && voice.manager.isInstalled(selected)) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("SPEAKER", palette)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                selected.voices.forEach { (name, sid) ->
                    Chip(name.uppercase(), settings.voiceSid == sid, palette) {
                        settings.setVoiceSid(sid)
                        voice.rewarm()
                        voice.speak("Hi, this is how I sound.") {}
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("SPEED", palette)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("RELAXED" to 1.0f, "NORMAL" to 1.15f, "QUICK" to 1.3f, "TURBO" to 1.5f).forEach { (name, v) ->
                    Chip(name, settings.voiceSpeed == v, palette) {
                        settings.setVoiceSpeed(v)
                        voice.rewarm()
                        voice.speak("Hey, this is my speed.") {}
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        PillButton(
            "TEST VOICE",
            filled = true,
            palette = palette,
            onClick = { voice.speak("Hey! This is how I sound. Pretty natural, right?") {} },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ProgressBar(progress: Float, palette: GlyphPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(palette.dim, androidx.compose.foundation.shape.RoundedCornerShape(50))
    ) {
        if (progress > 0f) {
            Spacer(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(palette.red, androidx.compose.foundation.shape.RoundedCornerShape(50))
            )
        }
    }
}

/** Every voice the phone's own speech engine has installed, found automatically. Tap the speaker to hear one, tap the row to use it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PhoneVoices(settings: Settings, voice: VoiceHub, palette: GlyphPalette) {
    val mono = FontFamily.Monospace
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var voices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList<com.demon.nothingassistant.audio.SpeechOut.DeviceVoice>()) }
    var filter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) {
            var tries = 0
            while (voices.isEmpty() && tries++ < 20) {
                voices = voice.deviceVoices()
                if (voices.isEmpty()) kotlinx.coroutines.delay(500)
            }
        }
    }
    SectionLabel("PHONE VOICES (INSTALLED ON THIS PHONE)", palette)
    Text(
        "Found automatically from the phone's speech engine. Works offline for voices marked LOCAL.",
        color = palette.muted, fontSize = 12.sp
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip(if (open) "HIDE LIST" else "CHOOSE A PHONE VOICE", open, palette) { open = !open }
        Chip("AUTOMATIC", settings.deviceVoice.isBlank(), palette) { settings.setDeviceVoice(""); voice.useDeviceVoice() }
    }
    if (settings.deviceVoice.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("IN USE: ${settings.deviceVoice}", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp)
    }
    if (!open) return
    Spacer(Modifier.height(12.dp))
    if (voices.isEmpty()) {
        Text("LOOKING FOR VOICES…", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp)
        return
    }
    com.demon.nothingassistant.ui.Field("SEARCH", filter, palette) { filter = it }
    Spacer(Modifier.height(8.dp))
    val shown = (if (filter.isBlank()) voices else voices.filter { it.label.contains(filter.trim(), ignoreCase = true) }).take(80)
    Text("${shown.size} OF ${voices.size} VOICES", color = palette.muted, fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))
    shown.forEach { v ->
        val on = settings.deviceVoice == v.name
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .background(if (on) palette.dim.copy(alpha = 0.6f) else palette.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .clickable { settings.setDeviceVoice(v.name); voice.useDeviceVoice() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (on) "●" else "○", color = if (on) palette.red else palette.muted, fontFamily = mono, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.label, color = palette.fg, fontSize = 12.sp)
                Text(if (v.offline) "LOCAL" else "NEEDS INTERNET", color = palette.muted, fontFamily = mono, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Text(
                "PLAY", color = palette.fg, fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier
                    .border(androidx.compose.foundation.BorderStroke(1.dp, palette.muted), androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .clickable { voice.previewDeviceVoice(v.name) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}
