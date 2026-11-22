package com.demon.nothingassistant.audio

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Who is speaking. The wake-word fingerprint only knows WHAT was said; this knows WHOSE voice it is.
 * From every phrase and sentence you say in Voice Match it learns the average shape of your voice (the cepstrum, which follows the length
 * and shape of your vocal tract) and your pitch. Later, an utterance is accepted only if it lies where your own recordings lie, so
 * someone else saying the same words is turned away. Everything stays on this phone.
 */
object Voiceprint {
    private const val SR = WakeVoice.SR
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val NMEL = 26
    private const val NMEAN = 20
    private const val NSTD = 10
    const val PITCH = NMEAN + NSTD          // index of median pitch (semitones); +1 pitch spread
    const val D = PITCH + 2                 // embedding length; one more slot says whether a pitch was found

    private val dct: Array<FloatArray> by lazy {
        val k = sqrt(2.0 / NMEL).toFloat()
        Array(NMEAN + 1) { i -> FloatArray(NMEL) { j -> k * cos(PI * i * (j + 0.5) / NMEL).toFloat() } }
    }

    private val floors = FloatArray(D) { i -> when { i < NMEAN -> 0.55f; i < PITCH -> 0.35f; i == PITCH -> 1.3f; else -> 0.9f } }

    /** One utterance as a point in "voice space", or null when it is too short or has no clear speech. */
    fun embed(pcm: ShortArray): FloatArray? {
        if (pcm.size < SR / 2) return null
        val raw = FloatArray(pcm.size) { pcm[it] / 32768f }
        val x = FloatArray(raw.size)
        var prev = 0f
        for (i in raw.indices) { x[i] = raw[i] - 0.97f * prev; prev = raw[i] }
        val nFrames = (x.size - FRAME) / HOP + 1
        if (nFrames < 30) return null
        val energy = FloatArray(nFrames)
        val ceps = Array(nFrames) { FloatArray(NMEAN) }
        val re = FloatArray(NFFT); val im = FloatArray(NFFT)
        val lm = FloatArray(NMEL)
        for (f in 0 until nFrames) {
            java.util.Arrays.fill(re, 0f); java.util.Arrays.fill(im, 0f)
            var e = 0f
            for (i in 0 until FRAME) { val v = x[f * HOP + i] * WakeVoice.hamming[i]; re[i] = v; e += v * v }
            energy[f] = e
            WakeVoice.fft(re, im)
            for (m in 0 until NMEL) {
                var s = 0f
                val bank = WakeVoice.melBank[m]
                for (k in 0..NFFT / 2) if (bank[k] != 0f) s += bank[k] * (re[k] * re[k] + im[k] * im[k])
                lm[m] = ln(s + 1e-8f)
            }
            for (c in 1..NMEAN) { var s = 0f; for (m in 0 until NMEL) s += dct[c][m] * lm[m]; ceps[f][c - 1] = s }
        }
        val emax = energy.max()
        if (emax <= 1e-6f) return null
        // Only the clearly spoken frames describe a voice.
        val keep = (0 until nFrames).filter { energy[it] >= emax * 0.06f }
        if (keep.size < 25) return null
        val out = FloatArray(D)
        for (c in 0 until NMEAN) { var s = 0f; for (f in keep) s += ceps[f][c]; out[c] = s / keep.size }
        for (c in 0 until NSTD) {
            val mean = out[c]
            var v = 0f; for (f in keep) { val t = ceps[f][c] - mean; v += t * t }
            out[NMEAN + c] = sqrt(v / keep.size)
        }
        // Pitch: strongest repeat of the waveform between 70 and 400 Hz, on the louder frames.
        val loud = keep.filter { energy[it] >= emax * 0.2f }
        val semis = ArrayList<Float>()
        val win = 800
        var idx = 0
        for (f in loud) {
            if (idx++ % 2 != 0) continue
            val st = f * HOP
            if (st + win + 240 > raw.size) continue
            var e0 = 0f
            for (i in 0 until win) e0 += raw[st + i] * raw[st + i]
            if (e0 < 1e-6f) continue
            var best = 0f; var bestLag = 0
            for (lag in 40..228) {
                var c = 0f; var e1 = 0f
                for (i in 0 until win) { c += raw[st + i] * raw[st + i + lag]; e1 += raw[st + i + lag] * raw[st + i + lag] }
                val r = c / sqrt(e0 * e1 + 1e-9f)
                if (r > best) { best = r; bestLag = lag }
            }
            if (best > 0.5f && bestLag > 0) semis += (12.0 * ln(SR.toDouble() / bestLag / 100.0) / ln(2.0)).toFloat()
        }
        if (semis.size >= 4) {
            semis.sort()
            val med = semis[semis.size / 2]
            val m = semis.average().toFloat()
            out[PITCH] = med
            out[PITCH + 1] = sqrt(semis.map { (it - m) * (it - m) }.average()).toFloat()
            return out.copyOf(D + 1).also { it[D] = 1f }
        }
        return out.copyOf(D + 1).also { it[D] = 0f }
    }

    class Profile(val mean: FloatArray, val sd: FloatArray, val thr: Float) {
        fun dist(e: FloatArray, m: FloatArray = mean): Float {
            val usePitch = e.size > D && e[D] == 1f
            var s = 0f; var n = 0
            for (i in 0 until D) {
                if (i >= PITCH && !usePitch) continue
                val z = (e[i] - m[i]) / sd[i]
                s += z * z; n++
            }
            return sqrt(s / max(n, 1))
        }

        fun accepts(e: FloatArray, sens: Float): Boolean {
            if (e.size > D && e[D] == 1f && abs(e[PITCH] - mean[PITCH]) > 5.5f) return false
            return dist(e) <= thr * (0.88f + sens * 0.3f)
        }

        /** 0..100, how close this voice is to yours (for the test screen). */
        fun percent(e: FloatArray): Int = (100f * (1f - dist(e) / (thr * 1.7f))).coerceIn(0f, 100f).toInt()
    }

    fun build(es: List<FloatArray>): Profile? {
        if (es.size < 4) return null
        val mean = FloatArray(D) { i -> es.map { it[i] }.average().toFloat() }
        val sd = FloatArray(D) { i ->
            val v = es.map { (it[i] - mean[i]) * (it[i] - mean[i]) }.average()
            max(sqrt(v).toFloat() * 1.15f, floors[i])
        }
        val tmp = Profile(mean, sd, 1f)
        // Each recording against the average of all the others: how far your own voice strays from itself.
        val loo = es.indices.map { k ->
            val others = es.filterIndexed { j, _ -> j != k }
            val m = FloatArray(D) { i -> others.map { it[i] }.average().toFloat() }
            tmp.dist(es[k], m)
        }
        val lm = loo.average().toFloat()
        val ls = sqrt(loo.map { (it - lm) * (it - lm) }.average()).toFloat()
        val thr = max(max(loo.max() * 1.15f, lm + 2.2f * ls), 0.9f).coerceAtMost(2.3f)
        return Profile(mean, sd, thr)
    }

    private fun file(c: Context) = File(c.filesDir, "voiceprint.bin")

    fun save(c: Context, p: Profile) {
        DataOutputStream(file(c).outputStream().buffered()).use { o ->
            o.writeInt(D); o.writeFloat(p.thr)
            for (v in p.mean) o.writeFloat(v)
            for (v in p.sd) o.writeFloat(v)
        }
    }

    fun load(c: Context): Profile? = runCatching {
        if (!file(c).exists()) return null
        DataInputStream(file(c).inputStream().buffered()).use { i ->
            if (i.readInt() != D) return null
            val thr = i.readFloat()
            val mean = FloatArray(D) { i.readFloat() }
            val sd = FloatArray(D) { i.readFloat() }
            Profile(mean, sd, thr)
        }
    }.getOrNull()

    fun ready(c: Context) = file(c).exists()
    fun clear(c: Context) { file(c).delete() }
}
