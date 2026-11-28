package com.demon.nothingassistant

import android.app.Application
import kotlinx.coroutines.launch
import com.demon.nothingassistant.core.AssistantController
import com.demon.nothingassistant.data.Settings

class NothingApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var controller: AssistantController
        private set

    override fun onCreate() {
        super.onCreate()
        com.demon.nothingassistant.debug.AppLog.init(this)
        // Any crash is written to the log first, so the next time the log screen is opened the cause is there.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { com.demon.nothingassistant.debug.AppLog.e("crash", "Uncaught on ${t.name}: ${e.message}", e) }
            previous?.uncaughtException(t, e)
        }
        com.demon.nothingassistant.action.Subject.init(this)
        com.demon.nothingassistant.action.Blackboard.init(this)
        com.demon.nothingassistant.agent.Skills.init(this)
        com.demon.nothingassistant.audio.Cue.recover(this)
        com.demon.nothingassistant.ai.Banter.init(this)
        com.demon.nothingassistant.ai.PyKb.init(this)
        com.demon.nothingassistant.ui.NFont.init(this)
        settings = Settings(this)
        com.demon.nothingassistant.priv.Priv.preference = { settings.privMode }
        settings.applyDefaultsOnce()
        // Leftovers from the removed wake word and voice setup: delete their saved recordings and settings.
        runCatching {
            filesDir.walkBottomUp().filter { it != filesDir && Regex("wake|voiceprint|voicematch", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.forEach { it.deleteRecursively() }
            getSharedPreferences("nothing_settings", MODE_PRIVATE).edit().apply {
                listOf("wake_word", "wake_phrase", "wake_variants", "wake_sens", "voice_lock").forEach { remove(it) }
            }.apply()
            listOf("wake", "voiceprint", "wake_voice").forEach { deleteSharedPreferences(it) }
        }
        // Someone who updated the app (rather than installed it fresh) has already set it up: skip the welcome screen for them.
        if (!settings.onboarded) runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (info.lastUpdateTime - info.firstInstallTime > 120_000L) settings.setOnboarded(true)
        }
        com.demon.nothingassistant.audio.SpeechInfo.cache = settings.googleOffline
        controller = AssistantController(this, settings)
        android.os.Handler(mainLooper).postDelayed({ com.demon.nothingassistant.audio.SpeechInfo.autoConfigure(this, settings) }, 1500)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            kotlinx.coroutines.delay(3000)
            com.demon.nothingassistant.priv.AutoGrant.keepAssistant(this@NothingApp)
        }
        if (settings.autoGrant) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            com.demon.nothingassistant.priv.AutoGrant.runOnce(this@NothingApp)
        }
    }
}
