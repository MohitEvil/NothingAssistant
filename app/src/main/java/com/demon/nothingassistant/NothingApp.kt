package com.demon.nothingassistant

import android.app.Application
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
        com.demon.nothingassistant.audio.Cue.recover(this)
        settings = Settings(this)
        com.demon.nothingassistant.priv.Priv.preference = { settings.privMode }
        controller = AssistantController(this, settings)
    }
}
