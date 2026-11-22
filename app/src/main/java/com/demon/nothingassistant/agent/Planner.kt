package com.demon.nothingassistant.agent

import android.content.Context
import com.demon.nothingassistant.access.NothingAccess
import com.demon.nothingassistant.ai.Brain
import com.demon.nothingassistant.ai.Turn
import com.demon.nothingassistant.debug.AppLog
import com.demon.nothingassistant.intent.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a sentence into tool calls. The hybrid order is decided by the controller:
 * Needle 3 (on the phone) -> cloud AI (if switched on) -> fixed grammar -> plain answer. Any plan, from either brain,
 * can hold several calls (open, wait, search, install...) and runs as one chain.
 */
class Planner(private val context: Context, private val brain: Brain) {

    class Plan(val command: Command, val confidence: Double, val source: String, val raw: org.json.JSONArray? = null)

    /** 1. Needle 3: instant and offline. Null when it isn't in this build or isn't sure. */
    suspend fun needle(text: String): Plan? {
        if (!NeedleEngine.bundled(context)) return null
        val r = withContext(Dispatchers.Default) { NeedleEngine.complete(context, text) } ?: return null
        val conf = r.optDouble("confidence", 1.0).let { if (it.isNaN()) 1.0 else it }
        val cmd = Tools.toPlan(r.optJSONArray("function_calls"))
        AppLog.d("needle", "'${text.take(50)}' -> ${cmd?.javaClass?.simpleName} confidence $conf")
        if (cmd == null || conf < 0.45) return null
        return Plan(cmd, conf, "needle", r.optJSONArray("function_calls"))
    }

    private fun screenContext(): String {
        val svc = NothingAccess.instance ?: return "Screen control is off."
        return runCatching {
            val labels = svc.candidates().map { it.label.trim() }.filter { it.length in 2..40 }.distinct().take(28)
            "App in front: ${svc.frontPackage()}. Visible on screen: " + labels.joinToString(" | ")
        }.getOrDefault("")
    }

    /** 2. The user's own cloud AI, shown the tools, the recent conversation and what is on screen. */
    /** Whether a screenshot is sent to the AI for screen-related requests. */
    var vision: () -> Boolean = { true }

    private val screenish = Regex("\\b(screen|this|that|here|button|tap|click|press|scroll|type|fill|select|reply|translate|summari[sz]e|read|these|those|above|below|it)\\b", RegexOption.IGNORE_CASE)

    suspend fun cloud(text: String, history: List<Turn>): Plan? {
        if (!brain.configured()) return null
        val now = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date())
        val system = "You are the planner of a voice assistant on an Android phone (India). Turn the user's request into tool calls. " +
            "Reply with ONLY JSON: {\"calls\":[{\"name\":\"tool\",\"arguments\":{...}}]}. " +
            "Rules: use several calls, in order, for multi-step requests; put a wait call wherever the user says wait; " +
            "to install or update an app use install_app (one call); to tap something visible use tap_on_screen with its exact on-screen label; " +
            "use the conversation so far to resolve words like it, that, him, there; " +
            "for anything that needs working through screens or apps (changing a setting you have no tool for, finding an option, filling a form, doing something inside an app, anything multi-step on screen) use ONE do_in_apps call with the whole goal: the AI agent sees the screen and finds the best way itself; " +
            "to restart, reboot or shut down the phone use power_control; for any other phone or system job that no tool covers use run_shell with a fitting shell command (you have full system access; risky commands are confirmed with the user automatically), never answer that you can't; " +
            "if a screenshot is attached it shows the screen right now: use it to decide what to tap or type; " +
            "if the user is only chatting or asking a question to be answered, reply {\"calls\":[]}. Never invent tools.\n" +
            "Now: $now.\n${screenContext()}\nPhone state: ${com.demon.nothingassistant.action.Blackboard.header()}\nTools:\n" + Tools.promptList()
        val compact = com.demon.nothingassistant.ai.Summarizer.compactHistory(history)
        val img = if (vision() && screenish.containsMatchIn(text)) NothingAccess.instance?.let { runCatching { com.demon.nothingassistant.access.Ocr.screenJpeg(it) }.getOrNull() } else null
        val reply = (if (img != null) brain.ask(compact, text, system, img).getOrNull() else null)
            ?: brain.ask(compact, text, system).getOrNull() ?: return null
        val json = reply.substringAfter('{', "").let { if (it.isEmpty()) null else "{" + it.substringBeforeLast('}') + "}" } ?: return null
        val rawCalls = runCatching { JSONObject(json).optJSONArray("calls") }.getOrNull()
        val cmd = runCatching { Tools.toPlan(rawCalls) }.getOrNull() ?: return null
        AppLog.d("cloudplan", "'${text.take(50)}' -> ${cmd.javaClass.simpleName}")
        return Plan(cmd, 0.7, "cloud", rawCalls)
    }
}
