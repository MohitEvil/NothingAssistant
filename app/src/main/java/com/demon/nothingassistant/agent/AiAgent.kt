package com.demon.nothingassistant.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.demon.nothingassistant.access.NothingAccess
import com.demon.nothingassistant.access.Ocr
import com.demon.nothingassistant.action.ActionResult
import com.demon.nothingassistant.action.Targeting
import com.demon.nothingassistant.ai.Brain
import com.demon.nothingassistant.debug.AppLog
import com.demon.nothingassistant.intent.Command
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The AI agent. It looks at the whole screen (a numbered map of everything on it, plus a screenshot when the AI can see pictures),
 * decides the best next move, does it, checks what happened, and carries on until the job is done. Wrong window? It sees that and
 * backs out, searches Settings, or takes another route. It never pays, and asks before anything that deletes or resets.
 */
class AiAgent(
    private val ctx: Context,
    private val exec: suspend (Command) -> ActionResult,
    private val brain: () -> Brain?
) {
    var progress: (String) -> Unit = {}
    var vision: () -> Boolean = { true }

    private val approved = HashSet<String>()
    private val pay = Regex("\\b(pay|payment|place (?:your )?order|confirm (?:order|booking|ride|payment|purchase)|book (?:now|ride|cab|table)|request (?:ride|cab)|buy(?: now)?|checkout|check out|proceed to pay|order now|purchase|subscribe|send money|transfer|donate)\\b", RegexOption.IGNORE_CASE)
    private val danger = Regex("\\b(delete|erase|wipe|reset|factory|uninstall|remove account|format|clear data|clear storage|sign out|log out|forget|deactivate)\\b", RegexOption.IGNORE_CASE)

    /** One thing on screen, with the numbers the AI refers to. */
    private class El(val id: Int, val kind: String, val label: String, val x: Int, val y: Int, val cand: NothingAccess.Cand?, val on: Boolean?)

    private class Seen(val text: String, val els: List<El>, val title: String, val pkg: String, val sig: Int)

    private suspend fun look(svc: NothingAccess?): Seen {
        if (svc == null) return Seen("Screen control is off, so the screen can't be read. Only open_app, open_settings, intent and shell actions work.", emptyList(), "", "", 0)
        val list = ArrayList<El>()
        val seen = HashSet<String>()
        val ordered = Targeting.reading(svc.candidates())
        for (c in ordered) {
            val label = c.label.trim().replace(Regex("\\s+"), " ").take(60)
            val kind = when { c.editable -> "field"; c.checkable -> "switch"; c.clickable -> "button"; else -> "text" }
            if (kind == "text" && label.length < 2) continue
            if (label.isEmpty() && kind == "text") continue
            if (!seen.add(kind + "|" + label.lowercase() + "|" + c.hit.centerY() / 6)) continue
            val on = if (c.checkable) runCatching { c.node.isChecked }.getOrNull() else null
            list.add(El(list.size + 1, kind, label, c.hit.centerX(), c.hit.centerY(), c, on))
            if (list.size >= 90) break
        }
        // Games, web pages and custom views draw their own text: add what the screenshot reader finds.
        if (list.count { it.label.isNotEmpty() } < 7) {
            runCatching { Ocr.read(svc) }.getOrNull()?.take(40)?.forEach { l ->
                if (l.text.length >= 2) list.add(El(list.size + 1, "drawn", l.text.take(60), l.box.centerX(), l.box.centerY(), null, null))
            }
        }
        val dm = svc.resources.displayMetrics
        val pkg = svc.frontPackage()
        val title = svc.topTitle()
        val text = buildString {
            append("APP: ").append(pkg).append("   TITLE: ").append(title.ifBlank { "(none)" }).append("   SCREEN: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append('\n')
            append("CAN SCROLL: ").append(if (svc.canScroll()) "yes" else "no").append('\n')
            append("ELEMENTS (id kind 'label' @x,y):\n")
            list.forEach { e ->
                append('[').append(e.id).append("] ").append(e.kind).append(" '").append(e.label).append("'")
                e.on?.let { append(if (it) " ON" else " OFF") }
                append(" @").append(e.x).append(',').append(e.y).append('\n')
            }
        }
        return Seen(text, list, title, pkg, svc.signature())
    }

    private fun system(): String =
        "You are the agent inside a voice assistant on an Android phone (Nothing OS, India). You complete the user's goal by operating the phone one step at a time. " +
            "Each turn you get GOAL, HISTORY (what you did and what happened) and the current SCREEN as a numbered list of elements, and usually a screenshot. " +
            "Think about where the thing the user wants lives, then choose ONE action. Reply with ONLY a JSON object: " +
            "{\"thought\":\"short reasoning\",\"action\":\"...\", ...arguments}.\n" +
            "Actions:\n" +
            "tap {id} - tap element by number (best), or {x,y} for a spot, or {text} to tap by visible label\n" +
            "long_press {id} | {x,y}\n" +
            "type {text, id?, submit?} - type into the focused field (or the field id); submit:true presses Enter/Search after\n" +
            "scroll {direction: up|down|left|right} - scroll the content\n" +
            "scroll_to_find {text} - keep scrolling until that text is visible\n" +
            "swipe {x1,y1,x2,y2}\n" +
            "system {name: back|home|recents|notifications|quick_settings|power_menu|lock|screenshot}\n" +
            "open_app {name}\n" +
            "open_settings {page} - open a Settings page by name, e.g. \"wifi\", \"default apps\", \"battery\", \"accessibility\"\n" +
            "settings_search {query} - open Settings and type the query into its search box (use when you don't know where an option is)\n" +
            "intent {action, uri?, package?} - start any Android intent, e.g. android.settings.* pages or ACTION_VIEW links\n" +
            "shell {command} - run a shell command with system access (Shizuku/root). Risky commands are confirmed with the user automatically.\n" +
            "wait {seconds} - up to 8\n" +
            "answer {text} - the goal was a question about the screen: say the answer and finish\n" +
            "done {summary} - the goal is complete (one short spoken sentence, in the user's language)\n" +
            "fail {reason} - it can't be done (one short spoken sentence)\n" +
            "Rules: prefer direct routes (open_settings, intent, shell, open_app) over long tapping. If an option is not where you expected, don't guess blindly: use settings_search or go back and look at the screen again. " +
            "After a tap look at whether the screen changed as expected; if you are on the wrong screen, press back or take another route. Never repeat the same failing action more than twice. " +
            "Check results: for a switch, make sure its state ended up ON/OFF as wanted. Never press pay, buy, order, confirm-payment or send-money buttons; finish with done and tell the user what is left. " +
            "Keep the user's language for done/fail/answer texts. Android tips: Settings has a search box at the top; Developer options are under System; quick toggles live in quick_settings; Nothing OS keeps Wi-Fi under Network & internet and default apps under Apps."

    private fun parse(reply: String): JSONObject? = runCatching {
        val a = reply.indexOf('{'); val b = reply.lastIndexOf('}')
        if (a < 0 || b <= a) null else JSONObject(reply.substring(a, b + 1))
    }.getOrNull()

    private fun describe(o: JSONObject): String = when (o.optString("action")) {
        "tap" -> "tap " + (o.opt("id")?.let { "#$it" } ?: o.optString("text").ifBlank { "${o.optInt("x")},${o.optInt("y")}" })
        "type" -> "type '" + o.optString("text").take(24) + "'"
        "scroll" -> "scroll " + o.optString("direction")
        "scroll_to_find" -> "scroll to find '" + o.optString("text") + "'"
        "open_app" -> "open " + o.optString("name")
        "open_settings" -> "open settings: " + o.optString("page")
        "settings_search" -> "search settings: " + o.optString("query")
        "system" -> o.optString("name")
        "shell" -> "run: " + o.optString("command").take(40)
        else -> o.optString("action")
    }

    private fun friendly(o: JSONObject, e: El?): String = when (o.optString("action")) {
        "tap" -> "Tapping " + (e?.label?.ifBlank { null } ?: o.optString("text").ifBlank { "the screen" }) + "…"
        "type" -> "Typing…"
        "scroll", "scroll_to_find" -> "Scrolling…"
        "open_app" -> "Opening " + o.optString("name") + "…"
        "open_settings", "settings_search" -> "Looking in Settings…"
        "system" -> "Going " + o.optString("name").replace('_', ' ') + "…"
        "shell" -> "Running a command…"
        else -> "Working on it…"
    }

    suspend fun run(goalRaw: String): ActionResult {
        var goal = goalRaw.trim()
        Regex("^\\[ok:(.+?)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(goal)?.let { approved += it.groupValues[1].lowercase(); goal = it.groupValues[2].trim() }
        val b = brain()
        if (b == null || !b.configured()) return ActionResult.Failure("To work through the screen I need the AI turned on. Kilo is free and needs no key: switch it on in AI settings.")
        val hist = ArrayList<String>()
        val started = System.currentTimeMillis()
        var sameCount = 0
        var lastKey = ""
        var noImage = false
        var lastSig = 0

        for (step in 1..26) {
            if (System.currentTimeMillis() - started > 240_000) return ActionResult.Failure("I worked on it for a while and stopped. " + recap(hist))
            val svc = NothingAccess.instance
            val now = look(svc)
            val img = if (!noImage && vision() && svc != null) runCatching { Ocr.screenJpeg(svc, 1000) }.getOrNull() else null
            val q = "GOAL: $goal\nHISTORY:\n" + (if (hist.isEmpty()) "(nothing yet)" else hist.takeLast(8).joinToString("\n")) + "\n\nSCREEN NOW:\n" + now.text
            var reply = if (img != null) b.ask(emptyList(), q, system(), img).getOrNull() else null
            if (reply == null && img != null) noImage = true
            if (reply == null) reply = b.ask(emptyList(), q, system()).getOrNull()
            if (reply == null) return ActionResult.Failure("The AI didn't answer, so I stopped. " + recap(hist))
            val o0 = parse(reply)
            if (o0 == null) { hist += "$step. (your reply was not valid JSON, answer with one JSON object)"; continue }
            val o: JSONObject = o0
            val action = o.optString("action")
            AppLog.d("aiagent", "step $step: ${describe(o)}  // ${o.optString("thought").take(80)}")

            val key = describe(o)
            if (key == lastKey && now.sig == lastSig) sameCount++ else sameCount = 0
            lastKey = key; lastSig = now.sig
            if (sameCount >= 2) { hist += "$step. ${describe(o)} -> REPEATED with no change. Use a different approach (scroll, settings_search, another element, back)."; if (sameCount >= 4) return ActionResult.Failure("I got stuck and stopped. " + recap(hist)); continue }

            val el = if (action == "tap" || action == "long_press") o.opt("id")?.toString()?.toIntOrNull()?.let { id -> now.els.firstOrNull { it.id == id } } else null
            progress(friendly(o, el))

            when (action) {
                "done" -> return ActionResult.Success(o.optString("summary").ifBlank { "Done." })
                "answer" -> return ActionResult.Success(o.optString("text").ifBlank { "I couldn't tell." })
                "fail" -> return ActionResult.Failure(o.optString("reason").ifBlank { "I couldn't do that." })
            }
            val out = perform(o, action, el, now, svc)
            if (out.terminal != null) return out.terminal
            // Let the screen settle, then say what changed so the AI can judge.
            delay(450)
            var after = svc?.signature() ?: 0
            var waited = 0
            while (waited < 1400 && svc != null) { delay(250); waited += 250; val s2 = svc.signature(); if (s2 == after) break; after = s2 }
            val title2 = svc?.topTitle().orEmpty()
            val changed = svc != null && after != now.sig
            hist += "$step. ${describe(o)}${if (o.optString("thought").isNotBlank()) " (" + o.optString("thought").take(60) + ")" else ""} -> ${out.note}; " +
                (if (svc == null) "" else if (changed) "screen changed" + (if (title2 != now.title && title2.isNotBlank()) " to '$title2'" else "") else "screen did NOT change")
        }
        return ActionResult.Success("I worked on it as far as I could. " + recap(hist))
    }

    private fun recap(h: List<String>) = if (h.isEmpty()) "" else "Last steps: " + h.takeLast(3).joinToString(" ") { it.substringAfter(". ").substringBefore(" ->") } + "."

    private class Out(val note: String, val terminal: ActionResult? = null)

    private suspend fun perform(o: JSONObject, action: String, el: El?, now: Seen, svc: NothingAccess?): Out {
        suspend fun needSvc(): NothingAccess? = svc
        when (action) {
            "tap", "long_press" -> {
                val s = needSvc() ?: return Out("screen control is off")
                var target = el
                var x = o.optInt("x", -1); var y = o.optInt("y", -1)
                val txt = o.optString("text")
                if (target == null && txt.isNotBlank()) {
                    target = now.els.map { it to Targeting.score(txt, it.label) }.filter { it.second >= 64 }.maxByOrNull { it.second }?.first
                    if (target == null) return Out("no element matches '$txt'")
                }
                val label = target?.label.orEmpty()
                if (label.isNotBlank()) {
                    if (pay.containsMatchIn(label)) return Out("stopped before '$label'", ActionResult.Success("I got as far as the '$label' button and stopped. Press it yourself when you're ready."))
                    if (danger.containsMatchIn(label) && label.lowercase() !in approved) {
                        return Out("needs confirmation", ActionResult.Confirm("Press '$label'?", "This looks like it deletes or resets something.", "This will press $label. Is that okay?", Command.Task("[ok:$label] " + "continue the task and press $label"), "Okay, I won't."))
                    }
                }
                if (action == "long_press") {
                    val n = target?.cand?.node
                    val ok = if (n != null) s.longClick(n) else (x >= 0 && s.holdAt(x.toFloat(), y.toFloat(), 800))
                    return Out(if (ok) "long-pressed" else "long press failed")
                }
                if (target != null) {
                    val c = target.cand
                    val ok = if (c != null) s.clickSure(c.node) else s.tapAt(target.x.toFloat(), target.y.toFloat()).also { delay(300) }
                    return Out(if (ok) "tapped '${target.label}'" else "tap on '${target.label}' failed")
                }
                if (x >= 0 && y >= 0) { val ok = s.tapAt(x.toFloat(), y.toFloat()); delay(300); return Out(if (ok) "tapped $x,$y" else "tap failed") }
                return Out("tap needs an id, text or x,y")
            }
            "type" -> {
                val s = needSvc() ?: return Out("screen control is off")
                val text = o.optString("text")
                el?.let { }
                o.opt("id")?.toString()?.toIntOrNull()?.let { id -> now.els.firstOrNull { it.id == id }?.let { f -> f.cand?.let { s.click(it.node) } ?: s.tapAt(f.x.toFloat(), f.y.toFloat()); delay(350) } }
                val r = exec(Command.UiType(text))
                if (o.optBoolean("submit", false)) { delay(250); s.imeEnter() }
                return Out(if (r is ActionResult.Failure) "typing failed: ${r.message.take(60)}" else "typed '${text.take(24)}'")
            }
            "scroll" -> {
                val d = o.optString("direction", "down").lowercase()
                val r = exec(Command.UiScrollEx(d))
                return Out(if (r is ActionResult.Failure) "could not scroll $d (${r.message.take(50)})" else "scrolled $d")
            }
            "scroll_to_find" -> {
                val s = needSvc() ?: return Out("screen control is off")
                val t = o.optString("text")
                val n = s.scrollToFind(listOf(t), 14)
                return Out(if (n != null) "found '$t' on screen" else "'$t' not found after scrolling")
            }
            "swipe" -> {
                val s = needSvc() ?: return Out("screen control is off")
                val ok = s.swipeXY(o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(), o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat())
                return Out(if (ok) "swiped" else "swipe failed")
            }
            "system" -> {
                val s = needSvc() ?: return Out("screen control is off")
                val ok = s.globalByName(o.optString("name"))
                return Out(if (ok) "pressed ${o.optString("name")}" else "couldn't press ${o.optString("name")}")
            }
            "open_app" -> {
                val r = exec(Command.OpenApp(o.optString("name")))
                if (r is ActionResult.Confirm) return Out("needs the user to choose", r)
                if (r is ActionResult.Failure) return Out("could not open: ${r.message.take(60)}")
                delay(1200)
                return Out("opened ${o.optString("name")}")
            }
            "open_settings" -> {
                val r = exec(Command.OpenSetting(o.optString("page")))
                if (r is ActionResult.Confirm) return Out("needs the user to choose", r)
                if (r is ActionResult.Failure) return Out("no such settings page (${r.message.take(60)}); try settings_search")
                delay(900)
                return Out("opened settings page '${o.optString("page")}'")
            }
            "settings_search" -> {
                val s = needSvc() ?: return Out("screen control is off")
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                delay(1100)
                val t = s.findSearchTrigger() ?: return Out("opened Settings but found no search box")
                if (!t.second) { s.click(t.first); delay(700) }
                val ok = s.setText(o.optString("query"))
                delay(900)
                return Out(if (ok) "typed '${o.optString("query")}' into the Settings search; results are listed" else "couldn't type into the search box")
            }
            "intent" -> {
                val act = o.optString("action")
                if (act.isBlank()) return Out("intent needs an action")
                val i = Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                o.optString("uri").takeIf { it.isNotBlank() }?.let { i.data = Uri.parse(it) }
                o.optString("package").takeIf { it.isNotBlank() }?.let { i.setPackage(it) }
                val ok = runCatching { ctx.startActivity(i) }.isSuccess
                delay(900)
                return Out(if (ok) "started $act" else "that intent isn't available")
            }
            "shell" -> {
                val r = exec(Command.Priv("SHELL", o.optString("command")))
                return when (r) {
                    is ActionResult.Confirm -> Out("needs confirmation", r)
                    is ActionResult.Failure -> Out("failed: ${r.message.take(120)}")
                    else -> Out("ok: " + ((r as? ActionResult.Success)?.message ?: "").take(160))
                }
            }
            "wait" -> { delay((o.optInt("seconds", 2).coerceIn(1, 8)) * 1000L); return Out("waited") }
        }
        return Out("unknown action '$action'")
    }
}
