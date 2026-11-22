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
        com.demon.nothingassistant.action.Subject.init(this)
        com.demon.nothingassistant.action.Blackboard.init(this)
        com.demon.nothingassistant.agent.Skills.init(this)
        com.demon.nothingassistant.audio.Cue.recover(this)
        com.demon.nothingassistant.ai.Banter.init(this)
        com.demon.nothingassistant.ai.PyKb.init(this)
        settings = Settings(this)
        com.demon.nothingassistant.priv.Priv.preference = { settings.privMode }
        settings.applyDefaultsOnce()
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
