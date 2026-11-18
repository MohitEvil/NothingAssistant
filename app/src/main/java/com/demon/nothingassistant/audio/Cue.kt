package com.demon.nothingassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The assistant's own sounds: tiny soft synth "dots" instead of Google's default listening beep.
 * Generated in code, so there is nothing to download and nothing to license.
 */
object Cue {
    private const val RATE = 44_100
    private val main = Handler(Looper.getMainLooper())

    /** Two quick rising dots: the mic is open. */
    private val listen: ShortArray by lazy { dots(listOf(Dot(1318.5, 0.040), Dot(1975.5, 0.055)), gap = 0.028, level = 0.30) }

    /** One soft low dot: the conversation is over. */
    private val close: ShortArray by lazy { dots(listOf(Dot(987.8, 0.070)), gap = 0.0, level = 0.24) }

    private class Dot(val hz: Double, val seconds: Double)

    private fun dots(list: List<Dot>, gap: Double, level: Double): ShortArray {
        val total = list.sumOf { it.seconds + gap } + 0.03
        val out = ShortArray((total * RATE).toInt())
        var offset = 0
        for (d in list) {
            val n = (d.seconds * RATE).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val attack = (t / 0.004).coerceAtMost(1.0)            // 4 ms fade in, no click
                val decay = exp(-t * (5.5 / d.seconds))                 // quick, soft tail
                val tone = sin(2 * PI * d.hz * t) + 0.18 * sin(2 * PI * d.hz * 2 * t) // a touch of brightness
                val v = (tone * attack * decay * level * Short.MAX_VALUE).toInt()
                if (offset + i < out.size) out[offset + i] = v.coerceIn(-32767, 32767).toShort()
            }
            offset += n + (gap * RATE).toInt()
        }
        return out
    }

    fun listening() = play(listen)
    fun closing() = play(close)

    private fun play(pcm: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // its own stream, so muting the system beep doesn't mute this
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, (pcm.size * 1000L / RATE) + 250)
        }
    }

    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_RING)
    /** Streams where muting did not stick (some phones ignore it): their volume was set to 0 instead, and is kept here. */
    private val lowered = HashMap<Int, Int>()
    private val muted = HashSet<Int>()
    private var unmuteAt = 0L
    private var tick: Runnable? = null

    private fun audio(c: Context) = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("cue", Context.MODE_PRIVATE)

    /**
     * Google's speech recogniser plays its own loud "ding" when listening starts AND when it stops or is cancelled
     * (the speaker plays both; some Bluetooth profiles hide them). There is no setting for it, so the streams it can use
     * are muted from just before it starts until a moment after it ends, and restored by a timer. Anything the user had
     * muted is left alone, and a flag on disk lets the next launch restore sound if the app was killed in between.
     */
    fun hushSystemBeep(context: Context, ms: Long = 700) {
        runCatching {
            val am = audio(context)
            unmuteAt = maxOf(unmuteAt, SystemClock.uptimeMillis() + ms)
            muteNow(context, am)
            schedule(context)
        }
    }

    /** The recognition is over: keep the streams muted only long enough to cover the closing ding, then restore them. */
    fun hushThenRelease(context: Context, ms: Long = 750) {
        runCatching {
            val am = audio(context)
            unmuteAt = SystemClock.uptimeMillis() + ms
            muteNow(context, am)
            schedule(context)
        }
    }

    /** Our own voice is about to speak: restore sound immediately so its first word is never clipped. */
    fun unhush(context: Context) {
        runCatching { release(context, audio(context)) }
    }

    /** Called at app start: if the last run died while muted, restore the sound. */
    fun recover(context: Context) {
        runCatching {
            val p = prefs(context)
            val saved = p.getStringSet("muted", emptySet()).orEmpty()
            if (saved.isEmpty()) return
            val am = audio(context)
            for (st in saved) runCatching { am.adjustStreamVolume(st.toInt(), AudioManager.ADJUST_UNMUTE, 0) }
            p.getString("lowered", "").orEmpty().split(',').filter { it.contains(':') }.forEach { e ->
                val (a, b) = e.split(':')
                runCatching { am.setStreamVolume(a.toInt(), b.toInt(), 0) }
            }
            p.edit().remove("muted").remove("lowered").apply()
        }
    }

    private fun muteNow(c: Context, am: AudioManager) {
        var changed = false
        for (st in streams) {
            if (st in muted || am.isStreamMute(st)) continue
            try { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); muted.add(st); changed = true } catch (_: SecurityException) { }
            // Headphones and some Nothing OS builds ignore mute on a stream: drop its volume to 0 as well.
            if (st in muted && !am.isStreamMute(st) && st !in lowered) {
                try { val v = am.getStreamVolume(st); if (v > 0) { lowered[st] = v; am.setStreamVolume(st, 0, 0) } } catch (_: SecurityException) { }
            } else if (st !in muted && st != AudioManager.STREAM_RING && !am.isStreamMute(st) && st !in lowered && (st == AudioManager.STREAM_MUSIC || st == AudioManager.STREAM_VOICE_CALL)) {
                try { val v = am.getStreamVolume(st); if (v > 0) { lowered[st] = v; am.setStreamVolume(st, 0, 0); muted.add(st); changed = true } } catch (_: SecurityException) { }
            }
        }
        if (changed || lowered.isNotEmpty()) prefs(c).edit().putStringSet("muted", muted.map { it.toString() }.toSet()).putString("lowered", lowered.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    private fun release(c: Context, am: AudioManager) {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        for (st in muted) runCatching { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) }
        for ((st, v) in lowered) runCatching { am.setStreamVolume(st, v, 0) }
        if (muted.isNotEmpty() || lowered.isNotEmpty()) prefs(c).edit().remove("muted").remove("lowered").apply()
        lowered.clear()
        muted.clear()
        unmuteAt = 0
    }

    private fun schedule(c: Context) {
        tick?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val left = unmuteAt - SystemClock.uptimeMillis()
                if (left > 0) { main.postDelayed(this, left + 20); return }
                release(c, audio(c))
            }
        }
        tick = r
        main.postDelayed(r, (unmuteAt - SystemClock.uptimeMillis()).coerceAtLeast(0) + 20)
    }
}
