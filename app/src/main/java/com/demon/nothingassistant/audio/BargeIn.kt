package com.demon.nothingassistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Speak-to-interrupt. While the assistant talks, the microphone is watched (nothing is recorded or kept) and, when the
 * person starts speaking over the voice, [onVoice] fires so the assistant can stop and listen.
 *
 * The microphone is opened in "voice communication" mode, which asks the phone to cancel the assistant's own voice out of the
 * signal. What is left of the echo is measured during the first half second and the trigger sits well above it.
 */
class BargeIn(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    private var thread: Thread? = null

    fun start(onVoice: () -> Unit) {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val my = ++gen
        thread = Thread { runCatching { watch(my, onVoice) } }.apply { isDaemon = true; start() }
    }

    fun stop() {
        gen++
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(200) }
    }

    @SuppressLint("MissingPermission")
    private fun watch(my: Int, onVoice: () -> Unit) {
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return }
        val aec = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val buf = ShortArray(1600) // 100 ms
        var frame = 0
        var base = 0.01f
        var hot = 0
        try {
            ar.startRecording()
            while (my == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                frame++
                if (frame <= 6) { base = maxOf(base, rms); continue } // first 0.6 s: just learn how loud the echo is
                val trigger = maxOf(base * 2.4f, 0.04f)
                if (rms > trigger) {
                    hot++
                    if (hot >= 3) { // about 0.3 s of something clearly louder than the echo
                        main.post { if (my == gen) { gen++; onVoice() } }
                        return
                    }
                } else {
                    hot = 0
                    base = base * 0.97f + rms * 0.03f
                }
            }
        } finally {
            runCatching { ar.stop() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            ar.release()
        }
    }
}
