package com.demon.nothingassistant.agent

import com.demon.nothingassistant.access.NothingAccess
import com.demon.nothingassistant.action.ActionResult
import com.demon.nothingassistant.ai.Brain
import com.demon.nothingassistant.debug.AppLog
import com.demon.nothingassistant.intent.Command
import com.demon.nothingassistant.intent.GlobalKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Multi-step jobs across apps: order food, book a ride, reserve a table, or anything else by reading the screen
 * and choosing the next tap. It always stops before paying or confirming a booking, and asks first.
 */
class ScreenAgent(
    private val ctx: android.content.Context,
    private val exec: suspend (Command) -> ActionResult,
    private val brain: () -> Brain?
) {
    /** Whether a screenshot is sent to the AI at each step. */
    var vision: () -> Boolean = { true }

    /** Status text while working ("Tapping Wi-Fi…"). */
    var progress: (String) -> Unit = {}
    private val ai by lazy { AiAgent(ctx, exec, brain).also { a -> a.progress = { m -> progress(m) }; a.vision = { vision() } } }

    private val payWords = Regex("\\b(pay|payment|place (?:your )?order|confirm (?:order|booking|ride|payment|purchase)|book (?:now|ride|cab|table)|request (?:ride|cab)|buy(?: now)?|checkout|check out|proceed to pay|order now|purchase|subscribe|send money|transfer)\\b", RegexOption.IGNORE_CASE)

    private val foodApps = listOf("swiggy", "zomato", "blinkit", "zepto", "instamart", "bigbasket", "dominos", "mcdonald's", "kfc")
    private val rideApps = listOf("uber", "ola", "rapido", "namma yatri", "inDrive")
    private val tableApps = listOf("zomato", "district", "dineout", "eazydiner", "swiggy dineout")

    private suspend fun openFirst(apps: List<String>): String? {
        for (a in apps) if (exec(Command.OpenApp(a)) is ActionResult.Success) return a
        return null
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    suspend fun run(goal: String): ActionResult {
        // With an AI set up, it does the whole job itself: it can see the screen and pick the best route.
        if (brain()?.configured() == true) return ai.run(goal)
        val g = goal.trim().trimEnd('.', '!')
        // Ordering food
        Regex("^(?:order|get|buy|i want|i'd like|i would like)\\s+(.+?)(?:\\s+(?:from|on|using|via|in|with)\\s+(.+?))?$", RegexOption.IGNORE_CASE).find(g)?.let { m ->
            val item = m.groupValues[1].trim()
            val named = m.groupValues[2].trim().ifEmpty { null }
            val app = if (named != null) named.also { exec(Command.OpenApp(it)).let { r -> if (r !is ActionResult.Success) return r } }
            else openFirst(foodApps) ?: return ActionResult.Failure("I couldn't find a food app like Swiggy or Zomato.")
            delay(1500)
            exec(Command.UiSearch(item, pick = true, first = true))
            return ActionResult.Success("I searched $item in ${cap(app)} and opened the first match. Pick what you like and add it. I'll never pay without asking you.")
        }
        // Booking a ride
        Regex("^(?:book|get|call|order|find)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?(?:ride|cab|taxi|auto|auto rickshaw|bike taxi|uber|ola|rapido)\\s*(?:to|for)\\s+(.+?)(?:\\s+(?:with|on|using|via|in)\\s+(.+?))?$", RegexOption.IGNORE_CASE).find(g)?.let { m ->
            val dest = m.groupValues[1].trim()
            val named = m.groupValues[2].trim().ifEmpty { null }
            val app = if (named != null) named.also { exec(Command.OpenApp(it)).let { r -> if (r !is ActionResult.Success) return r } }
            else openFirst(rideApps) ?: return ActionResult.Failure("I couldn't find Uber, Ola or Rapido on this phone.")
            delay(2000)
            exec(Command.UiSearch(dest, pick = true, first = true))
            return ActionResult.Success("I put $dest in ${cap(app)} as the destination. Choose the ride type and I'll stop before you confirm and pay.")
        }
        // Table reservation
        Regex("^(?:book|reserve|make a reservation|find)\\s+(?:me\\s+)?(?:a\\s+)?(?:table|reservation|seat)s?\\s*(?:for\\s+\\d+\\s*(?:people|persons|guests)?\\s*)?(?:at|in)\\s+(.+?)(?:\\s+(?:with|on|using|via)\\s+(.+?))?$", RegexOption.IGNORE_CASE).find(g)?.let { m ->
            val place = m.groupValues[1].trim()
            val named = m.groupValues[2].trim().ifEmpty { null }
            val app = if (named != null) named.also { exec(Command.OpenApp(it)).let { r -> if (r !is ActionResult.Success) return r } }
            else openFirst(tableApps) ?: return ActionResult.Failure("I couldn't find a dining app like Zomato or District.")
            delay(1500)
            exec(Command.UiSearch(place, pick = true, first = true))
            return ActionResult.Success("Opened $place in ${cap(app)}. Choose the time and guests and I'll stop before you confirm.")
        }
        return openEnded(g)
    }

    private val agentTools: String by lazy {
        fun tool(name: String, desc: String, vararg args: Pair<String, String>): org.json.JSONObject {
            val props = JSONObject(); val req = org.json.JSONArray()
            for ((n, d) in args) { props.put(n, JSONObject().put("type", "string").put("description", d)); req.put(n) }
            val params = JSONObject().put("type", "object").put("properties", props)
            if (req.length() > 0) params.put("required", req)
            return JSONObject().put("name", name).put("description", desc).put("parameters", params)
        }
        org.json.JSONArray()
            .put(tool("tap", "Tap one thing on the screen.", "target" to "Its label, or its number from UI"))
            .put(tool("input_text", "Type text into the focused field.", "text" to "Text"))
            .put(tool("scroll", "Scroll the screen.", "direction" to "up or down"))
            .put(tool("back", "Go back."))
            .put(tool("open_app", "Open an app.", "name" to "App name"))
            .put(tool("done", "The goal is finished, or only a payment or confirmation is left.", "summary" to "One short sentence"))
            .toString()
    }

    private class Act(val name: String, val arg: String, val say: String)

    /** One atomic action: Needle 3 first (it sees the GOAL / HIST / UI prompt), then the cloud AI if it's on. */
    private suspend fun decide(prompt: String): Act? {
        if (NeedleEngine.bundled(ctx)) {
            val r = withContext(Dispatchers.Default) { NeedleEngine.completeWith(ctx, agentTools, prompt) }
            val call = r?.optJSONArray("function_calls")?.optJSONObject(0)
            val conf = r?.optDouble("confidence", 1.0)?.let { if (it.isNaN()) 1.0 else it } ?: 0.0
            if (call != null && conf >= 0.4) {
                val a = call.optJSONObject("arguments") ?: JSONObject()
                val name = call.optString("name")
                val arg = a.optString("target").ifBlank { a.optString("text").ifBlank { a.optString("direction").ifBlank { a.optString("name").ifBlank { a.optString("summary") } } } }
                if (name in setOf("tap", "input_text", "scroll", "back", "open_app", "done")) return Act(name, arg.trim(), a.optString("summary"))
            }
        }
        val b = brain()
        if (b != null && b.configured()) {
            val q = prompt + "\nReply with ONE JSON object only: {\"do\":\"tap|input_text|scroll|back|open_app|done\",\"arg\":\"label, text, up/down, app name or summary\"}. Use done when finished or only a payment is left."
            val sys = "You operate an Android phone by reading its screen, one atomic action at a time. A screenshot of the screen may be attached: use it together with the UI list. HIST lists what you already did; LAST_ACTION_FAILED means try something different."
            val img = if (vision()) NothingAccess.instance?.let { runCatching { com.demon.nothingassistant.access.Ocr.screenJpeg(it, 900) }.getOrNull() } else null
            val reply = (if (img != null) b.ask(emptyList(), q, sys, img, fast = true).getOrNull() else null) ?: b.ask(emptyList(), q, sys, fast = true).getOrNull() ?: return null
            val o = runCatching { JSONObject(reply.substringAfter('{').let { "{" + it.substringBeforeLast('}') + "}" }) }.getOrNull() ?: return null
            val n = when (val d = o.optString("do").lowercase()) { "type", "input", "input_text" -> "input_text"; "open", "open_app" -> "open_app"; else -> d }
            return Act(n, o.optString("arg").trim(), o.optString("arg"))
        }
        return null
    }

    /** Tier 3: the state-action loop (max 10 steps). Look at the pruned screen, pick ONE action, do it, wait for the screen to settle, repeat. */
    private suspend fun openEnded(goal: String): ActionResult {
        val svc0 = NothingAccess.instance ?: return ActionResult.Failure("I need Accessibility access to work through other apps. Turn it on in Access settings.")
        if (!NeedleEngine.bundled(ctx) && brain()?.configured() != true) return ActionResult.Failure("For open-ended jobs I need Needle 3 in this build or an AI provider set up in AI settings. I can already order food, book rides and tables, and chain simple steps.")
        val hist = ArrayList<String>()
        var fails = 0
        var lastKey = ""
        var repeats = 0
        for (step in 1..10) {
            val svc = NothingAccess.instance ?: svc0
            val ui = ScreenPruner.snapshot(svc)
            val before = ScreenPruner.hash(ui)
            val prompt = ScreenPruner.prompt(goal, hist, ui, svc.frontPackage())
            val a = decide(prompt) ?: return ActionResult.Failure("I couldn't decide the next step, so I stopped. " + progress(hist))
            AppLog.d("agent", "step $step: ${a.name} '${a.arg}'")
            val key = a.name + "|" + a.arg.lowercase()
            if (key == lastKey) repeats++ else { repeats = 0; lastKey = key }
            if (repeats >= 2) return ActionResult.Failure("I kept repeating the same step, so I stopped. " + progress(hist))
            when (a.name) {
                "done" -> return ActionResult.Success(a.say.ifBlank { "That's as far as I should go. " + progress(hist) })
                "open_app" -> { val r = exec(Command.OpenApp(a.arg)); if (r !is ActionResult.Success) return r; hist += "opened ${a.arg}"; delay(1800); continue }
                "tap" -> {
                    val label = Regex("^\\[?(\\d+)]?$").find(a.arg)?.groupValues?.get(1)?.toIntOrNull()?.let { n -> ui.firstOrNull { it.n == n }?.label } ?: a.arg
                    val r = exec(Command.UiSmart(label))
                    if (r is ActionResult.Confirm) return r // Guardian: Pay, Delete... always ask the user
                    if (r is ActionResult.Failure) { hist += "tap '$label' LAST_ACTION_FAILED"; fails++ } else hist += "tapped '$label'"
                }
                "input_text" -> { exec(Command.UiType(a.arg)); hist += "typed '${a.arg.take(20)}'" }
                "scroll" -> { exec(Command.UiScrollEx(if (a.arg.lowercase().startsWith("u")) "up" else "down")); hist += "scrolled ${a.arg}" }
                "back" -> { exec(Command.GlobalAction(GlobalKey.BACK)); hist += "went back" }
                else -> return ActionResult.Failure("I wasn't sure what to do next. " + progress(hist))
            }
            delay(800)
            val after = ScreenPruner.hash(ScreenPruner.snapshot(NothingAccess.instance ?: svc))
            if (after == before && a.name != "input_text") {
                // Screen-change detection: nothing moved, so tell the model and let it try something else.
                if (!hist.last().contains("LAST_ACTION_FAILED")) hist[hist.size - 1] = hist.last() + " LAST_ACTION_FAILED(no change)"
                fails++
            } else if (!hist.last().contains("FAILED")) fails = 0
            if (fails >= 3) return ActionResult.Failure("I got stuck, so I stopped. " + progress(hist))
        }
        return ActionResult.Success("I worked on it for a while and stopped. " + progress(hist))
    }

    private fun progress(log: List<String>) = if (log.isEmpty()) "" else "So far: " + log.takeLast(4).joinToString(", ") + "."
}
