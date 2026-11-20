package com.demon.nothingassistant.priv

import android.content.Context
import android.content.pm.PackageManager
import com.demon.nothingassistant.debug.AppLog
import kotlinx.coroutines.delay

/**
 * With Shizuku or root, grants what the assistant needs so nobody has to tap through permission screens.
 *
 * Android kills an app's process when some permissions change under it, so everything is granted one step at a time,
 * and each step is written down BEFORE it runs. If a step ends the process, the next start carries on with the
 * following step instead of repeating the one that killed it (that repeat is what made the app restart forever).
 */
object AutoGrant {
    private class Step(val key: String, val cmd: String, val perm: String? = null)

    @Volatile var lastSummary = ""
        private set
    @Volatile private var running = false

    private fun steps(pkg: String): List<Step> {
        val s = ArrayList<Step>()
        // Quiet ones first: special access that never touches the process.
        for (op in listOf("SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "SCHEDULE_EXACT_ALARM", "REQUEST_INSTALL_PACKAGES", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND"))
            s.add(Step("op:$op", "appops set $pkg $op allow"))
        s.add(Step("idle", "dumpsys deviceidle whitelist +$pkg"))
        s.add(Step("notiflistener", "cmd notification allow_listener $pkg/$pkg.service.NotificationFeed"))
        // Runtime permissions. Storage and media last: changing those is what restarts an app.
        for (p in listOf("RECORD_AUDIO", "POST_NOTIFICATIONS", "READ_CONTACTS", "CALL_PHONE", "READ_SMS", "READ_CALENDAR", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"))
            s.add(Step("perm:$p", "pm grant $pkg android.permission.$p", "android.permission.$p"))
        for (p in listOf("READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE"))
            s.add(Step("perm:$p", "pm grant $pkg android.permission.$p", "android.permission.$p"))
        return s
    }

    private fun prefs(c: Context) = c.getSharedPreferences("autogrant", Context.MODE_PRIVATE)

    /** Start-up call: continues where the last run stopped. Does nothing when Shizuku/root isn't there. */
    suspend fun runOnce(context: Context, waitMs: Long = 25_000): Boolean {
        delay(4000) // let the app come up first
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Priv.probe() } }
        var waited = 0L
        while (!Priv.available() && waited < waitMs) { delay(1000); waited += 1000 }
        if (!Priv.available()) return false
        return grantAll(context, force = false)
    }

    /** Runs the steps not done yet. [force] starts the list again (the button). */
    suspend fun grantAll(context: Context, force: Boolean = true): Boolean {
        if (!Priv.available()) { lastSummary = "No Shizuku or root access."; return false }
        if (running) return true
        running = true
        try {
            val p = prefs(context)
            if (force) p.edit().remove("done").apply()
            val done = HashSet(p.getStringSet("done", emptySet()) ?: emptySet())
            var ran = 0
            for (st in steps(context.packageName)) {
                if (st.key in done) continue
                if (st.perm != null && context.checkSelfPermission(st.perm) == PackageManager.PERMISSION_GRANTED) { done.add(st.key); continue }
                // Written first, committed to disk: survives the process being killed by the step itself.
                done.add(st.key)
                p.edit().putStringSet("done", HashSet(done)).commit()
                val r = Priv.sh(st.cmd, 15)
                AppLog.d("autogrant", "${st.key}: exit=${r.code} ${r.text.take(80)}")
                ran++
                delay(250)
            }
            val access = runCatching { com.demon.nothingassistant.access.NothingAccess.instance != null || PrivBridge.enableAccessibility(context) }.getOrDefault(false)
            lastSummary = (if (ran > 0) "Permissions granted" else "Permissions already granted") + (if (access) ", screen control is on." else ".")
            return true
        } finally { running = false }
    }

    /** Makes Nothing Assistant the default assistant without opening any settings page. */
    suspend fun setAssistant(context: Context): Boolean {
        if (!Priv.available()) return false
        val pkg = context.packageName
        val svc = "$pkg/$pkg.voice.NothingInteractionService"
        Priv.sh("settings put secure assistant $svc; settings put secure voice_interaction_service $svc", 15)
        return Priv.sh("settings get secure assistant").text.contains(pkg)
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
