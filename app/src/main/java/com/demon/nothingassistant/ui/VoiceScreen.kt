package com.demon.nothingassistant.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.audio.InstallPhase
import com.demon.nothingassistant.audio.InstallState
import com.demon.nothingassistant.audio.NeuralVoice
import com.demon.nothingassistant.audio.SpeechEngine
import com.demon.nothingassistant.audio.SpeechInfo
import com.demon.nothingassistant.audio.VoiceCatalog
import com.demon.nothingassistant.audio.VoiceHub
import com.demon.nothingassistant.audio.VoskAsr
import com.demon.nothingassistant.audio.VoskLang
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.service.WakeService

/** Voice settings: a short list of pages instead of one long scroll. Downloads live inside the page they belong to. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoiceScreen(
    settings: Settings,
    voice: VoiceHub,
    palette: GlyphPalette,
    onOpenLog: () -> Unit,
    onClose: () -> Unit,
    onOpenAiVoice: () -> Unit = {}
) {
    var page by remember { mutableStateOf("hub") }
    BackHandler(enabled = page != "hub") { page = parent(page) }

    if (page == "wakesetup") {
        WakeSetup(settings, palette) { page = "wake" }
        return
    }
    if (page == "pickphone") {
        PhoneVoicePicker(settings, voice, palette) { page = "voices" }
        return
    }

    val titles = mapOf("hub" to "VOICE", "recog" to "SPEECH RECOGNITION", "vosk" to "OFFLINE LANGUAGES", "voices" to "VOICES", "neural" to "DOWNLOADABLE VOICES", "wake" to "WAKE WORD", "assist" to "BEHAVIOUR")
    Column(
        Modifier
            .fillMaxSize()
            .glassBackdrop(palette)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOTHING ASSISTANT", color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("LOG", color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable(onClick = onOpenLog).padding(vertical = 8.dp, horizontal = 12.dp))
            Text("BACK", color = palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable { if (page == "hub") onClose() else page = parent(page) }.padding(vertical = 8.dp, horizontal = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        DotText(if (page == "hub") "VOICE" else titles[page] ?: "VOICE", palette.fg, palette.dim.copy(alpha = 0.45f), if (page == "hub") 5.dp else 3.dp)
        Spacer(Modifier.height(16.dp))
        when (page) {
            "recog" -> RecogPage(settings, palette) { page = "vosk" }
            "vosk" -> VoskPage(palette)
            "voices" -> VoicesPage(settings, voice, palette, onOpenAiVoice, { page = "pickphone" }, { page = "neural" })
            "neural" -> NeuralPage(settings, voice, palette)
            "wake" -> WakePage(settings, palette) { page = "wakesetup" }
            "assist" -> AssistPage(settings, voice, palette)
            else -> HubPage(settings, voice, palette, onClose) { page = it }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun parent(page: String) = when (page) { "wakesetup" -> "wake"; "vosk" -> "recog"; "pickphone", "neural" -> "voices"; else -> "hub" }

@Composable
private fun HubPage(settings: Settings, voice: VoiceHub, palette: GlyphPalette, onClose: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val vosk = VoskAsr.get(ctx)
    val installed = vosk.states.values.count { it.phase == InstallPhase.INSTALLED }
    Text(
        "Everything about how I hear you and how I sound. Pick a page.",
        color = palette.muted, fontSize = 12.sp
    )
    Spacer(Modifier.height(18.dp))
    Card(palette) {
        NavRow(DotIcons.VOICE, "Speech recognition", "${SpeechInfo.niceName(settings.language)} · " + (if (installed > 0) "$installed offline language(s) downloaded" else "Google / system"), palette) { go("recog") }
        Divider(palette)
        NavRow(DotIcons.VOICE, "Voices", when {
            settings.aiVoiceEnabled && settings.voiceProviders.any { it.ready } -> "Online voice in use"
            settings.voiceEngine == "neural" -> "Offline neural voice"
            settings.deviceVoice.isNotBlank() -> "Phone voice: ${settings.deviceVoice}"
            else -> "Phone voice (automatic)"
        }, palette) { go("voices") }
        Divider(palette)
        NavRow(DotIcons.VOICE, "Wake word", if (settings.wakeWord) "On · \"${settings.wakePhrase}\"" else "Off", palette) { go("wake") }
        Divider(palette)
        NavRow(DotIcons.GENERAL, "Behaviour", "Interrupt, voice gender, screen vision, default assistant", palette) { go("assist") }
    }
    Spacer(Modifier.height(20.dp))
    PillButton("TEST VOICE", filled = true, palette = palette, onClick = { voice.speak("Hey! This is how I sound. Pretty natural, right?") {} }, modifier = Modifier.fillMaxWidth())
}

// ---------------------------------------------------------------------------------------------------------------------
// Speech recognition
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecogPage(settings: Settings, palette: GlyphPalette, openVosk: () -> Unit) {
    val ctx = LocalContext.current
    val vosk = VoskAsr.get(ctx)
    var tick by remember { mutableStateOf(0) }
    var google by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(tick) {
        SpeechInfo.autoConfigure(ctx, settings)
        SpeechInfo.installedOffline(ctx) { google = it }
    }
    val installedCount = vosk.states.values.count { it.phase == InstallPhase.INSTALLED }
    val voskTags = remember(installedCount) { vosk.installedModels().map { it.tag } }
    val langs = (google + voskTags).distinctBy { it.lowercase() }
    val label = remember(tick) { SpeechInfo.label(ctx) }
    val current = langs.firstOrNull { it.equals(settings.language, true) } ?: langs.firstOrNull { it.substringBefore('-').equals(settings.language.substringBefore('-'), true) }

    SectionLabel("RECOGNISER", palette)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("AUTO" to "auto", "GOOGLE / SYSTEM" to "system", "ON-DEVICE (OFFLINE)" to "device").forEach { (name, id) ->
            Chip(name, (if (settings.asrEngine in listOf("live", "accurate", "twopass")) "device" else settings.asrEngine) == id, palette) { settings.setAsrEngine(id) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        when (settings.asrEngine) {
            "system" -> "Uses the speech service of your ROM" + (label?.let { " ($it)" } ?: "") + ". Needs internet unless the language is downloaded offline."
            "device" -> "My own offline recogniser (Vosk). Works with no internet and no Google. Download the languages you speak."
            else -> "Uses Google speech when your ROM has it, otherwise my offline recogniser. Recommended."
        },
        color = palette.muted, fontSize = 12.sp
    )
    Spacer(Modifier.height(12.dp))
    Card(palette) {
        ToggleRow("Prefer offline speech", "Uses the on-device model when installed", settings.preferOffline, palette) { settings.setPreferOffline(it) }
        Divider(palette)
        ToggleRow("Understand two languages", "Offline recogniser listens for two languages at once (for example English and Hindi) and keeps the better result", settings.bilingual, palette) { settings.setBilingual(it) }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel("LANGUAGES ON THIS PHONE", palette)
    if (langs.isEmpty()) {
        Text("No downloaded language found yet. Download one below, or add offline speech in your phone's Voice input settings.", color = palette.muted, fontSize = 12.sp)
    } else {
        Text("Only languages that are downloaded here are listed. Tap one to speak it.", color = palette.muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        langs.forEach { tag -> Chip(SpeechInfo.niceName(tag).uppercase(), tag == current, palette) { settings.setLanguage(tag) } }
        Chip("REFRESH", false, palette) { tick++ }
    }
    if (!SpeechInfo.available(ctx)) {
        Spacer(Modifier.height(10.dp))
        Text("No Google speech recogniser found on this phone, so the offline one is used.", color = palette.red, fontSize = 12.sp)
    }

    Spacer(Modifier.height(24.dp))
    Card(palette) { NavRow(DotIcons.VOICE, "Offline languages", if (installedCount > 0) "$installedCount downloaded · add or remove" else "Download languages (Russian, Arabic, Hindi…)", palette, openVosk) }
}

/** Every language the offline recogniser can learn, each downloaded once. */
@Composable
private fun VoskPage(palette: GlyphPalette) {
    val ctx = LocalContext.current
    val vosk = VoskAsr.get(ctx)
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (vosk.catalog.isEmpty()) vosk.fetchCatalog() }
    val mono = FontFamily.Monospace

    @Composable
    fun row(m: VoskLang) {
        val st = vosk.states[m.id] ?: InstallState(InstallPhase.NOT_INSTALLED)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.title, color = palette.fg, fontSize = 15.sp)
                Text(
                    when (st.phase) {
                        InstallPhase.INSTALLED -> "Downloaded"
                        InstallPhase.DOWNLOADING -> "Downloading ${(st.progress * 100).toInt()}%"
                        InstallPhase.INSTALLING -> "Unpacking…"
                        InstallPhase.FAILED -> st.message
                        else -> m.size
                    }, color = if (st.phase == InstallPhase.FAILED) palette.red else palette.muted, fontSize = 12.sp
                )
                if (st.phase == InstallPhase.DOWNLOADING) { Spacer(Modifier.height(6.dp)); ProgressBar(st.progress, palette) }
            }
            Spacer(Modifier.width(12.dp))
            when (st.phase) {
                InstallPhase.INSTALLED -> Text("DELETE", color = palette.fg, fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.border(BorderStroke(1.dp, palette.muted), RoundedCornerShape(50)).clickable { vosk.delete(m) }.padding(horizontal = 14.dp, vertical = 8.dp))
                InstallPhase.DOWNLOADING, InstallPhase.INSTALLING -> Unit
                else -> Text("GET", color = palette.bg, fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.background(palette.fg, RoundedCornerShape(50)).clickable { vosk.download(m) }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    val installed = vosk.installedModels()
    if (installed.isNotEmpty()) {
        SectionLabel("DOWNLOADED", palette)
        installed.forEach { row(it) }
        Spacer(Modifier.height(18.dp))
    }
    SectionLabel("AVAILABLE", palette)
    SearchField(q, "Search languages", palette) { q = it }
    Spacer(Modifier.height(8.dp))
    val all = (VoskLang.BUILTIN + vosk.catalog).filter { m -> installed.none { it.id == m.id } }
        .filter { q.isBlank() || it.title.contains(q.trim(), true) || it.tag.contains(q.trim(), true) }
    all.forEach { row(it) }
    if (vosk.catalogStatus.value.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(vosk.catalogStatus.value, color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 1.sp)
    }
    Spacer(Modifier.height(10.dp))
    Chip("REFRESH LIST", false, palette) { vosk.fetchCatalog() }
}

// ---------------------------------------------------------------------------------------------------------------------
// Voices
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VoicesPage(settings: Settings, voice: VoiceHub, palette: GlyphPalette, onOpenAiVoice: () -> Unit, pickPhone: () -> Unit, openNeural: () -> Unit) {
    val selected = VoiceCatalog.byId(settings.voiceModelId)
    val installedModels = VoiceCatalog.all.count { voice.manager.states[it.id]?.phase == InstallPhase.INSTALLED }
    SectionLabel("ENGINE", palette)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("NEURAL (OFFLINE)", settings.voiceEngine == "neural", palette) {
            settings.setVoiceEngine("neural")
            selected?.let { if (voice.manager.isInstalled(it)) voice.useModel(it) }
        }
        Chip("PHONE VOICE", settings.voiceEngine == "system", palette) { voice.useDeviceVoice() }
    }
    Spacer(Modifier.height(18.dp))
    Card(palette) {
        NavRow(DotIcons.VOICE, "Phone voices", if (settings.deviceVoice.isBlank()) "Automatic · choose from every voice installed" else settings.deviceVoice, palette, pickPhone)
        Divider(palette)
        NavRow(DotIcons.VOICE, "Downloadable offline voices", if (installedModels > 0) "$installedModels downloaded" else "Kokoro, Supertonic and more", palette, openNeural)
        Divider(palette)
        NavRow(DotIcons.AI, "Online voices", if (settings.aiVoiceEnabled) "On · Edge, Gemini, OpenAI…" else "Edge free voices and AI voice providers", palette, onOpenAiVoice)
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
    Spacer(Modifier.height(20.dp))
    PillButton("TEST VOICE", filled = true, palette = palette, onClick = { voice.speak("Hey! This is how I sound. Pretty natural, right?") {} }, modifier = Modifier.fillMaxWidth())
}

/** The phone's own voices, in the same list style as every other voice picker. */
@Composable
private fun PhoneVoicePicker(settings: Settings, voice: VoiceHub, palette: GlyphPalette, done: () -> Unit) {
    var voices by remember { mutableStateOf(emptyList<com.demon.nothingassistant.audio.SpeechOut.DeviceVoice>()) }
    var tick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(tick) {
        loading = true
        var tries = 0
        voices = voice.deviceVoices()
        while (voices.isEmpty() && tries++ < 20) { kotlinx.coroutines.delay(500); voices = voice.deviceVoices() }
        loading = false
    }
    val items = remember(voices) {
        listOf(PickItem("", "Automatic", "Best voice for the language")) +
            voices.map { v -> PickItem(v.name, v.name, v.label.substringAfter("  ·  ", ""), if (v.offline) "" else "Online") }
    }
    BackHandler { done() }
    VoicePicker(
        title = "Voice", items = items, selected = settings.deviceVoice, palette = palette, loading = loading,
        status = if (loading && voices.isEmpty()) "Looking for voices…" else "",
        onRefresh = { tick++ },
        onSelect = { settings.setDeviceVoice(it.id); voice.useDeviceVoice() },
        onPreview = { if (it.id.isNotEmpty()) voice.previewDeviceVoice(it.id) else voice.speak("Hi, this is how I sound.") {} },
        onDone = done
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NeuralPage(settings: Settings, voice: VoiceHub, palette: GlyphPalette) {
    val mono = FontFamily.Monospace
    val neural = voice.neural
    val selected = VoiceCatalog.byId(settings.voiceModelId)
    Text(
        "Download a voice once and it works fully offline. Hindi in Devanagari is read by Supertonic; other voices hand Hindi to your phone's own voice.",
        color = palette.muted, fontSize = 12.sp
    )
    Spacer(Modifier.height(16.dp))
    if (!neural.libraryPresent) {
        Text(
            "The neural engine isn't part of this build, so the phone voice is used. Build with the included GitHub workflow (or run scripts/fetch-sherpa.sh first) to add it.",
            color = palette.red, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
    }
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

        }


}

@Composable
private fun ProgressBar(progress: Float, palette: GlyphPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(palette.dim, RoundedCornerShape(50))
    ) {
        if (progress > 0f) {
            Spacer(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(palette.red, RoundedCornerShape(50))
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Wake word
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WakePage(settings: Settings, palette: GlyphPalette, openSetup: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val trained = remember(tick) { com.demon.nothingassistant.audio.WakeVoice.ready(ctx) }
    val count = remember(tick) { com.demon.nothingassistant.audio.WakeVoice.sampleCount(ctx) }
    val hasPrint = remember(tick) { com.demon.nothingassistant.audio.Voiceprint.ready(ctx) }
    var note by remember { mutableStateOf("") }

    Text(
        "Say your phrase and I start listening, hands free. I learn your own voice, so it works for any phrase and any language, and needs no download. " +
            "A tiny volume check runs all the time; only when someone speaks is that short burst compared with your voice. Nothing is recorded or sent anywhere.",
        color = palette.muted, fontSize = 12.sp
    )
    Spacer(Modifier.height(16.dp))
    Card(palette) {
        ToggleRow("Wake word", if (!trained) "Set it up first" else if (settings.wakeWord) "Listening for \"${settings.wakePhrase}\"" else "Off", settings.wakeWord, palette) { on ->
            note = ""
            if (!on) { settings.setWakeWord(false); WakeService.stop(ctx) }
            else if (!trained) note = "Teach me your voice first."
            else if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) note = "The microphone permission is needed."
            else { settings.setWakeWord(true); WakeService.start(ctx) }
        }
    }
    if (note.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(note, color = palette.red, fontSize = 12.sp) }

    Spacer(Modifier.height(22.dp))
    SectionLabel("YOUR VOICE", palette)
    Text(if (trained) "\"${settings.wakePhrase}\" · Voice Match ${if (hasPrint) "is on" else "needs setting up again"}." else "Not set up yet.", color = palette.fg, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    PillButton(if (trained) "SET UP VOICE MATCH AGAIN" else "SET UP VOICE MATCH", filled = true, palette = palette, modifier = Modifier.fillMaxWidth(), onClick = openSetup)
    if (trained && hasPrint) {
        Spacer(Modifier.height(12.dp))
        Card(palette) {
            ToggleRow("Only my voice", "Right words from someone else won't wake me", settings.voiceLock, palette) { on ->
                settings.setVoiceLock(on)
                if (settings.wakeWord) { WakeService.stop(ctx); WakeService.start(ctx) }
            }
        }
    }
    if (trained) {
        Spacer(Modifier.height(10.dp))
        Chip("DELETE MY VOICE DATA", false, palette) {
            WakeService.stop(ctx); settings.setWakeWord(false)
            com.demon.nothingassistant.audio.WakeVoice.clear(ctx); com.demon.nothingassistant.audio.Voiceprint.clear(ctx); tick++
        }
    }

    Spacer(Modifier.height(22.dp))
    SectionLabel("SENSITIVITY", palette)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("STRICT" to 0.25f, "BALANCED" to 0.5f, "EAGER" to 0.8f).forEach { (n, v) ->
            Chip(n, settings.wakeSens == v, palette) { settings.setWakeSens(v); if (settings.wakeWord) { WakeService.stop(ctx); WakeService.start(ctx) } }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Strict: fewer false wake-ups. Eager: wakes more easily, even in a noisy room. A small notification stays on while the wake word listens. If a restart stops it, open the app once.", color = palette.muted, fontSize = 11.sp)
}

// ---------------------------------------------------------------------------------------------------------------------
// Behaviour
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AssistPage(settings: Settings, voice: VoiceHub, palette: GlyphPalette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val isDefault = remember(tick) { runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, "assistant").orEmpty().contains(ctx.packageName) }.getOrDefault(false) }
    val detected = if (settings.voiceGender == "auto") voice.gender() else ""

    Card(palette) {
        ToggleRow("Speak to interrupt", "Talk over a long answer and I stop and listen, so you can change or drop the task", settings.interrupt, palette) { settings.setInterrupt(it) }
        Divider(palette)
        ToggleRow("AI sees the screen", "For things on screen, a screenshot goes to the AI so it can tap and type correctly", settings.aiVision, palette) { settings.setAiVision(it) }
        Divider(palette)
        ToggleRow("Stay the default assistant", "Put me back as the default digital assistant after a restart, instead of Gemini", settings.keepAssistant, palette) { settings.setKeepAssistant(it) }
    }
    Spacer(Modifier.height(10.dp))
    Text(if (isDefault) "Nothing Assistant is the default digital assistant now." else "Nothing Assistant is not the default assistant right now.", color = palette.muted, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("SET AS DEFAULT NOW", false, palette) {
            scope.launch {
                val ok = com.demon.nothingassistant.priv.AutoGrant.setAssistant(ctx)
                if (!ok) runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                tick++
            }
        }
        Chip("REFRESH", false, palette) { tick++ }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel("MY VOICE IS", palette)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("AUTOMATIC" to "auto", "FEMALE" to "female", "MALE" to "male").forEach { (n, v) -> Chip(n, settings.voiceGender == v, palette) { settings.setVoiceGender(v) } }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        (if (settings.voiceGender == "auto") (if (detected.isNotEmpty()) "Detected from the voice in use: $detected. " else "Couldn't tell from this voice, so I stay neutral. Pick one if you like. ") else "") +
            "The AI answers to match: for example in Hindi it says \"karti hoon\" for a female voice and \"karta hoon\" for a male one.",
        color = palette.muted, fontSize = 12.sp
    )
}
