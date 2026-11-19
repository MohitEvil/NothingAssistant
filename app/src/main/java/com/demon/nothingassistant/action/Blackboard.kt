package com.demon.nothingassistant.action

import android.content.Context
import com.demon.nothingassistant.intent.Command
import org.json.JSONArray
import org.json.JSONObject

/**
 * What just happened, in a form the assistant can use: the last screenshot, last app, last setting changed (with the value it
 * had before, for "undo"), and a short log of tool -> result. It lives on the phone and expires on its own, so "delete it" or
 * "turn it back off" mean the right thing minutes later, but not days later.
 */
object Blackboard {
    class Turn(val user: String, val tool: String, val ok: Boolean, val result: String, val ts: Long)

    private var sp: android.content.SharedPreferences? = null
    private val log = ArrayList<Turn>()

    @Volatile var screenshotUri: String = ""; private set
    @Volatile var screenshotAt = 0L; private set
    @Volatile var settingKey: String = ""; private set
    @Volatile var settingPrev: String = ""; private set
    @Volatile var settingAt = 0L; private set
    @Volatile var lastKind: String = ""; private set   // screenshot | toggle | app | message | other
    @Volatile var lastKindAt = 0L; private set

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("blackboard", Context.MODE_PRIVATE)
        runCatching {
            screenshotUri = sp!!.getString("shot", "").orEmpty(); screenshotAt = sp!!.getLong("shotAt", 0)
            settingKey = sp!!.getString("sk", "").orEmpty(); settingPrev = sp!!.getString("sp", "").orEmpty(); settingAt = sp!!.getLong("sat", 0)
            val arr = JSONArray(sp!!.getString("log", "[]"))
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { log.add(Turn(it.getString("u"), it.getString("t"), it.getBoolean("o"), it.getString("r"), it.getLong("ts"))) }
        }
    }

    private fun fresh(at: Long, minutes: Int) = at > 0 && System.currentTimeMillis() - at < minutes * 60_000L

    fun recentScreenshot(): String? = screenshotUri.takeIf { it.isNotEmpty() && fresh(screenshotAt, 30) }
    fun setScreenshot(uri: String) {
        screenshotUri = uri; screenshotAt = System.currentTimeMillis(); touch("screenshot")
        sp?.edit()?.putString("shot", uri)?.putLong("shotAt", screenshotAt)?.apply()
    }
    fun clearScreenshot() { screenshotUri = ""; sp?.edit()?.remove("shot")?.apply() }

    fun setSetting(key: String, previous: String) {
        settingKey = key; settingPrev = previous; settingAt = System.currentTimeMillis(); touch("toggle")
        sp?.edit()?.putString("sk", key)?.putString("sp", previous)?.putLong("sat", settingAt)?.apply()
    }
    fun lastSetting(): Pair<String, String>? = if (settingKey.isNotEmpty() && fresh(settingAt, 30)) settingKey to settingPrev else null

    fun touch(kind: String) { lastKind = kind; lastKindAt = System.currentTimeMillis() }
    /** What the user most likely means by "it" right now. */
    fun subjectKind(): String = if (fresh(lastKindAt, 10)) lastKind else ""

    @Synchronized fun record(user: String, cmd: Command, ok: Boolean, result: String) {
        log.add(Turn(user.take(120), cmd.javaClass.simpleName, ok, result.take(120), System.currentTimeMillis()))
        while (log.size > 30) log.removeAt(0)
        runCatching {
            val arr = JSONArray()
            for (t in log) arr.put(JSONObject().put("u", t.user).put("t", t.tool).put("o", t.ok).put("r", t.result).put("ts", t.ts))
            sp?.edit()?.putString("log", arr.toString())?.apply()
        }
        when (cmd) {
            is Command.OpenApp, is Command.InstallApp, is Command.UpdateApp -> touch("app")
            is Command.Toggle, is Command.Flashlight -> touch("toggle")
            is Command.UiSend, is Command.UiMessage, is Command.SendSms -> touch("message")
            else -> {}
        }
    }

    /** "Active state" for planners: a few compact lines, never raw prose. */
    @Synchronized fun header(): String {
        val sb = StringBuilder()
        recentScreenshot()?.let { sb.append("last_screenshot=").append(it).append("; ") }
        lastSetting()?.let { sb.append("last_setting=").append(it.first).append(" (was ").append(it.second).append("); ") }
        Subject.get()?.let { sb.append("last_app=").append(it).append("; ") }
        val recent = log.filter { fresh(it.ts, 15) }.takeLast(3)
        if (recent.isNotEmpty()) sb.append("history: ").append(recent.joinToString(" | ") { "\"${it.user}\" -> ${it.tool} ${if (it.ok) "ok" else "failed"}" })
        return sb.toString().trim()
    }

    /** "Did it work?", "what did you just do?", "why did it fail?" answered from the log, with no model. */
    @Synchronized fun status(question: String): String? {
        val q = question.lowercase()
        val last = log.lastOrNull() ?: return null
        if (!fresh(last.ts, 30)) return null
        return when {
            Regex("did (?:it|that) work|was that (?:ok|okay|right)|did you (?:do|finish|manage)").containsMatchIn(q) ->
                if (last.ok) "Yes. ${last.result.ifBlank { "That went through." }}" else "No. ${last.result.ifBlank { "That didn't work." }}"
            Regex("why did (?:it|that) fail|what went wrong|why (?:didn't|did not) (?:it|that) work").containsMatchIn(q) ->
                if (!last.ok) last.result.ifBlank { "I'm not sure why." } else "It didn't fail. ${last.result}"
            Regex("what did you (?:just )?(?:do|say)|what (?:was|were) (?:that|you doing)").containsMatchIn(q) ->
                "I ran “${last.user}”. ${last.result}"
            else -> null
        }
    }
}
