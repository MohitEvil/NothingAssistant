package com.demon.nothingassistant.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The live spectrum of the voice (yours while you speak, the assistant's while it answers), split into [N] bands from deep to high.
 * The blob and the training screens draw from it, so what you see follows the loudness AND the pitch of the sound.
 * Real data comes from audio we own (offline recogniser, our own playback, the training recorder). When the phone's own recogniser holds
 * the microphone, only its loudness is available, so [tick] shapes that into a voice-like spread instead.
 */
object VoiceBands {
    const val N = 10

    /** Smoothed 0..1 levels, deep voice first. Read inside draw blocks. */
    val levels = FloatArray(N)

    @Volatile private var realUntil = 0L
    private val re = FloatArray(512)
    private val im = FloatArray(512)
    private val win = FloatArray(512) { (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / 511)).toFloat() }
    private val edges = DoubleArray(N + 1) { 70.0 * Math.pow(6500.0 / 70.0, it.toDouble() / N) }
    private val profile = floatArrayOf(0.85f, 1.0f, 0.95f, 0.85f, 0.72f, 0.6f, 0.5f, 0.4f, 0.3f, 0.22f)

    fun real(): Boolean = System.currentTimeMillis() < realUntil

    /** Overall energy 0..1. */
    fun level(): Float { var s = 0f; for (v in levels) s += v; return (s / N * 1.7f).coerceIn(0f, 1f) }

    private fun apply(b: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        levels[b] += (c - levels[b]) * (if (c > levels[b]) 0.65f else 0.16f)
    }

    /** Raw 16-bit audio (a few hundred samples or more). */
    @Synchronized
    fun pushPcm(buf: ShortArray, n: Int, rate: Int) {
        if (n < 256) return
        val m = minOf(n, 512)
        for (i in 0 until 512) { re[i] = if (i < m) buf[i] / 32768f * win[i] else 0f; im[i] = 0f }
        fft(re, im)
        for (b in 0 until N) {
            val lo = (edges[b] * 512 / rate).toInt().coerceIn(1, 255)
            val hi = (edges[b + 1] * 512 / rate).toInt().coerceIn(lo, 255)
            var s = 0f
            for (k in lo..hi) s += re[k] * re[k] + im[k] * im[k]
            val mag = sqrt(s / (hi - lo + 1))
            val db = 20f * log10(mag + 1e-5f) + b * 1.8f // speech falls off with pitch; lift the top so it still moves
            apply(b, (db + 34f) / 46f)
        }
        realUntil = System.currentTimeMillis() + 350
    }

    /** Output of android.media.audiofx.Visualizer.getFft (re/im byte pairs). */
    @Synchronized
    fun pushFft(fft: ByteArray, rateHz: Int) {
        val bins = fft.size / 2
        if (bins < 16 || rateHz <= 0) return
        for (b in 0 until N) {
            val lo = (edges[b] * bins * 2 / rateHz).toInt().coerceIn(1, bins - 1)
            val hi = (edges[b + 1] * bins * 2 / rateHz).toInt().coerceIn(lo, bins - 1)
            var s = 0f
            for (k in lo..hi) { val r = fft[2 * k].toFloat(); val i = fft[2 * k + 1].toFloat(); s += r * r + i * i }
            val mag = sqrt(s / (hi - lo + 1))
            apply(b, (20f * log10(mag + 1f) + b * 1.5f) / 44f)
        }
        realUntil = System.currentTimeMillis() + 350
    }

    /** Once per drawn frame. Without real data the loudness [amp] is spread over the bands like a voice; without any sound everything settles. */
    fun tick(amp: Float, tSec: Float) {
        if (real()) return
        for (b in 0 until N) {
            val wob = 0.55f + 0.45f * sin(tSec * (2.3f + b * 0.9f) + b * 1.7f)
            apply(b, amp * profile[b] * wob)
        }
    }

    private var viz: android.media.audiofx.Visualizer? = null

    /** Follows what an AudioTrack / MediaPlayer is really playing (needs the microphone permission; silently skipped without it). */
    @Synchronized
    fun attach(session: Int) {
        detach()
        runCatching {
            val v = android.media.audiofx.Visualizer(session)
            v.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vz: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) { if (fft != null) pushFft(fft, samplingRate / 1000) }
            }, android.media.audiofx.Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            viz = v
        }
    }

    @Synchronized
    fun detach() {
        viz?.let { runCatching { it.enabled = false }; runCatching { it.release() } }
        viz = null
    }

    fun clear() { for (i in 0 until N) levels[i] = 0f; realUntil = 0L }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
