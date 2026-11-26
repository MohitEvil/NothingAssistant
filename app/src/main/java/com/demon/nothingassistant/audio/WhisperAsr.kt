package com.demon.nothingassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.demon.nothingassistant.debug.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Speech to text through Whisper (Groq's free hosted model, or any OpenAI-compatible /audio/transcriptions address).
 * Whisper is far more accurate than the phone's recogniser for accents, mixed Hindi and English, and names, and it answers in well under a second.
 * It records the sentence itself, finds the end by silence, and sends one small WAV. All calls to start/finish/cancel are made on the main thread.
 */
class WhisperAsr {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile private var finishNow = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cancel() { gen++; finishNow = false }
    fun finish() { finishNow = true }

    private fun post(my: Int, f: () -> Unit) { main.post { if (my == gen) f() } }

    /** [onHardFail] runs (instead of an error) when Whisper itself can't be used, so the caller can fall back to another recogniser. */
    fun start(key: String, model: String, baseUrl: String, vocabulary: String, l: SpeechEngine.Listener, onHardFail: (String) -> Unit) {
        val my = ++gen
        finishNow = false
        Thread {
            runCatching { listen(key, model, baseUrl, vocabulary, l, my, onHardFail) }.onFailure {
                AppLog.e("whisper", "failed", it)
                post(my) { onHardFail(it.message ?: "Whisper failed") }
            }
        }.apply { isDaemon = true }.start()
    }

    @SuppressLint("MissingPermission")
    private fun listen(key: String, model: String, baseUrl: String, vocab: String, l: SpeechEngine.Listener, my: Int, onHardFail: (String) -> Unit) {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); post(my) { l.onError(9) }; return }

        val buf = ShortArray(800) // 50 ms
        val all = ByteArrayOutputStream()
        val preRoll = ArrayDeque<ShortArray>()
        var floor = 0.006f
        var calibrated = 0
        var speechMs = 0
        var silenceMs = 0
        var spoke = false
        val started = System.currentTimeMillis()
        try {
            ar.startRecording()
            while (my == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                VoiceBands.pushPcm(buf, n, sr)
                val lvl = ((20 * log10(rms + 1e-6f) + 48f) / 36f).coerceIn(0f, 1f)
                post(my) { l.onRms(lvl * 12f - 2f) }

                // The first quarter second sets the room's noise level; speech is clearly above it.
                if (calibrated < 5) { floor = maxOf(floor * 0.6f + rms * 0.4f, 0.004f); calibrated++ }
                val loud = rms > maxOf(0.016f, floor * 3.2f)
                val chunk = buf.copyOf(n)
                if (!spoke) {
                    preRoll.addLast(chunk); if (preRoll.size > 8) preRoll.removeFirst()
                    if (loud && calibrated >= 5) {
                        spoke = true; speechMs = 0; silenceMs = 0
                        preRoll.forEach { writePcm(all, it) }
                        preRoll.clear()
                    }
                } else {
                    writePcm(all, chunk)
                    if (loud) { speechMs += 50; silenceMs = 0 } else silenceMs += 50
                }
                val now = System.currentTimeMillis() - started
                if (spoke && silenceMs >= 850 && speechMs >= 250) break
                if (spoke && finishNow) break
                if (!spoke && finishNow) break
                if (!spoke && now > 7000) break
                if (now > 20000) break
            }
        } finally {
            runCatching { ar.stop() }
            ar.release()
        }
        if (my != gen) return
        val pcm = all.toByteArray()
        if (!spoke || pcm.size < 8000) { post(my) { l.onError(7) }; return }

        post(my) { l.onPartial("…") }
        val wav = wav(pcm, sr)
        val result = transcribe(key, model, baseUrl, vocab, wav)
        if (my != gen) return
        result.fold(
            onSuccess = { text ->
                AppLog.d("whisper", "heard='$text' (${pcm.size / 32} ms of audio)")
                post(my) { if (text.isBlank()) l.onError(7) else l.onFinal(text) }
            },
            onFailure = { e ->
                AppLog.d("whisper", "request failed: ${e.message}")
                post(my) { onHardFail(e.message ?: "Whisper failed") }
            }
        )
    }

    private fun transcribe(key: String, model: String, baseUrl: String, vocab: String, wav: ByteArray): Result<String> = runCatching {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (vocab.isNotBlank()) addFormDataPart("prompt", vocab.take(400)) }
            .build()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val why = runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw RuntimeException(when (r.code) { 401 -> "The Whisper key was rejected."; 429 -> "Whisper is rate limited."; else -> "Whisper error ${r.code}: ${why.take(80)}" })
            }
            JSONObject(txt).optString("text", "").trim()
        }
    }

    private fun writePcm(out: ByteArrayOutputStream, s: ShortArray) {
        val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { bb.putShort(it) }
        out.write(bb.array())
    }

    private fun wav(pcm: ByteArray, sr: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    companion object {
        const val GROQ = "https://api.groq.com/openai/v1"
    }
}
