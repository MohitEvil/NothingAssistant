package com.demon.nothingassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.demon.nothingassistant.data.Settings
import kotlinx.coroutines.launch

/** Restores the floating bubble after a reboot if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { com.demon.nothingassistant.auto.MacroScheduler.rescheduleAll(context) }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
                try { com.demon.nothingassistant.priv.AutoGrant.keepAssistant(context.applicationContext, 6) } finally { pending.finish() }
            }
        }
        if (Settings.bubbleEnabled(context) && android.provider.Settings.canDrawOverlays(context)) {
            runCatching { OverlayService.start(context) }
        }
    }
}
