package com.demon.nothingassistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the assistant knows about the user, learned from conversation and from what they do. It grows with use: facts the user states
 * ("my sister is Riya", "I'm vegetarian"), explicit "remember that…" notes, and which apps they open most. A short ranked block of it
 * goes into the AI's instructions so answers get personal, and the names in it feed the speech recogniser so they are heard correctly.
 * Stored only on the phone. Can be switched off, listed, edited and cleared.
 */
object Mem {
    class Fact(val text: String, var ts: Long, var hits: Int, val explicit: Boolean)

    private var sp: android.content.SharedPreferences? = null
    private val facts = ArrayList<Fact>()
    private val apps = HashMap<String, Int>()
    @Volatile var on: () -> Boolean = { true }
    private var lastLearn = 0L

    @Synchronized fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("assistant_memory", Context.MODE_PRIVATE)
        runCatching {
            val a = JSONArray(sp!!.getString("facts", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); facts.add(Fact(o.getString("t"), o.optLong("ts"), o.optInt("h"), o.optBoolean("e"))) }
            val m = JSONObject(sp!!.getString("apps", "{}"))
            m.keys().forEach { apps[it] = m.getInt(it) }
        }
    }

    private fun save() {
        val a = JSONArray()
        facts.forEach { a.put(JSONObject().put("t", it.text).put("ts", it.ts).put("h", it.hits).put("e", it.explicit)) }
        sp?.edit()?.putString("facts", a.toString())?.putString("apps", JSONObject(apps as Map<*, *>).toString())?.apply()
    }

    @Synchronized fun all(): List<Fact> = facts.sortedByDescending { it.ts }
    @Synchronized fun count() = facts.size

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    private fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return a.equals(b, true)
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size) >= 0.75
    }

    @Synchronized fun add(text: String, explicit: Boolean = false) {
        val t = text.trim().trimEnd('.').take(160)
        if (t.length < 4) return
        val same = facts.indexOfFirst { similar(it.text, t) }
        if (same >= 0) { val old = facts[same]; facts[same] = Fact(t, System.currentTimeMillis(), old.hits, old.explicit || explicit) }
        else facts.add(Fact(t, System.currentTimeMillis(), 0, explicit))
        while (facts.size > 120) facts.remove(facts.filter { !it.explicit }.minByOrNull { it.ts + it.hits * 86_400_000L } ?: facts.minByOrNull { it.ts }!!)
        save()
    }

    /** Removes facts that mention the words in [what]. Returns how many went. */
    @Synchronized fun forget(what: String): Int {
        val w = words(what)
        if (w.isEmpty()) return 0
        val gone = facts.filter { f -> words(f.text).intersect(w).isNotEmpty() }
        facts.removeAll(gone.toSet())
        if (gone.isNotEmpty()) save()
        return gone.size
    }

    @Synchronized fun remove(f: Fact) { facts.remove(f); save() }
    @Synchronized fun clear() { facts.clear(); apps.clear(); save() }

    @Synchronized fun noteApp(name: String) {
        val n = name.trim().lowercase()
        if (n.length < 2) return
        apps[n] = (apps[n] ?: 0) + 1
        if (apps.size > 40) apps.entries.minByOrNull { it.value }?.let { apps.remove(it.key) }
        save()
    }

    @Synchronized fun topApps(n: Int = 3): List<String> = apps.entries.filter { it.value >= 3 }.sortedByDescending { it.value }.take(n).map { it.key }

    /** The ranked block for the AI's instructions: facts that share words with what was just said come first, then recent and often-used ones. */
    @Synchronized fun block(query: String, budget: Int = 700): String {
        if (!on() || (facts.isEmpty() && apps.isEmpty())) return ""
        val q = words(query)
        val ranked = facts.sortedByDescending { f ->
            val rel = words(f.text).intersect(q).size * 1000
            rel + (if (f.explicit) 300 else 0) + f.hits * 20 + ((f.ts / 3_600_000L) % 100000).toInt() / 1000
        }
        val sb = StringBuilder(" What you know about the user (use it naturally when it helps, never recite it, never mention that you keep notes): ")
        val used = ArrayList<Fact>()
        for (f in ranked) { if (sb.length + f.text.length + 3 > budget) break; sb.append(f.text).append("; "); used.add(f) }
        topApps().takeIf { it.isNotEmpty() }?.let { sb.append("They often open ").append(it.joinToString(", ")).append("."); }
        used.forEach { if (words(it.text).intersect(q).isNotEmpty()) it.hits++ }
        return sb.toString()
    }

    /** Names and words to help the speech recogniser (Whisper prompt): capitalised words from the facts, plus favourite apps. */
    @Synchronized fun vocabulary(): String {
        if (!on()) return ""
        val names = facts.flatMap { Regex("\\b[A-Z][a-zA-Z]{2,}\\b").findAll(it.text).map { m -> m.value }.toList() }.distinct().take(20)
        val a = topApps(6)
        return (names + a).joinToString(", ")
    }

    private val personal = Regex(
        "\\b(my|i am|i'm|i m|i live|i work|i like|i love|i hate|i prefer|i usually|i always|i never|call me|mera|meri|mere|main|mujhe|i have|i study|i drive|i wake|i sleep)\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when a sentence might hold something worth keeping (so the AI is only asked about those). */
    fun worthLearning(user: String) = user.length in 8..300 && personal.containsMatchIn(user) && !user.trim().endsWith("?")

    /** Asks the AI, in fast mode and at most every 20 seconds, to pull stable facts about the user out of what they just said. */
    suspend fun learn(brain: Brain, user: String) {
        if (!on() || !worthLearning(user) || !brain.configured()) return
        val now = System.currentTimeMillis()
        if (now - lastLearn < 20_000) return
        lastLearn = now
        val sys = "Extract lasting personal facts or preferences about the USER from their message (name, family, home city, job, habits, tastes, diet, language, routine, devices). " +
            "Skip anything temporary, questions, commands, and opinions about the world. One short third-person line per fact, like 'Their sister is Riya.' or 'Prefers Hindi replies.' " +
            "Output only the lines, or exactly NONE."
        val out = brain.ask(emptyList(), user, system = sys, fast = true).getOrNull()?.trim().orEmpty()
        if (out.isEmpty() || out.equals("NONE", true)) return
        out.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.length in 6..160 && !it.equals("NONE", true) }.take(3).forEach { add(it) }
    }

    private val rememberRe = Regex("^(?:please )?(?:remember|note|save|yaad rakh(?:o|na)?|yaad rakho)(?: that| ki)? (.{3,})$", RegexOption.IGNORE_CASE)
    private val forgetRe = Regex("^(?:please )?(?:forget|delete|remove|bhool ja(?:o|na)?) (?:that |about |what i said about )?(.{3,})$", RegexOption.IGNORE_CASE)
    private val listRe = Regex("^(?:what do you (?:remember|know) about me|what have you learned about me|what do you know about me|tell me what you remember)\\??$", RegexOption.IGNORE_CASE)
    private val wipeRe = Regex("^(?:forget everything(?: about me)?|clear (?:my )?memory|erase (?:my )?memory|forget all(?: of)? (?:it|that)? ?about me)$", RegexOption.IGNORE_CASE)

    /** Handles "remember that…", "forget…", "what do you know about me". Returns the spoken answer, or null if it isn't a memory command. */
    fun command(raw: String): String? {
        val t = raw.trim().trimEnd('.', '!')
        if (wipeRe.matches(t)) { val n = count(); clear(); return if (n == 0) "I wasn't keeping anything about you." else "Done. I've forgotten everything I knew about you." }
        if (listRe.matches(t)) {
            val l = all().take(6)
            return if (l.isEmpty()) "Nothing yet. Tell me about yourself, or say remember that, and I'll keep it."
            else "Here's what I know. " + l.joinToString(" ") { it.text.trimEnd('.') + "." }
        }
        rememberRe.find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.startsWith("to ", true) || Regex("^(?:me|the|a|an) ", RegexOption.IGNORE_CASE).containsMatchIn(what).not() && what.split(' ').size < 2) return null
            if (what.startsWith("to ", true)) return null // "remember to call mom" is a reminder, not a fact
            add(what.replaceFirstChar { it.uppercase() }, explicit = true)
            return "Got it, I'll remember that."
        }
        forgetRe.find(t)?.let { m ->
            val what = m.groupValues[1]
            if (Regex("^(?:what you|your |the (?:chat|conversation)|this chat|skills|everything$)", RegexOption.IGNORE_CASE).containsMatchIn(what)) return null
            val n = forget(what)
            return if (n == 0) "I don't have anything like that saved." else "Done, forgotten."
        }
        return null
    }
}
