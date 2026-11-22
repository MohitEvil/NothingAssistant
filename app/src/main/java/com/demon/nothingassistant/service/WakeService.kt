package com.demon.nothingassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.demon.nothingassistant.MainActivity
import com.demon.nothingassistant.NothingApp
import com.demon.nothingassistant.PanelActivity
import com.demon.nothingassistant.R
import com.demon.nothingassistant.audio.VoskAsr
import com.demon.nothingassistant.debug.AppLog
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Optional wake word ("Hey Nothing"). A tiny volume gate watches the microphone; only when somebody speaks does a recogniser that knows
 * nothing but the wake phrase (and the ways it heard you say it while training) listen to that one short burst. Silence costs almost
 * no CPU, and nothing is recorded, stored or sent anywhere.
 */
class WakeService : Service() {

    companion object {
        private const val CHANNEL = "nothing_wake"
        private const val NOTIF_ID = 2
        /** Set while the settings page records training samples, so the microphone is free. */
        @Volatile var paused = false
        @Volatile var running = false
            private set

        fun start(context: Context) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java)) }
                .onFailure { AppLog.e("wake", "can't start from the background: ${it.message}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, WakeService::class.java)) }
    }

    @Volatile private var gen = 0
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nothing)
            .setContentTitle("Listening for the wake word")
            .setContentText("Say your wake phrase to start. Turn it off in Voice settings.")
            .setContentIntent(open).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            AppLog.e("wake", "foreground refused: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (thread == null) {
            val my = ++gen
            running = true
            thread = Thread { runCatching { loop(my) }.onFailure { AppLog.e("wake", "loop failed", it) }; running = false }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gen++
        running = false
        thread = null
        super.onDestroy()
    }

    private fun grammar(phrases: List<String>): String = JSONArray().also { a -> phrases.forEach { a.put(it) }; a.put("[unk]") }.toString()

    @SuppressLint("MissingPermission")
    private fun loop(my: Int) {
        val settings = (application as NothingApp).settings
        val model = VoskAsr.get(this).modelFor(settings.language)
        if (model == null) { AppLog.e("wake", "no offline speech model downloaded"); stopSelf(); return }
        val phrases = (listOf(settings.wakePhrase) + settings.wakeVariants).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val gram = grammar(phrases)
        val sr = 16000
        val frame = 1600 // 100 ms
        val buf = ShortArray(frame)
        val ring = ArrayDeque<ShortArray>()
        var ar: AudioRecord? = null
        var rec: Recognizer? = null
        var floor = 0.006f
        var silent = 0
        var frames = 0

        fun openMic() {
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr))
            if (a.state == AudioRecord.STATE_INITIALIZED) { a.startRecording(); ar = a } else a.release()
        }
        fun closeMic() { ar?.let { runCatching { it.stop() }; it.release() }; ar = null }

        try {
            while (my == gen) {
                // The panel is open or the settings page is training: the microphone belongs to them.
                if (paused || PanelActivity.current != null) { closeMic(); rec?.close(); rec = null; Thread.sleep(700); continue }
                if (ar == null) { openMic(); if (ar == null) { Thread.sleep(2000); continue } }
                val n = ar!!.read(buf, 0, frame)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                val gate = maxOf(floor * 3.2f, 0.012f + (1f - settings.wakeSens) * 0.02f)

                if (rec == null) {
                    if (rms < gate) { floor = floor * 0.95f + rms * 0.05f; ring.addLast(buf.copyOf(n)); if (ring.size > 3) ring.removeFirst(); continue }
                    // Somebody is making noise: let the phrase recogniser hear this burst (plus the 0.3 s before it).
                    rec = Recognizer(model, sr.toFloat(), gram).also { r -> runCatching { r.setWords(true) } }
                    for (b in ring) rec!!.acceptWaveForm(b, b.size)
                    ring.clear()
                    silent = 0; frames = 0
                }
                rec!!.acceptWaveForm(buf, n)
                frames++
                if (rms < gate * 0.7f) silent++ else silent = 0
                if (silent >= 5 || frames >= 30) { // a pause, or a burst longer than any wake phrase
                    val json = rec!!.finalResult
                    rec!!.close(); rec = null
                    if (accepts(json, phrases, settings.wakeSens)) {
                        AppLog.d("wake", "woke up")
                        closeMic()
                        onWake()
                        Thread.sleep(2500)
                    }
                }
            }
        } finally {
            closeMic()
            runCatching { rec?.close() }
        }
    }

    private fun accepts(json: String, phrases: List<String>, sens: Float): Boolean {
    return runCatching {
        val o = JSONObject(json)
        val text = o.optString("text", "").trim().lowercase()
        if (text.isEmpty() || text !in phrases) return@runCatching false
        val arr = o.optJSONArray("result") ?: return@runCatching true
        var sum = 0.0
        for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optDouble("conf", 1.0)
        val avg = if (arr.length() == 0) 1.0 else sum / arr.length()
        avg >= 0.9 - sens * 0.5
    }.getOrDefault(false)
    }

    private fun onWake() {
        com.demon.nothingassistant.audio.Cue.listening()
        runCatching {
            startActivity(
                Intent(this, PanelActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(OverlayService.EXTRA_AUTO_START, true)
            )
        }.onFailure { AppLog.e("wake", "couldn't open the panel: ${it.message}") }
    }
}
