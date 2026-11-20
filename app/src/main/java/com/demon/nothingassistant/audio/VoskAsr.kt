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

/** Offline speech models (Vosk). Small ones, about 40 MB each, downloaded once. */
enum class VoskLang(val id: String, val tag: String, val title: String, val size: String, val url: String) {
    EN_IN("en-in", "en-IN", "English (India)", "about 36 MB", "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"),
    EN_US("en-us", "en-US", "English (US)", "about 40 MB", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
    HI("hi", "hi-IN", "Hindi", "about 42 MB", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip");

    companion object {
        fun forTag(tag: String): VoskLang = values().firstOrNull { it.tag.equals(tag, true) } ?: if (tag.startsWith("hi", true)) HI else EN_IN
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

    @Volatile private var gen = 0
    @Volatile private var finishNow = false

    init { VoskLang.values().forEach { states[it.id] = InstallState(if (installed(it)) InstallPhase.INSTALLED else InstallPhase.NOT_INSTALLED) } }

    private fun dir(l: VoskLang) = File(root, l.id)
    private fun marker(l: VoskLang) = File(dir(l), ".ok")
    fun installed(l: VoskLang) = marker(l).exists()
    fun anyInstalled() = VoskLang.values().any { installed(it) }

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
                marker(l).writeText("ok")
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

    fun start(languageTag: String, l: SpeechEngine.Listener) {
        cancel()
        val my = ++gen
        finishNow = false
        val lang = VoskLang.forTag(languageTag).let { if (installed(it)) it else VoskLang.values().firstOrNull { x -> installed(x) } ?: it }
        Thread { runCatching { listen(lang, l, my) }.onFailure {
            AppLog.e("vosk", "listen failed", it)
            post(my) { l.onError(SpeechEngine.ERROR_ENGINE) }
        } }.apply { isDaemon = true }.start()
    }

    fun finish() { finishNow = true }
    fun cancel() { gen++; finishNow = false }
    private fun post(my: Int, f: () -> Unit) { main.post { if (my == gen) f() } }

    private fun textOf(json: String, key: String) = runCatching { JSONObject(json).optString(key, "") }.getOrDefault("").trim()

    @SuppressLint("MissingPermission")
    private fun listen(lang: VoskLang, l: SpeechEngine.Listener, my: Int) {
        val rec = Recognizer(model(lang), 16000f)
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); rec.close(); post(my) { l.onError(9) }; return }
        val shorts = ShortArray(1600)
        var lastPartial = ""
        var final = ""
        var speech = false
        var agc = 0f
        val started = System.currentTimeMillis()
        try {
            ar.startRecording()
            while (my == gen && !finishNow) {
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

                if (rec.acceptWaveForm(shorts, n)) {
                    val t = textOf(rec.result, "text")
                    if (t.isNotEmpty()) { final = t; speech = true; break }
                } else {
                    val p = textOf(rec.partialResult, "partial")
                    if (p.isNotEmpty()) { speech = true; if (p != lastPartial) { lastPartial = p; post(my) { l.onPartial(p) } } }
                }
                val now = System.currentTimeMillis()
                if (!speech && now - started > 8000) break
                if (now - started > 25000) break
            }
        } finally {
            runCatching { ar.stop() }
            ar.release()
        }
        if (my != gen) { rec.close(); return }
        if (final.isEmpty()) final = textOf(rec.finalResult, "text").ifEmpty { lastPartial }
        rec.close()
        AppLog.d("vosk", "lang=${lang.id} heard='$final'")
        val out = final.trim()
        post(my) { if (out.isBlank()) l.onError(7) else l.onFinal(out) }
    }

    companion object {
        @Volatile private var instance: VoskAsr? = null
        fun get(context: Context): VoskAsr = instance ?: synchronized(this) { instance ?: VoskAsr(context.applicationContext).also { instance = it } }
    }
}
