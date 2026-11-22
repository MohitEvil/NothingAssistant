package com.demon.nothingassistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.demon.nothingassistant.debug.AppLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.log10
import kotlin.math.sqrt

/** One offline speech model (Vosk). Built-in ones plus any language from the online catalog. */
data class VoskLang(val id: String, val tag: String, val title: String, val size: String, val url: String) {
    /** Language part only: "en-IN" -> "en". */
    val base: String get() = tag.substringBefore('-').lowercase()

    companion object {
        val BUILTIN = listOf(
            VoskLang("en-in", "en-IN", "English (India)", "about 36 MB", "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"),
            VoskLang("en-us", "en-US", "English (US)", "about 40 MB", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            VoskLang("hi", "hi-IN", "Hindi", "about 42 MB", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip")
        )
        fun values(): List<VoskLang> = BUILTIN
    }
}

/**
 * The phone's own speech recogniser for ROMs without Google speech. Vosk is a mature offline engine with
 * Indian English and Hindi models; words appear while you speak and the end of the sentence is found by silence.
 */
class VoskAsr(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.filesDir, "vosk").apply { mkdirs() }
    val states = mutableStateMapOf<String, InstallState>()
    private val models = HashMap<String, Model>()
    private val prefs = context.getSharedPreferences("nothing_settings", Context.MODE_PRIVATE)

    /** The online list of languages (filled by [fetchCatalog]). */
    val catalog = androidx.compose.runtime.mutableStateListOf<VoskLang>()
    val catalogStatus = androidx.compose.runtime.mutableStateOf("")
    private val custom = androidx.compose.runtime.mutableStateListOf<VoskLang>()

    @Volatile private var gen = 0
    @Volatile private var finishNow = false

    init {
        loadCustom()
        (VoskLang.BUILTIN + custom).forEach { states[it.id] = InstallState(if (installed(it)) InstallPhase.INSTALLED else InstallPhase.NOT_INSTALLED) }
    }

    private fun loadCustom() {
        custom.clear()
        root.listFiles()?.filter { it.isDirectory && File(it, ".ok").exists() && File(it, "meta.json").exists() }?.forEach { d ->
            runCatching {
                val j = JSONObject(File(d, "meta.json").readText())
                if (VoskLang.BUILTIN.none { it.id == d.name }) custom.add(VoskLang(d.name, j.getString("tag"), j.getString("title"), j.optString("size"), j.optString("url")))
            }
        }
    }

    /** Every model that is downloaded on this phone (built-in or from the catalog). */
    fun installedModels(): List<VoskLang> = (VoskLang.BUILTIN + custom).filter { installed(it) }

    /** Reads the official list of small models and offers every language in it. */
    fun fetchCatalog() {
        catalogStatus.value = "LOADING…"
        Thread {
            try {
                val conn = URL("https://alphacephei.com/vosk/models/model-list.json").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 20_000
                val text = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                val arr = org.json.JSONArray(text)
                val byLang = LinkedHashMap<String, VoskLang>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val type = o.optString("type")
                    if (o.optString("obsolete") == "true" || (type != "small" && type != "big-lgraph")) continue
                    val lang = o.optString("lang"); val url = o.optString("url"); val name = o.optString("name")
                    if (lang.isBlank() || url.isBlank()) continue
                    if (VoskLang.BUILTIN.any { it.url == url }) continue
                    val tag = if (lang.contains('-')) lang.substringBefore('-') + "-" + lang.substringAfter('-').uppercase() else lang
                    val size = o.optString("size_text").ifBlank { "" }.let { if (it.isBlank()) "" else "about $it" }
                    val title = o.optString("lang_text").ifBlank { lang } + (if (type == "big-lgraph") " · more accurate" else "")
                    byLang[lang + "|" + type] = VoskLang(name.removePrefix("vosk-model-"), tag, title, size, url)
                }
                val list = byLang.values.sortedBy { it.title }
                main.post {
                    catalog.clear(); catalog.addAll(list)
                    list.forEach { if (states[it.id] == null) states[it.id] = InstallState(if (installed(it)) InstallPhase.INSTALLED else InstallPhase.NOT_INSTALLED) }
                    catalogStatus.value = if (list.isEmpty()) "NOTHING FOUND" else ""
                }
            } catch (e: Exception) {
                AppLog.e("vosk", "catalog failed", e)
                main.post { catalogStatus.value = "NO INTERNET. TAP REFRESH." }
            }
        }.start()
    }

    private fun dir(l: VoskLang) = File(root, l.id)
    private fun marker(l: VoskLang) = File(dir(l), ".ok")
    fun installed(l: VoskLang) = marker(l).exists()
    fun anyInstalled() = installedModels().isNotEmpty()

    /** The model folder inside the unpacked download (the one that holds "am"). */
    private fun modelRoot(l: VoskLang): File? = dir(l).walkTopDown().firstOrNull { it.isDirectory && File(it, "am").isDirectory }

    fun download(l: VoskLang) {
        val cur = states[l.id]?.phase
        if (cur == InstallPhase.DOWNLOADING || cur == InstallPhase.INSTALLING) return
        states[l.id] = InstallState(InstallPhase.DOWNLOADING, 0f)
        Thread {
            try {
                val d = dir(l); d.deleteRecursively(); d.mkdirs()
                val part = File(root, l.id + ".part")
                fetch(l.url, part) { states[l.id] = InstallState(InstallPhase.DOWNLOADING, it) }
                states[l.id] = InstallState(InstallPhase.INSTALLING, 1f)
                unzip(part, d)
                part.delete()
                if (modelRoot(l) == null) throw IOException("The download was incomplete. Try again.")
                File(d, "meta.json").writeText(JSONObject().put("tag", l.tag).put("title", l.title).put("size", l.size).put("url", l.url).toString())
                marker(l).writeText("ok")
                main.post { if (VoskLang.BUILTIN.none { it.id == l.id } && custom.none { it.id == l.id }) custom.add(l) }
                states[l.id] = InstallState(InstallPhase.INSTALLED)
                AppLog.d("vosk", "${l.id} installed")
            } catch (e: Exception) {
                AppLog.e("vosk", "${l.id} download failed", e)
                dir(l).deleteRecursively()
                states[l.id] = InstallState(InstallPhase.FAILED, 0f, when (e) {
                    is java.net.UnknownHostException -> "No internet. Connect once to download."
                    is java.net.SocketTimeoutException -> "The download timed out. Try again."
                    else -> e.message ?: "Download failed."
                })
            }
        }.start()
    }

    fun delete(l: VoskLang) {
        models.remove(l.id)?.let { runCatching { it.close() } }
        dir(l).deleteRecursively()
        custom.removeAll { it.id == l.id }
        states[l.id] = InstallState(InstallPhase.NOT_INSTALLED)
    }

    private fun fetch(url: String, dest: File, report: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("The server answered ${conn.responseCode}.")
            val total = conn.contentLengthLong
            var done = 0L; var last = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; report(if (total > 0) done.toFloat() / total else 0f) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The download was cut short. Try again.")
        } finally { conn.disconnect() }
    }

    private fun unzip(zip: File, into: File) {
        val base = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val dest = File(into, e.name).canonicalFile
                if (!dest.path.startsWith(base)) continue
                if (e.isDirectory) dest.mkdirs() else { dest.parentFile?.mkdirs(); dest.outputStream().use { z.copyTo(it, 1 shl 16) } }
            }
        }
    }

    private fun model(l: VoskLang): Model = models.getOrPut(l.id) {
        val r = modelRoot(l) ?: throw IOException("Model files missing")
        Model(r.absolutePath)
    }

    // ---- Listening ----

    /** The model for this language tag, or null. Exact tag first, then the same language (en-GB -> en-IN). */
    private fun pick(tag: String, from: List<VoskLang>): VoskLang? =
        from.firstOrNull { it.tag.equals(tag, true) } ?: from.firstOrNull { it.base == tag.substringBefore('-').lowercase() }

    /** A loaded model for the language (or any installed one), for the wake word. Null when none is downloaded. */
    fun modelFor(tag: String): Model? {
        val have = installedModels()
        val m = pick(tag, have) ?: pick("en", have) ?: have.firstOrNull() ?: return null
        return runCatching { model(m) }.getOrNull()
    }

    fun start(languageTag: String, l: SpeechEngine.Listener) {
        cancel()
        val my = ++gen
        finishNow = false
        val have = installedModels()
        val primary = pick(languageTag, have) ?: have.firstOrNull()
        if (primary == null) { l.onError(SpeechEngine.ERROR_NOMODEL); return }
        // Bilingual: also listen with a model of another language and keep whichever understood better.
        val second = if (prefs.getBoolean("bilingual", true)) have.firstOrNull { it.base != primary.base } else null
        val langs = listOfNotNull(primary, second)
        Thread { runCatching { listen(langs, l, my) }.onFailure {
            AppLog.e("vosk", "listen failed", it)
            post(my) { l.onError(SpeechEngine.ERROR_ENGINE) }
        } }.apply { isDaemon = true }.start()
    }

    fun finish() { finishNow = true }
    fun cancel() { gen++; finishNow = false }
    private fun post(my: Int, f: () -> Unit) { main.post { if (my == gen) f() } }

    private fun textOf(json: String, key: String) = runCatching { JSONObject(json).optString(key, "") }.getOrDefault("").trim()

    /** Text and how sure the model was (sum of word confidences). */
    private fun scored(json: String): Pair<String, Float> = runCatching {
        val o = JSONObject(json)
        val t = o.optString("text", "").trim()
        val arr = o.optJSONArray("result")
        var sc = 0f
        if (arr != null) for (i in 0 until arr.length()) sc += arr.getJSONObject(i).optDouble("conf", 0.5).toFloat()
        else if (t.isNotEmpty()) sc = t.split(' ').size * 0.5f
        t to sc
    }.getOrDefault("" to 0f)

    @SuppressLint("MissingPermission")
    private fun listen(langs: List<VoskLang>, l: SpeechEngine.Listener, my: Int) {
        val recs = langs.map { Recognizer(model(it), 16000f).also { r -> runCatching { r.setWords(true) } } }
        fun closeAll() = recs.forEach { runCatching { it.close() } }
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); closeAll(); post(my) { l.onError(9) }; return }
        val shorts = ShortArray(1600)
        var lastPartial = ""
        var speech = false
        var done = false
        val finals = arrayOfNulls<Pair<String, Float>>(recs.size)
        var agc = 0f
        val started = System.currentTimeMillis()
        try {
            ar.startRecording()
            while (my == gen && !finishNow && !done) {
                val n = ar.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) { val a = if (shorts[i] < 0) -shorts[i].toInt() else shorts[i].toInt(); if (a > peak) peak = a }
                val pk = peak / 32768f
                agc = maxOf(agc * 0.995f, pk)
                // Quiet microphones: lift the level so the model hears normal speech.
                val gain = if (agc in 0.001f..0.25f) (0.35f / agc).coerceAtMost(6f) else 1f
                if (gain > 1.1f) for (i in 0 until n) shorts[i] = (shorts[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                var sum = 0.0
                for (i in 0 until n) { val v = shorts[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                val lvl = ((20 * log10(rms + 1e-6f) + 48f) / 36f).coerceIn(0f, 1f)
                post(my) { l.onRms(lvl * 12f - 2f) }

                var partial = ""
                for ((i, r) in recs.withIndex()) {
                    if (r.acceptWaveForm(shorts, n)) {
                        val sc = scored(r.result)
                        if (sc.first.isNotEmpty()) { finals[i] = sc; speech = true; done = true }
                    } else { val p = textOf(r.partialResult, "partial"); if (p.length > partial.length) partial = p }
                }
                if (partial.isNotEmpty()) { speech = true; if (partial != lastPartial) { lastPartial = partial; post(my) { l.onPartial(partial) } } }
                val now = System.currentTimeMillis()
                if (!speech && now - started > 8000) break
                if (now - started > 25000) break
            }
        } finally {
            runCatching { ar.stop() }
            ar.release()
        }
        if (my != gen) { closeAll(); return }
        // The model that finished first returned its sentence via acceptWaveForm; finalResult gives every model's best.
        val best = recs.mapIndexed { i, r -> finals[i] ?: scored(r.finalResult) }.maxByOrNull { it.second }
        closeAll()
        val out = (best?.first.orEmpty().ifEmpty { lastPartial }).trim()
        AppLog.d("vosk", "langs=${langs.joinToString { it.id }} heard='$out'")
        post(my) { if (out.isBlank()) l.onError(7) else l.onFinal(out) }
    }

    companion object {
        @Volatile private var instance: VoskAsr? = null
        fun get(context: Context): VoskAsr = instance ?: synchronized(this) { instance ?: VoskAsr(context.applicationContext).also { instance = it } }
    }
}
