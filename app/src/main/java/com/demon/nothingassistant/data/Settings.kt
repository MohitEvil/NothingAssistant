package com.demon.nothingassistant.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Theme choices: follow the system, force AMOLED dark, or force light. */
object ThemeMode {
    const val SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2
}

/** One AI provider the assistant can use. The list order is the order they are tried in. */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val key: String,
    val locked: Boolean = false,
    val custom: Boolean = false,
    /** Voice providers only: the voice name or id to speak with. */
    val extra: String = ""
) {
    /** Has everything needed to make a request. Providers that aren't ready are skipped. */
    val ready: Boolean get() = (key.isNotBlank() || keyless) && model.isNotBlank() && baseUrl.isNotBlank()

    /** Free services that need no account or key (Kilo's free gateway, Pollinations, Edge voices). */
    val keyless: Boolean get() = baseUrl.startsWith("edge://") || baseUrl.contains("api.kilo.ai") || baseUrl.contains("text.pollinations.ai")
}

/** Ready-made settings for popular AI providers. All but Anthropic speak the OpenAI chat format. */
object AiPresets {
    data class Preset(val id: String, val name: String, val baseUrl: String, val model: String)

    val all = listOf(
        Preset("gemini", "GEMINI", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        Preset("groq", "GROQ", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Preset("openrouter", "OPENROUTER", "https://openrouter.ai/api/v1", "openrouter/auto"),
        Preset("openai", "OPENAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset("anthropic", "ANTHROPIC", "https://api.anthropic.com/v1", "claude-haiku-5-5"),
        Preset("kilo", "KILO FREE", "https://api.kilo.ai/api/gateway", "kilo-auto/free"),
        Preset("custom", "CUSTOM", "", "")
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all.first()
}

/** Ready-made settings for cloud text-to-speech providers. */
object VoicePresets {
    data class Preset(val id: String, val name: String, val baseUrl: String, val model: String, val voice: String)

    val all = listOf(
        Preset("v_openai", "OPENAI", "https://api.openai.com/v1", "gpt-4o-mini-tts", "alloy"),
        Preset("v_groq", "GROQ", "https://api.groq.com/openai/v1", "canopylabs/orpheus-v1-english", "hannah"),
        Preset("v_google", "GOOGLE", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash-preview-tts", "Kore"),
        Preset("v_eleven", "ELEVENLABS", "https://api.elevenlabs.io/v1", "eleven_flash_v2_5", "21m00Tcm4TlvDq8ikWAM"),
        Preset("v_edge", "EDGE FREE", "edge://tts", "edge", "en-US-AriaNeural")
    )
}

/** How the AI should behave. The default is a quiet, minimal assistant that is good at small talk. */
object PersonaPresets {
    const val DEFAULT =
        "You are a minimal, quiet assistant who is good at small talk. Answer in one short sentence whenever you can, " +
            "two at most. Don't over-explain, don't add pleasantries, don't offer extra help, and don't ask follow-up " +
            "questions unless you truly need to. For casual chat be calm, warm and natural, like a friend who doesn't say much. " +
            "Give more detail only when the user asks for it."

    data class Preset(val name: String, val text: String)

    val all = listOf(
        Preset("MINIMAL", DEFAULT),
        Preset(
            "FRIENDLY",
            "You are a warm, upbeat friend. Chat naturally with a little curiosity, in two or three short sentences, " +
                "using casual everyday language. Ask a short question back only when it keeps the conversation going."
        ),
        Preset(
            "WITTY",
            "You are dry-witted and playful, with light teasing that is never mean. Keep replies to one or two short " +
                "sentences. Make the odd small joke, but be genuinely helpful first."
        ),
        Preset(
            "DESI BUDDY",
            "You talk like a close Indian friend, mixing Hindi and English (Hinglish) naturally when the user does. " +
                "Keep it casual, warm and short, one or two sentences."
        ),
        Preset(
            "HINGLISH",
            "You talk in natural Hinglish like a close friend: write Hindi words in Devanagari and English words in English, " +
                "mixed the way people speak, for example: ठीक है, मैं time देख लेता हूँ. Keep it casual and short, one or two sentences."
        ),
        Preset(
            "FORMAL",
            "You are a polite, professional assistant. Use clear, complete sentences with no slang, and keep replies " +
                "to two sentences unless asked for more."
        )
    )
}

/** Small SharedPreferences-backed settings holder whose values are Compose state. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("nothing_settings", Context.MODE_PRIVATE)

    var haptics by mutableStateOf(sp.getBoolean("haptics", true))
        private set
    var animations by mutableStateOf(sp.getBoolean("animations", true))
        private set
    var batterySaver by mutableStateOf(sp.getBoolean("battery_saver", false))
        private set
    var bubble by mutableStateOf(sp.getBoolean("bubble", false))
        private set
    var preferOffline by mutableStateOf(sp.getBoolean("prefer_offline", true))
    /** Which speech recogniser: auto, system, live, accurate, twopass. */
    var asrEngine by mutableStateOf(sp.getString("asr_engine", "auto") ?: "auto")
    /** Grant every permission automatically when Shizuku or root is available. */
    var autoGrant by mutableStateOf(sp.getBoolean("auto_grant", true))
        private set
    var language by mutableStateOf(sp.getString("language", "en-US") ?: "en-US")
    /** True once the person has picked a speech language themselves; until then the detected default is used. */
    var langChosen by mutableStateOf(sp.getBoolean("lang_chosen", false))
    /** Languages the phone's speech recogniser says it supports. */
    var supportedLangs by mutableStateOf((sp.getStringSet("asr_langs", emptySet()) ?: emptySet()).toList().sorted())
        private set
    var theme by mutableIntStateOf(sp.getInt("theme", ThemeMode.DARK))
        private set
    /** Offline recogniser listens for two languages at once (e.g. English and Hindi) and keeps the better result. */
    /** Which gender the assistant speaks as in replies: auto (from the voice), female or male. */
    var voiceGender by mutableStateOf(sp.getString("voice_gender", "auto") ?: "auto")
        private set
    /** Send a screenshot to the AI when a request is about what is on screen. */
    var aiVision by mutableStateOf(sp.getBoolean("ai_vision", true))
        private set
    var bilingual by mutableStateOf(sp.getBoolean("bilingual", true))
        private set
    /** Speaking while the assistant talks stops it so you can give a new command. */
    var interrupt by mutableStateOf(sp.getBoolean("speak_interrupt", true))
        private set
    /** Keep Nothing Assistant as the default digital assistant after a restart (needs Shizuku or root). */
    var keepAssistant by mutableStateOf(sp.getBoolean("keep_assistant", true))
        private set
    var wakeWord by mutableStateOf(sp.getBoolean("wake_word", false))
        private set
    var wakePhrase by mutableStateOf(sp.getString("wake_phrase", "hey nothing") ?: "hey nothing")
        private set
    /** Different ways the recogniser heard the phrase while training. */
    var wakeVariants by mutableStateOf((sp.getStringSet("wake_variants", emptySet()) ?: emptySet()).toList().sorted())
        private set
    var wakeSens by mutableStateOf(sp.getFloat("wake_sens", 0.5f))
        private set

    /** Look: 0 plain Nothing, 1 liquid glass, 2 minimal liquid glass. */
    var glassStyle by mutableIntStateOf(sp.getInt("glass_style", 0))
        private set
    /** The listening blob (dot-matrix, Siri-style) instead of the plain ring. */
    var blob by mutableStateOf(sp.getBoolean("blob", true))
        private set
    /** Words appear one by one as the assistant speaks. */
    var typing by mutableStateOf(sp.getBoolean("typing", true))
        private set
    /** red, amber, mint, blue, white */
    var accent by mutableStateOf(sp.getString("accent", "red") ?: "red")
        private set

    // Voice replies and conversation
    var speakReplies by mutableStateOf(sp.getBoolean("speak_replies", true))
        private set
    var followUp by mutableStateOf(sp.getBoolean("follow_up", true))
        private set
    /** After each task, keep the window open and listen for the next one until you say ok, thanks or stop. */
    var stayOpen by mutableStateOf(sp.getBoolean("stay_open", true))
        private set

    /** Package names of the apps used for "play a song" / "play a video" when no app is named. Empty = none chosen. */
    /** "auto" uses Shizuku or root when available, "off" never does, "shizuku" / "root" force one. */
    var privMode by mutableStateOf(sp.getString("priv_mode", "auto") ?: "auto")
        private set
    var musicApp by mutableStateOf(sp.getString("music_app", "") ?: "")
        private set
    var videoApp by mutableStateOf(sp.getString("video_app", "") ?: "")
        private set

    // Optional AI chat (bring your own key)
    /** Providers in priority order. Edited from the AI chat page. */
    val providers = mutableStateListOf<Provider>().also { it.addAll(loadProviders()) }

    var aiPersona by mutableStateOf(sp.getString("ai_persona", PersonaPresets.DEFAULT) ?: PersonaPresets.DEFAULT)
        private set

    // Offline neural voice
    var voiceEngine by mutableStateOf(sp.getString("voice_engine", "neural") ?: "neural")
        private set
    var voiceModelId by mutableStateOf(sp.getString("voice_model", "supertonic") ?: "supertonic")
        private set
    var voiceSid by mutableIntStateOf(sp.getInt("voice_sid", 0))
        private set
    var voiceSpeed by mutableStateOf(sp.getFloat("voice_speed", 1.15f))
        private set
    /** Name of the phone's own voice to use ("" = automatic, by language). */
    var deviceVoice by mutableStateOf(sp.getString("device_voice", "") ?: "")
        private set
    @JvmName("applyDeviceVoice")
    fun setDeviceVoice(name: String) { deviceVoice = name; sp.edit().putString("device_voice", name).apply() }

    // AI voice (cloud text-to-speech), off by default
    var aiVoiceEnabled by mutableStateOf(sp.getBoolean("ai_voice_enabled", false))
        private set
    val voiceProviders = mutableStateListOf<Provider>().also { it.addAll(loadVoiceProviders()) }

    // What the assistant calls you (learned from "my name is ...")
    var userName by mutableStateOf(sp.getString("user_name", "") ?: "")
        private set

    // Master switch for AI chat
    var aiEnabled by mutableStateOf(sp.getBoolean("ai_enabled", false))
        private set

    // AI thinking mode (off = faster replies)
    var aiThinking by mutableStateOf(sp.getBoolean("ai_thinking", false))
        private set

    @JvmName("applyVoiceEngine")

    fun setVoiceEngine(v: String) { voiceEngine = v; sp.edit().putString("voice_engine", v).apply() }
    @JvmName("applyVoiceSid")
    fun setVoiceSid(v: Int) { voiceSid = v; sp.edit().putInt("voice_sid", v).apply() }
    @JvmName("applyVoiceSpeed")
    fun setVoiceSpeed(v: Float) { voiceSpeed = v; sp.edit().putFloat("voice_speed", v).apply() }
    @JvmName("applyVoiceModel")
    fun setVoiceModel(id: String, sid: Int) {
        voiceModelId = id
        voiceSid = sid
        sp.edit().putString("voice_model", id).putInt("voice_sid", sid).apply()
    }
    @JvmName("applyAiVoiceEnabled")
    fun setAiVoiceEnabled(v: Boolean) { aiVoiceEnabled = v; sp.edit().putBoolean("ai_voice_enabled", v).apply() }

    private fun loadVoiceProviders(): List<Provider> {
        val raw = sp.getString("ai_voice_providers", null)
        if (raw != null) {
            val out = ArrayList<Provider>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(Provider(o.getString("id"), o.optString("name"), o.optString("base"), o.optString("model"), o.optString("key"), o.optBoolean("locked"), o.optBoolean("custom"), o.optString("extra")))
                }
            }
            // Add any provider that was introduced after the list was first saved (for example Google).
            VoicePresets.all.forEach { pr -> if (out.none { it.id == pr.id }) out.add(Provider(pr.id, pr.name, pr.baseUrl, pr.model, "", extra = pr.voice)) }
            if (out.isNotEmpty()) return out
        }
        return VoicePresets.all.map { Provider(it.id, it.name, it.baseUrl, it.model, "", extra = it.voice) }
    }

    private fun persistVoiceProviders() {
        val arr = JSONArray()
        voiceProviders.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.baseUrl).put("model", it.model).put("key", it.key).put("locked", it.locked).put("custom", it.custom).put("extra", it.extra))
        }
        sp.edit().putString("ai_voice_providers", arr.toString()).apply()
    }

    fun updateVoiceProvider(id: String, change: (Provider) -> Provider) {
        val i = voiceProviders.indexOfFirst { it.id == id }
        if (i < 0) return
        voiceProviders[i] = change(voiceProviders[i])
        persistVoiceProviders()
    }

    /** Puts a provider first in the voice list, makes sure it has a voice, and turns AI voice on. */
    fun preferVoiceProvider(id: String) {
        val i = voiceProviders.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = voiceProviders.removeAt(i)
        voiceProviders.add(0, p)
        persistVoiceProviders()
        setAiVoiceEnabled(true)
    }

    /** Once per install: Kilo (free AI) and Edge (free voice) are switched on so nothing has to be enabled by hand. Providers the user already set up stay first. */
    fun applyDefaultsOnce() {
        if (sp.getBoolean("defaults_v2", false)) return
        sp.edit().putBoolean("defaults_v2", true).apply()
        if (providers.none { it.ready && !it.id.equals("kilo") }) preferProvider("kilo") else setAiEnabled(true)
        if (voiceProviders.none { it.ready && it.id != "v_edge" }) preferVoiceProvider("v_edge") else setAiVoiceEnabled(true)
    }

    /** Puts an AI chat provider first in the list and turns AI chat on. */
    fun preferProvider(id: String) {
        val i = providers.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = providers.removeAt(i)
        providers.add(0, p)
        persistProviders()
        setAiEnabled(true)
    }

    fun moveVoiceProvider(id: String, delta: Int) {
        val i = voiceProviders.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= voiceProviders.size) return
        val p = voiceProviders.removeAt(i)
        voiceProviders.add(j, p)
        persistVoiceProviders()
    }

    fun addCustomVoiceProvider(name: String): String {
        val id = "vcustom_" + System.currentTimeMillis()
        voiceProviders.add(Provider(id, name.trim().uppercase().ifBlank { "CUSTOM" }, "", "", "", custom = true))
        persistVoiceProviders()
        return id
    }

    fun removeVoiceProvider(id: String) {
        voiceProviders.removeAll { it.id == id && it.custom }
        persistVoiceProviders()
    }

    @JvmName("applyUserName")
    fun setUserName(v: String) { userName = v; sp.edit().putString("user_name", v).apply() }

    @JvmName("applyAiEnabled")
    fun setAiEnabled(v: Boolean) { aiEnabled = v; sp.edit().putBoolean("ai_enabled", v).apply() }

    @JvmName("applyAiThinking")
    fun setAiThinking(v: Boolean) { aiThinking = v; sp.edit().putBoolean("ai_thinking", v).apply() }

    @JvmName("applyAiPersona")

    fun setAiPersona(v: String) { aiPersona = v; sp.edit().putString("ai_persona", v).apply() }

    @JvmName("applyHaptics")

    fun setHaptics(v: Boolean) { haptics = v; sp.edit().putBoolean("haptics", v).apply() }
    @JvmName("applyAnimations")
    fun setAnimations(v: Boolean) { animations = v; sp.edit().putBoolean("animations", v).apply() }
    @JvmName("applyBatterySaver")
    fun setBatterySaver(v: Boolean) { batterySaver = v; sp.edit().putBoolean("battery_saver", v).apply() }
    @JvmName("applyBubble")
    fun setBubble(v: Boolean) { bubble = v; sp.edit().putBoolean("bubble", v).apply() }
    @JvmName("applyAsrEngine")
    fun setAsrEngine(v: String) { asrEngine = v; sp.edit().putString("asr_engine", v).apply() }
    @JvmName("applyAutoGrant")
    fun setAutoGrant(v: Boolean) { autoGrant = v; sp.edit().putBoolean("auto_grant", v).apply() }
    @JvmName("applyPreferOffline")
    fun setPreferOffline(v: Boolean) { preferOffline = v; sp.edit().putBoolean("prefer_offline", v).apply() }
    @JvmName("applyLanguage")
    fun setLanguage(v: String) { language = v; langChosen = true; sp.edit().putString("language", v).putBoolean("lang_chosen", true).apply() }
    @JvmName("applyLanguageAuto")
    fun setLanguageAuto(v: String) { language = v; sp.edit().putString("language", v).apply() }
    @JvmName("applySupportedLangs")
    fun setSupportedLangs(v: List<String>) { supportedLangs = v.sorted(); sp.edit().putStringSet("asr_langs", v.toSet()).apply() }
    @JvmName("applyVoiceGender")
    fun setVoiceGender(v: String) { voiceGender = v; sp.edit().putString("voice_gender", v).apply() }
    @JvmName("applyAiVision")
    fun setAiVision(v: Boolean) { aiVision = v; sp.edit().putBoolean("ai_vision", v).apply() }
    @JvmName("applyBilingual")
    fun setBilingual(v: Boolean) { bilingual = v; sp.edit().putBoolean("bilingual", v).apply() }
    @JvmName("applyInterrupt")
    fun setInterrupt(v: Boolean) { interrupt = v; sp.edit().putBoolean("speak_interrupt", v).apply() }
    @JvmName("applyKeepAssistant")
    fun setKeepAssistant(v: Boolean) { keepAssistant = v; sp.edit().putBoolean("keep_assistant", v).apply() }
    @JvmName("applyWakeWord")
    fun setWakeWord(v: Boolean) { wakeWord = v; sp.edit().putBoolean("wake_word", v).apply() }
    @JvmName("applyWakePhrase")
    fun setWakePhrase(v: String) { wakePhrase = v.trim().lowercase(); wakeVariants = emptyList(); sp.edit().putString("wake_phrase", wakePhrase).putStringSet("wake_variants", emptySet()).apply() }
    @JvmName("applyWakeVariants")
    fun setWakeVariants(v: List<String>) { wakeVariants = v.distinct().sorted(); sp.edit().putStringSet("wake_variants", wakeVariants.toSet()).apply() }
    @JvmName("applyWakeSens")
    fun setWakeSens(v: Float) { wakeSens = v; sp.edit().putFloat("wake_sens", v).apply() }
    @JvmName("applyTheme")
    fun setTheme(v: Int) { theme = v; sp.edit().putInt("theme", v).apply() }
    @JvmName("applyGlassStyle")
    fun setGlassStyle(v: Int) { glassStyle = v; sp.edit().putInt("glass_style", v).apply() }
    @JvmName("applyBlob")
    fun setBlob(v: Boolean) { blob = v; sp.edit().putBoolean("blob", v).apply() }
    @JvmName("applyTyping")
    fun setTyping(v: Boolean) { typing = v; sp.edit().putBoolean("typing", v).apply() }
    @JvmName("applyAccent")
    fun setAccent(v: String) { accent = v; sp.edit().putString("accent", v).apply() }
    @JvmName("applySpeakReplies")
    fun setSpeakReplies(v: Boolean) { speakReplies = v; sp.edit().putBoolean("speak_replies", v).apply() }
    @JvmName("applyStayOpen")
    fun setStayOpen(v: Boolean) { stayOpen = v; sp.edit().putBoolean("stay_open", v).apply() }
    @JvmName("applyPrivMode")
    fun setPrivMode(v: String) { privMode = v; sp.edit().putString("priv_mode", v).apply() }
    @JvmName("applyMusicApp")
    fun setMusicApp(v: String) { musicApp = v; sp.edit().putString("music_app", v).apply() }
    @JvmName("applyVideoApp")
    fun setVideoApp(v: String) { videoApp = v; sp.edit().putString("video_app", v).apply() }
    @JvmName("applyFollowUp")
    fun setFollowUp(v: Boolean) { followUp = v; sp.edit().putBoolean("follow_up", v).apply() }

    private fun loadProviders(): List<Provider> {
        val raw = sp.getString("ai_providers_v2", null)
        if (raw != null) {
            val out = ArrayList<Provider>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Provider(
                            o.getString("id"), o.optString("name"), o.optString("base"), o.optString("model"),
                            o.optString("key"), o.optBoolean("locked"), o.optBoolean("custom")
                        )
                    )
                }
            }
            if (out.isNotEmpty()) {
                // Free AI added in a later version: offer it (at the end) to people who already had a saved list.
                if (out.none { it.id == "kilo" }) AiPresets.all.firstOrNull { it.id == "kilo" }?.let { out.add(Provider(it.id, it.name, it.baseUrl, it.model, "")) }
                return out
            }
        }
        // First run of this version: bring over whatever was set up before.
        val oldSel = sp.getString("ai_provider", "gemini") ?: "gemini"
        val list = AiPresets.all.filter { it.id != "custom" }.map { pr ->
            val sel = pr.id == oldSel
            Provider(
                pr.id, pr.name,
                if (sel) sp.getString("ai_base", pr.baseUrl) ?: pr.baseUrl else pr.baseUrl,
                if (sel) sp.getString("ai_model", pr.model) ?: pr.model else pr.model,
                sp.getString("ai_key_${pr.id}", "") ?: ""
            )
        }.toMutableList()
        if (oldSel == "custom" || !sp.getString("ai_key_custom", "").isNullOrBlank()) {
            list.add(
                Provider(
                    "custom_old", "CUSTOM", sp.getString("ai_base", "") ?: "", sp.getString("ai_model", "") ?: "",
                    sp.getString("ai_key_custom", "") ?: "", custom = true
                )
            )
        }
        val first = list.indexOfFirst { it.id == oldSel || (oldSel == "custom" && it.id == "custom_old") }
        if (first > 0) list.add(0, list.removeAt(first))
        return list
    }

    private fun persistProviders() {
        val arr = JSONArray()
        providers.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("base", it.baseUrl).put("model", it.model)
                    .put("key", it.key).put("locked", it.locked).put("custom", it.custom)
            )
        }
        sp.edit().putString("ai_providers_v2", arr.toString()).apply()
    }

    fun updateProvider(id: String, change: (Provider) -> Provider) {
        val i = providers.indexOfFirst { it.id == id }
        if (i < 0) return
        providers[i] = change(providers[i])
        persistProviders()
    }

    /** Moves a provider earlier (-1) or later (+1) in the order they are tried. */
    fun moveProvider(id: String, delta: Int) {
        val i = providers.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= providers.size) return
        val p = providers.removeAt(i)
        providers.add(j, p)
        persistProviders()
    }

    fun addCustomProvider(name: String): String {
        val id = "custom_" + System.currentTimeMillis()
        providers.add(Provider(id, name.trim().uppercase().ifBlank { "CUSTOM" }, "", "", "", custom = true))
        persistProviders()
        return id
    }

    fun removeProvider(id: String) {
        providers.removeAll { it.id == id && it.custom }
        persistProviders()
    }

    // Bubble position is not UI state, so it is read and written directly.
    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) { sp.edit().putInt("bubble_x", v).apply() }
    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) { sp.edit().putInt("bubble_y", v).apply() }

    /** Read without needing a Settings instance (used from BootReceiver). */
    companion object {
        fun bubbleEnabled(context: Context): Boolean =
            context.getSharedPreferences("nothing_settings", Context.MODE_PRIVATE).getBoolean("bubble", false)
    }
}
