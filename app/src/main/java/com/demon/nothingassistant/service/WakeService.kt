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
import com.demon.nothingassistant.audio.WakeVoice
import com.demon.nothingassistant.debug.AppLog

/**
 * Optional wake word. A tiny volume gate watches the microphone; only when somebody speaks is that one short burst compared with the
 * fingerprints of your own voice from training. Silence costs almost no CPU, and nothing is recorded, stored or sent anywhere.
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

    @SuppressLint("MissingPermission")
    private fun loop(my: Int) {
        val settings = (application as NothingApp).settings
        val model = WakeVoice.load(this)
        if (model == null) { AppLog.e("wake", "no trained wake word yet"); stopSelf(); return }
        val print = com.demon.nothingassistant.audio.Voiceprint.load(this)
        val seg = WakeVoice.Segmenter { (0.5f - settings.wakeSens) * 0.01f }
        val buf = ShortArray(800) // 50 ms
        var ar: AudioRecord? = null

        fun openMic() {
            val min = AudioRecord.getMinBufferSize(WakeVoice.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeVoice.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WakeVoice.SR))
            if (a.state == AudioRecord.STATE_INITIALIZED) { a.startRecording(); ar = a } else a.release()
        }
        fun closeMic() { ar?.let { runCatching { it.stop() }; it.release() }; ar = null }

        try {
            while (my == gen) {
                // The panel is open or the training screen is recording: the microphone belongs to them.
                if (paused || PanelActivity.current != null) { closeMic(); Thread.sleep(700); continue }
                if (ar == null) { openMic(); if (ar == null) { Thread.sleep(2000); continue } }
                val n = ar!!.read(buf, 0, buf.size)
                if (n <= 0) continue
                val utterance = seg.push(buf, n) ?: continue
                val f = WakeVoice.features(utterance) ?: continue
                if (model.accepts(f, settings.wakeSens)) {
                    // Right words: now check it is also your voice.
                    if (settings.voiceLock && print != null) {
                        val e = com.demon.nothingassistant.audio.Voiceprint.embed(utterance)
                        if (e == null || !print.accepts(e, settings.wakeSens)) { AppLog.d("wake", "right words, other voice (${if (e == null) 0 else print.percent(e)}%)"); continue }
                    }
                    AppLog.d("wake", "woke up (score ${model.score(f)} / limit ${model.threshold})")
                    closeMic()
                    onWake()
                    Thread.sleep(2500)
                }
            }
        } finally { closeMic() }
    }

    private fun onWake() {
        com.demon.nothingassistant.audio.Cue.listening()
        WindowAnchor.holdAndWait(this, 60_000L) // a window must exist before Android lets us open the panel from the background
        runCatching {
            PanelActivity.launch(
                applicationContext,
                Intent(this, PanelActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(OverlayService.EXTRA_AUTO_START, true)
            )
        }.onFailure { AppLog.e("wake", "couldn't open the panel: ${it.message}") }
    }
}
