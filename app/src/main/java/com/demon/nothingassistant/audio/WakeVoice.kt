package com.demon.nothingassistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Your own wake word, learned from your voice. No speech model is needed: a recording of the phrase becomes a small fingerprint
 * (MFCC features, the same idea voice assistants use) and a new utterance is compared with your fingerprints by dynamic time warping.
 * That is why it works for any phrase in any language, and why training with different volumes, speeds and moods makes it better.
 */
object WakeVoice {
    const val SR = 16000
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val NMEL = 26
    private const val NCEP = 13

    internal val hamming = FloatArray(FRAME) { (0.54 - 0.46 * cos(2.0 * PI * it / (FRAME - 1))).toFloat() }

    internal val melBank: Array<FloatArray> by lazy {
        fun mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun inv(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
        val lo = mel(80.0); val hi = mel(7600.0)
        val pts = DoubleArray(NMEL + 2) { inv(lo + (hi - lo) * it / (NMEL + 1)) }
        val bins = IntArray(NMEL + 2) { ((NFFT + 1) * pts[it] / SR).toInt().coerceIn(0, NFFT / 2) }
        Array(NMEL) { m ->
            val f = FloatArray(NFFT / 2 + 1)
            for (k in bins[m]..bins[m + 1]) if (bins[m + 1] > bins[m]) f[k] = (k - bins[m]).toFloat() / (bins[m + 1] - bins[m])
            for (k in bins[m + 1]..bins[m + 2]) if (bins[m + 2] > bins[m + 1]) f[k] = (bins[m + 2] - k).toFloat() / (bins[m + 2] - bins[m + 1])
            f
        }
    }

    private val dct: Array<FloatArray> by lazy { Array(NCEP) { i -> FloatArray(NMEL) { j -> cos(PI * i * (j + 0.5) / NMEL).toFloat() } } }

    internal fun fft(re: FloatArray, im: FloatArray) {
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

    /** The fingerprint of one utterance: per 10 ms frame, 12 cepstral values and the loudness, each normalised. Null if it isn't a plausible phrase. */
    fun features(pcm: ShortArray): Array<FloatArray>? {
        if (pcm.size < FRAME * 3) return null
        val x = FloatArray(pcm.size)
        var prev = 0f
        for (i in pcm.indices) { val s = pcm[i] / 32768f; x[i] = s - 0.97f * prev; prev = s }
        val nFrames = (x.size - FRAME) / HOP + 1
        val energy = FloatArray(nFrames)
        val logMel = Array(nFrames) { FloatArray(NMEL) }
        val re = FloatArray(NFFT); val im = FloatArray(NFFT)
        for (f in 0 until nFrames) {
            java.util.Arrays.fill(re, 0f); java.util.Arrays.fill(im, 0f)
            var e = 0f
            for (i in 0 until FRAME) { val v = x[f * HOP + i] * hamming[i]; re[i] = v; e += v * v }
            energy[f] = e
            fft(re, im)
            for (m in 0 until NMEL) {
                var s = 0f
                val bank = melBank[m]
                for (k in 0..NFFT / 2) if (bank[k] != 0f) s += bank[k] * (re[k] * re[k] + im[k] * im[k])
                logMel[f][m] = ln(s + 1e-8f)
            }
        }
        // Keep only the spoken part.
        val emax = energy.max()
        if (emax <= 1e-6f) return null
        var a = 0; var b = nFrames - 1
        while (a < nFrames && energy[a] < emax * 0.02f) a++
        while (b > a && energy[b] < emax * 0.02f) b--
        val n = b - a + 1
        if (n < 28 || n > 300) return null
        val out = Array(n) { FloatArray(NCEP) }
        for (i in 0 until n) {
            val lm = logMel[a + i]
            for (c in 1 until NCEP) { var s = 0f; for (m in 0 until NMEL) s += dct[c][m] * lm[m]; out[i][c - 1] = s }
            out[i][NCEP - 1] = ln(energy[a + i] + 1e-8f)
        }
        // Per-utterance normalisation: volume and microphone distance drop out.
        for (d in 0 until NCEP) {
            var mean = 0f; for (i in 0 until n) mean += out[i][d]; mean /= n
            var v = 0f; for (i in 0 until n) { val t = out[i][d] - mean; v += t * t }
            val sd = sqrt(v / n) + 1e-4f
            for (i in 0 until n) out[i][d] = (out[i][d] - mean) / sd
        }
        return out
    }

    /** Distance between two fingerprints (smaller = more alike), by dynamic time warping inside a band. */
    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size; val m = b.size
        val band = max(abs(n - m), max(n, m) * 3 / 10) + 2
        val inf = 1e30f
        var prev = FloatArray(m + 1) { inf }; var cur = FloatArray(m + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            java.util.Arrays.fill(cur, inf)
            val lo = max(1, i - band); val hi = min(m, i + band)
            for (j in lo..hi) {
                var d = 0f
                val x = a[i - 1]; val y = b[j - 1]
                for (k in x.indices) { val t = x[k] - y[k]; d += t * t }
                d = sqrt(d)
                cur[j] = d + min(prev[j], min(prev[j - 1], cur[j - 1]))
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m] / (n + m)
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    /** Your trained phrase: the fingerprints and how far a new utterance may be from them. */
    class Model(val templates: List<Array<FloatArray>>, val threshold: Float) {
        private val medLen = templates.map { it.size }.sorted().let { it[it.size / 2] }

        /** Average of the two closest distances. */
        fun score(f: Array<FloatArray>): Float {
            val d = templates.map { dtw(f, it) }.sorted()
            return if (d.size >= 2) (d[0] + d[1]) / 2f else d[0]
        }

        fun accepts(f: Array<FloatArray>, sens: Float): Boolean {
            if (f.size < medLen * 0.5f || f.size > medLen * 1.9f) return false
            return score(f) <= threshold * (0.8f + sens * 0.5f)
        }
    }

    /** How far apart your own recordings are tells how strict the match can be. */
    fun calibrate(t: List<Array<FloatArray>>): Float {
        val nn = t.indices.map { i ->
            val d = t.indices.filter { it != i }.map { dtw(t[i], t[it]) }.sorted()
            d.take(3).average().toFloat()
        }
        val mean = nn.average().toFloat()
        val sd = sqrt(nn.map { (it - mean) * (it - mean) }.average()).toFloat()
        return (mean + 1.8f * sd) * 1.12f
    }

    private fun file(c: Context) = File(c.filesDir, "wake_templates.bin")

    fun save(c: Context, t: List<Array<FloatArray>>, thr: Float) {
        DataOutputStream(file(c).outputStream().buffered()).use { o ->
            o.writeFloat(thr); o.writeInt(t.size)
            for (f in t) { o.writeInt(f.size); o.writeInt(f[0].size); for (r in f) for (v in r) o.writeFloat(v) }
        }
    }

    fun load(c: Context): Model? = runCatching {
        if (!file(c).exists()) return null
        DataInputStream(file(c).inputStream().buffered()).use { i ->
            val thr = i.readFloat(); val n = i.readInt()
            val list = List(n) { val fr = i.readInt(); val d = i.readInt(); Array(fr) { FloatArray(d) { i.readFloat() } } }
            if (list.size < 3) null else Model(list, thr)
        }
    }.getOrNull()

    fun ready(c: Context) = file(c).exists()
    fun clear(c: Context) { file(c).delete() }
    fun sampleCount(c: Context): Int = runCatching { DataInputStream(file(c).inputStream()).use { it.readFloat(); it.readInt() } }.getOrDefault(0)

    /** Cuts a stream of 50 ms frames into utterances. Used by the training screen and by the always-on listener. */
    class Segmenter(private val maxFrames: Int = 70, private val endSilence: Int = 9, private val gateBoost: () -> Float = { 0f }) {
        private var floor = 0.006f
        private val ring = ArrayDeque<ShortArray>()
        private val seg = ArrayList<ShortArray>()
        private var active = false
        private var silent = 0
        private var loud = 0
        var level = 0f
            private set

        /** Returns the finished utterance, or null while still waiting / listening. */
        fun push(buf: ShortArray, n: Int): ShortArray? {
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / n).toFloat()
            level = (rms * 14f).coerceIn(0f, 1f)
            val gate = max(floor * 3.2f, 0.012f + gateBoost())
            val frame = buf.copyOf(n)
            if (!active) {
                if (rms < gate) { floor = floor * 0.95f + rms * 0.05f; ring.addLast(frame); if (ring.size > 5) ring.removeFirst(); return null }
                active = true; silent = 0; loud = 0
                seg.clear(); seg.addAll(ring); ring.clear()
            }
            seg.add(frame)
            if (rms >= gate * 0.8f) { silent = 0; loud++ } else silent++
            if (silent >= endSilence || seg.size >= maxFrames) {
                val ok = loud >= 5
                active = false
                val total = seg.sumOf { it.size }
                val out = ShortArray(total)
                var p = 0
                for (s in seg) { System.arraycopy(s, 0, out, p, s.size); p += s.size }
                seg.clear()
                return if (ok) out else null
            }
            return null
        }
    }

    /**
     * Training recorder: blocks until one utterance has been captured (or the timeout). Call from a background thread.
     * [onChunk] gets every 50 ms of audio as it arrives (for live word recognition); the live spectrum is published to [VoiceBands].
     */
    @SuppressLint("MissingPermission")
    fun capture(
        timeoutMs: Long, stop: () -> Boolean, maxFrames: Int = 70, endSilence: Int = 9,
        onChunk: ((ShortArray, Int) -> Unit)? = null, onLevel: (Float) -> Unit
    ): ShortArray? {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, SR))
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return null }
        val seg = Segmenter(maxFrames, endSilence)
        val buf = ShortArray(800)
        val t0 = System.currentTimeMillis()
        try {
            ar.startRecording()
            while (!stop() && System.currentTimeMillis() - t0 < timeoutMs) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                VoiceBands.pushPcm(buf, n, SR)
                onChunk?.invoke(buf, n)
                val r = seg.push(buf, n)
                onLevel(seg.level)
                if (r != null) return r
            }
        } finally { runCatching { ar.stop() }; ar.release() }
        return null
    }
}
