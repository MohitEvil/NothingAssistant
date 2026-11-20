package com.demon.nothingassistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.demon.nothingassistant.debug.AppLog
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.sqrt

/** The models the on-device recogniser can use. Downloaded once, then fully offline. */
enum class AsrModel(val id: String, val title: String, val size: String, val url: String, val folder: String) {
    LIVE("live", "Live speech (streaming)", "about 45 MB",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2", "zipformer-live"),
    ACCURATE("accurate", "Accurate speech (Moonshine)", "about 30 MB",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2", "moonshine-tiny"),
    VAD("vad", "Voice activity detector", "about 2 MB",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", "silero-vad")
}

/**
 * Our own speech recognition for phones whose ROM has no Google recogniser. sherpa-onnx is reached by reflection,
 * like the voice, so the app still builds and runs without the library.
 *
 *  LIVE:      words appear while you speak (streaming Zipformer), the end of speech is found by its own endpoint detector.
 *  ACCURATE:  waits for you to finish (Silero VAD), then Moonshine transcribes the whole sentence.
 *  TWOPASS:   live words while you speak, then Moonshine re-reads the audio for the final text.
 */
class SherpaAsr(private val context: Context) {
    enum class Mode { LIVE, ACCURATE, TWOPASS }

    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.filesDir, "asr").apply { mkdirs() }
    val states = mutableStateMapOf<String, InstallState>()

    private var online: Any? = null
    private var offline: Any? = null
    private var vadModelPath: String? = null

    @Volatile private var gen = 0
    @Volatile private var finishNow = false
    private var worker: Thread? = null

    init {
        AsrModel.values().forEach { states[it.id] = InstallState(if (installed(it)) InstallPhase.INSTALLED else InstallPhase.NOT_INSTALLED) }
    }

    val libraryPresent: Boolean by lazy { runCatching { Class.forName(PKG + "OnlineRecognizer"); true }.getOrDefault(false) }

    fun dir(m: AsrModel) = File(root, m.folder)
    private fun marker(m: AsrModel) = File(dir(m), ".ok")
    fun installed(m: AsrModel) = marker(m).exists()

    fun ready(mode: Mode): Boolean = libraryPresent && when (mode) {
        Mode.LIVE -> installed(AsrModel.LIVE)
        Mode.ACCURATE -> installed(AsrModel.ACCURATE)
        Mode.TWOPASS -> installed(AsrModel.LIVE)
    }

    /** The best mode this phone can run right now, or null when nothing is downloaded. */
    fun bestAvailable(): Mode? = when {
        ready(Mode.TWOPASS) && installed(AsrModel.ACCURATE) -> Mode.TWOPASS
        ready(Mode.LIVE) -> Mode.LIVE
        ready(Mode.ACCURATE) -> Mode.ACCURATE
        else -> null
    }

    // ---- Downloading ----

    fun download(m: AsrModel) {
        val cur = states[m.id]?.phase
        if (cur == InstallPhase.DOWNLOADING || cur == InstallPhase.INSTALLING) return
        states[m.id] = InstallState(InstallPhase.DOWNLOADING, 0f)
        Thread {
            try {
                val d = dir(m)
                d.deleteRecursively(); d.mkdirs()
                val part = File(root, m.folder + ".part")
                fetch(m.url, part) { states[m.id] = InstallState(InstallPhase.DOWNLOADING, it) }
                states[m.id] = InstallState(InstallPhase.INSTALLING, 1f)
                if (m.url.endsWith(".tar.bz2")) extract(part, d) else part.copyTo(File(d, File(m.url).name), true)
                part.delete()
                check(m)
                marker(m).writeText("ok")
                states[m.id] = InstallState(InstallPhase.INSTALLED)
                AppLog.d("asr", "${m.id} installed")
            } catch (e: Exception) {
                AppLog.e("asr", "${m.id} download failed", e)
                dir(m).deleteRecursively()
                states[m.id] = InstallState(InstallPhase.FAILED, 0f, when (e) {
                    is java.net.UnknownHostException -> "No internet. Connect once to download."
                    is java.net.SocketTimeoutException -> "The download timed out. Try again."
                    else -> e.message ?: "Download failed."
                })
            }
        }.start()
    }

    fun delete(m: AsrModel) {
        dir(m).deleteRecursively()
        if (m == AsrModel.LIVE) online = null
        if (m == AsrModel.ACCURATE) offline = null
        states[m.id] = InstallState(InstallPhase.NOT_INSTALLED)
    }

    private fun check(m: AsrModel) {
        val miss = when (m) {
            AsrModel.LIVE -> if (find(m, "encoder") == null || find(m, "decoder") == null || find(m, "joiner") == null || find(m, "tokens") == null) "model files" else null
            AsrModel.ACCURATE -> if (find(m, "encode") == null || find(m, "tokens") == null) "model files" else null
            AsrModel.VAD -> if (find(m, "silero") == null) "vad file" else null
        }
        if (miss != null) throw IOException("The download was incomplete ($miss). Try again.")
    }

    /** Finds a model file by the start of its name, preferring the int8 build. Folder layout differs between releases. */
    private fun find(m: AsrModel, prefix: String): File? {
        val all = dir(m).walkTopDown().filter { it.isFile }.toList()
        val ext = if (prefix == "tokens") ".txt" else ".onnx"
        val c = all.filter { it.name.startsWith(prefix) && it.name.endsWith(ext) }
        return c.firstOrNull { it.name.contains("int8") } ?: c.firstOrNull()
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

    private fun extract(archive: File, into: File) {
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16), true)).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val dest = File(into, e.name).canonicalFile
                if (!dest.path.startsWith(base)) continue
                if (e.isDirectory) dest.mkdirs() else { dest.parentFile?.mkdirs(); dest.outputStream().use { tar.copyTo(it, 1 shl 16) } }
            }
        }
    }

    // ---- Listening ----

    fun start(mode: Mode, l: SpeechEngine.Listener) {
        cancel()
        val my = ++gen
        finishNow = false
        val t = Thread { runCatching { listen(mode, l, my) }.onFailure {
            AppLog.e("asr", "listen failed", it)
            post(my) { l.onError(SpeechEngine.ERROR_ENGINE) }
        } }
        t.isDaemon = true
        worker = t
        t.start()
    }

    /** Stop capturing and transcribe what was heard so far. */
    fun finish() { finishNow = true }

    fun cancel() { gen++ ; finishNow = false }

    private fun post(my: Int, f: () -> Unit) { main.post { if (my == gen) f() } }

    @SuppressLint("MissingPermission")
    private fun listen(mode: Mode, l: SpeechEngine.Listener, my: Int) {
        val useOnline = mode != Mode.ACCURATE
        val useOffline = mode != Mode.LIVE
        val rec = if (useOnline) onlineEngine() else null
        val off = if (useOffline) offlineEngine() else null
        val stream = rec?.let { create(it) }
        val vad = if (mode == Mode.ACCURATE) runCatching { makeVad() }.getOrNull() else null

        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); post(my) { l.onError(9) }; return }

        val all = FloatArray(sr * 30)
        var count = 0
        val shorts = ShortArray(1600)
        var speechSeen = false
        var silentMs = 0
        var lastText = ""
        var elapsed = 0
        var noise = 0.01f
        val started = System.currentTimeMillis()
        var vadLeft = FloatArray(0)
        try {
            ar.startRecording()
            while (my == gen && !finishNow) {
                val n = ar.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                val f = FloatArray(n) { shorts[it] / 32768f }
                var sum = 0f
                for (v in f) sum += v * v
                val rms = sqrt(sum / n)
                val lvl = ((20 * log10(rms + 1e-6f) + 48f) / 36f).coerceIn(0f, 1f)
                post(my) { l.onRms(lvl * 12f - 2f) }
                if (count + n <= all.size) { System.arraycopy(f, 0, all, count, n); count += n }
                val ms = n * 1000 / sr
                elapsed += ms

                var voiced = rms > maxOf(0.015f, noise * 2.5f)
                if (!voiced) noise = noise * 0.95f + rms * 0.05f

                if (stream != null && rec != null) {
                    call(stream, "acceptWaveform", f, sr)
                    while (call(rec, "isReady", stream) as Boolean) call(rec, "decode", stream)
                    val text = textOf(call(rec, "getResult", stream)).lowercase().trim()
                    if (text.isNotEmpty()) { voiced = true; speechSeen = true }
                    if (text != lastText && text.isNotEmpty()) { lastText = text; post(my) { l.onPartial(text) } }
                    if (speechSeen && text.isNotEmpty() && (call(rec, "isEndpoint", stream) as Boolean)) break
                }
                if (vad != null) {
                    val buf = vadLeft + f
                    var i = 0
                    while (i + 512 <= buf.size) { call(vad, "acceptWaveform", buf.copyOfRange(i, i + 512)); i += 512 }
                    vadLeft = buf.copyOfRange(i, buf.size)
                    voiced = call(vad, "isSpeechDetected") as Boolean
                }
                if (voiced) { speechSeen = true; silentMs = 0 } else silentMs += ms
                // No stream endpoint (accurate mode, or the endpoint never fired): our own silence timer.
                if (speechSeen && silentMs >= (if (mode == Mode.LIVE) 1800 else 1000)) break
                if (!speechSeen && System.currentTimeMillis() - started > 8000) break
                if (count >= all.size) break
            }
        } finally {
            runCatching { ar.stop() }
            ar.release()
        }
        if (my != gen) { stream?.let { runCatching { call(it, "release") } }; vad?.let { runCatching { call(it, "release") } }; return }

        var text = lastText
        if (stream != null && rec != null) {
            runCatching {
                call(stream, "inputFinished")
                while (call(rec, "isReady", stream) as Boolean) call(rec, "decode", stream)
                val t = textOf(call(rec, "getResult", stream)).lowercase().trim()
                if (t.isNotEmpty()) text = t
            }
            runCatching { call(stream, "release") }
        }
        vad?.let { runCatching { call(it, "release") } }
        if (off != null && count > sr / 5 && speechSeen) {
            runCatching {
                val os = call(off, "createStream")!!
                call(os, "acceptWaveform", all.copyOf(count), sr)
                call(off, "decode", os)
                val t = textOf(call(off, "getResult", os)).trim()
                runCatching { call(os, "release") }
                if (t.isNotEmpty()) text = t
            }.onFailure { AppLog.e("asr", "moonshine pass failed", it) }
        }
        val out = text.trim()
        post(my) { if (out.isBlank()) l.onError(7) else l.onFinal(out) }
    }

    // ---- Engines (by reflection) ----

    private fun onlineEngine(): Any {
        online?.let { return it }
        val d = AsrModel.LIVE
        val tr = bean("OnlineTransducerModelConfig").also {
            set(it, "encoder", find(d, "encoder")!!.absolutePath); set(it, "decoder", find(d, "decoder")!!.absolutePath); set(it, "joiner", find(d, "joiner")!!.absolutePath)
        }
        val mc = bean("OnlineModelConfig").also {
            set(it, "transducer", tr); set(it, "tokens", find(d, "tokens")!!.absolutePath)
            set(it, "numThreads", 2); trySet(it, "modelType", "zipformer")
        }
        val cfg = bean("OnlineRecognizerConfig").also {
            set(it, "modelConfig", mc)
            trySet(it, "enableEndpoint", true)
            trySet(it, "decodingMethod", "greedy_search")
            trySet(it, "featConfig", bean("FeatureConfig").also { f -> trySet(f, "sampleRate", 16000); trySet(f, "featureDim", 80) })
            runCatching {
                val ec = bean("EndpointConfig")
                fun rule(nonSilence: Boolean, trail: Float, minUtt: Float) = bean("EndpointRule").also { r ->
                    trySet(r, "mustContainNonSilence", nonSilence); trySet(r, "minTrailingSilence", trail); trySet(r, "minUtteranceLength", minUtt)
                }
                trySet(ec, "rule1", rule(false, 2.4f, 0f)); trySet(ec, "rule2", rule(true, 1.1f, 0f)); trySet(ec, "rule3", rule(false, 0f, 20f))
                set(it, "endpointConfig", ec)
            }
        }
        val e = construct(cls("OnlineRecognizer"), cfg, "OnlineRecognizerConfig")
        online = e
        AppLog.d("asr", "streaming recogniser ready")
        return e
    }

    private fun offlineEngine(): Any {
        offline?.let { return it }
        val d = AsrModel.ACCURATE
        val moon = bean("OfflineMoonshineModelConfig").also {
            trySet(it, "preprocessor", find(d, "preprocess")?.absolutePath ?: "")
            trySet(it, "encoder", find(d, "encode")!!.absolutePath)
            trySet(it, "uncachedDecoder", find(d, "uncached_decode")?.absolutePath ?: "")
            trySet(it, "cachedDecoder", find(d, "cached_decode")?.absolutePath ?: "")
            trySet(it, "mergedDecoder", find(d, "merged_decode")?.absolutePath ?: "")
        }
        val mc = bean("OfflineModelConfig").also {
            set(it, "moonshine", moon); set(it, "tokens", find(d, "tokens")!!.absolutePath); set(it, "numThreads", 2)
        }
        val cfg = bean("OfflineRecognizerConfig").also {
            set(it, "modelConfig", mc); trySet(it, "decodingMethod", "greedy_search")
            trySet(it, "featConfig", bean("FeatureConfig").also { f -> trySet(f, "sampleRate", 16000); trySet(f, "featureDim", 80) })
        }
        val e = construct(cls("OfflineRecognizer"), cfg, "OfflineRecognizerConfig")
        offline = e
        AppLog.d("asr", "accurate recogniser ready")
        return e
    }

    private fun makeVad(): Any {
        val f = find(AsrModel.VAD, "silero") ?: throw IOException("No VAD model")
        val sv = bean("SileroVadModelConfig").also {
            set(it, "model", f.absolutePath); trySet(it, "threshold", 0.5f); trySet(it, "minSilenceDuration", 0.5f)
            trySet(it, "minSpeechDuration", 0.2f); trySet(it, "windowSize", 512)
        }
        val vc = bean("VadModelConfig").also { set(it, "sileroVadModelConfig", sv); trySet(it, "sampleRate", 16000); trySet(it, "numThreads", 1) }
        return construct(cls("Vad"), vc, "VadModelConfig")
    }

    private fun construct(c: Class<*>, cfg: Any, cfgName: String): Any {
        val two = c.constructors.firstOrNull { it.parameterTypes.size == 2 && it.parameterTypes[0] == AssetManager::class.java }
        return try {
            if (two != null) two.newInstance(null, cfg) else c.getConstructor(cls(cfgName)).newInstance(cfg)
        } catch (e: InvocationTargetException) { throw e.cause ?: e }
    }

    private fun create(rec: Any): Any {
        val m = rec.javaClass.methods.filter { it.name == "createStream" }.minByOrNull { it.parameterTypes.size }!!
        return if (m.parameterTypes.isEmpty()) m.invoke(rec)!! else m.invoke(rec, "")!!
    }

    private fun textOf(result: Any?): String = result?.let { runCatching { it.javaClass.getMethod("getText").invoke(it) as? String }.getOrNull() }.orEmpty()

    /** Calls a method by name and argument count; boxed ints and float arrays match their primitive parameters. */
    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodException(target.javaClass.simpleName + "." + name)
        return try { m.invoke(target, *args) } catch (e: InvocationTargetException) { throw e.cause ?: e }
    }

    private fun cls(name: String): Class<*> = Class.forName(PKG + name)
    private fun bean(name: String): Any = cls(name).getDeclaredConstructor().newInstance()
    private fun set(target: Any, prop: String, value: Any?) {
        val setter = "set" + prop.replaceFirstChar { it.uppercase() }
        val m = target.javaClass.methods.firstOrNull { it.name == setter && it.parameterTypes.size == 1 } ?: throw NoSuchMethodException(target.javaClass.simpleName + "." + setter)
        m.invoke(target, value)
    }
    private fun trySet(target: Any, prop: String, value: Any?) { runCatching { set(target, prop, value) } }

    companion object {
        private const val PKG = "com.k2fsa.sherpa.onnx."
        @Volatile private var instance: SherpaAsr? = null
        fun get(context: Context): SherpaAsr = instance ?: synchronized(this) { instance ?: SherpaAsr(context.applicationContext).also { instance = it } }
    }
}
