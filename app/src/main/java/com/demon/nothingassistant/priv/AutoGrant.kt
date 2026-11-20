package com.demon.nothingassistant.priv

import android.content.Context
import com.demon.nothingassistant.debug.AppLog
import kotlinx.coroutines.delay

/**
 * With Shizuku or root, grants everything the assistant needs by itself, so nobody has to tap through
 * permission screens: runtime permissions, special access (overlay, settings, exact alarms, battery), accessibility
 * and the default assistant.
 */
object AutoGrant {
    private val runtime = listOf(
        "RECORD_AUDIO", "READ_CONTACTS", "READ_SMS", "CALL_PHONE", "READ_CALENDAR", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE", "POST_NOTIFICATIONS", "CAMERA", "SEND_SMS", "READ_PHONE_STATE", "ANSWER_PHONE_CALLS"
    )
    private val appOps = listOf(
        "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "SCHEDULE_EXACT_ALARM", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES",
        "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "ACCESS_NOTIFICATIONS", "GET_USAGE_STATS"
    )
    @Volatile private var done = false
    @Volatile var lastSummary = ""
        private set

    /** Waits (up to [waitMs]) for Shizuku or root to appear, then grants once per process. Safe to call repeatedly. */
    suspend fun runOnce(context: Context, waitMs: Long = 25_000): Boolean {
        if (done) return true
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Priv.probe() } }
        var waited = 0L
        while (!Priv.available() && waited < waitMs) { delay(1000); waited += 1000 }
        if (!Priv.available()) return false
        val ok = grantAll(context)
        done = ok
        return ok
    }

    /** Grants everything now. Returns true when the commands ran. */
    suspend fun grantAll(context: Context): Boolean {
        if (!Priv.available()) { lastSummary = "No Shizuku or root access."; return false }
        val pkg = context.packageName
        val script = StringBuilder()
        runtime.forEach { script.append("pm grant $pkg android.permission.$it 2>/dev/null; ") }
        appOps.forEach { script.append("appops set $pkg $it allow 2>/dev/null; ") }
        script.append("cmd appops set $pkg MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; ")
        script.append("dumpsys deviceidle whitelist +$pkg 2>/dev/null; ")
        script.append("cmd notification allow_listener $pkg/com.demon.nothingassistant.service.NotificationFeed 2>/dev/null; ")
        val r = Priv.sh(script.toString(), 40)
        AppLog.d("autogrant", "ran: exit=${r.code}")
        val access = runCatching { com.demon.nothingassistant.access.NothingAccess.instance != null || PrivBridge.enableAccessibility(context) }.getOrDefault(false)
        val assistant = runCatching { setAssistant(context) }.getOrDefault(false)
        lastSummary = "Permissions granted" + (if (access) ", screen control on" else "") + (if (assistant) ", default assistant set" else "") + "."
        return true
    }

    /** Makes Nothing Assistant the default assistant without opening any settings page. */
    suspend fun setAssistant(context: Context): Boolean {
        if (!Priv.available()) return false
        val pkg = context.packageName
        val svc = "$pkg/$pkg.voice.NothingInteractionService"
        Priv.sh("settings put secure assistant $svc; settings put secure voice_interaction_service $svc; settings put secure voice_recognition_service $pkg/$pkg.voice.NothingRecognitionService", 15)
        val now = Priv.sh("settings get secure assistant").text
        return now.contains(pkg)
    }
}

/** Small helper so AutoGrant can switch accessibility on without depending on the action layer. */
object PrivBridge {
    suspend fun enableAccessibility(context: Context): Boolean {
        if (!Priv.available()) return false
        val me = context.packageName + "/" + com.demon.nothingassistant.access.NothingAccess::class.java.name
        val cur = Priv.sh("settings get secure enabled_accessibility_services").text.trim().let { if (it == "null") "" else it }
        val list = when { cur.contains(me) -> cur; cur.isEmpty() -> me; else -> "$cur:$me" }
        Priv.sh("settings put secure enabled_accessibility_services $list; settings put secure accessibility_enabled 1")
        repeat(16) {
            delay(250)
            if (com.demon.nothingassistant.access.NothingAccess.instance != null) return true
        }
        return false
    }
}
