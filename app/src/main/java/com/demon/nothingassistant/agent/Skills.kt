package com.demon.nothingassistant.agent

import android.content.Context
import com.demon.nothingassistant.action.Matcher
import com.demon.nothingassistant.debug.AppLog
import com.demon.nothingassistant.intent.Command
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-learning memory of what worked. When Needle 3 or your cloud AI turns a sentence into a plan and the plan succeeds,
 * the plan is saved against that sentence. Next time the same (or nearly the same) sentence is replayed straight away,
 * offline, with no model. A saved plan that fails twice is forgotten. Nothing leaves the phone; "forget skills" clears it.
 */
object Skills {
    private class Skill(var pattern: String, var calls: String, var uses: Int, var fails: Int, var ts: Long)

    private var sp: android.content.SharedPreferences? = null
    private val list = ArrayList<Skill>()

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("skills", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(sp!!.getString("v1", "[]"))
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { list.add(Skill(it.getString("p"), it.getString("c"), it.optInt("u", 1), it.optInt("f", 0), it.optLong("t"))) }
        }
    }

    @Synchronized private fun save() {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("p", s.pattern).put("c", s.calls).put("u", s.uses).put("f", s.fails).put("t", s.ts))
        sp?.edit()?.putString("v1", arr.toString())?.apply()
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("^(?:hey |ok |okay |please |can you |could you |would you )+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun numbers(t: String) = Regex("\\d+").findAll(t).map { it.value }.toList()

    /** A saved plan for this sentence, as JSON calls, or null. Needs the same numbers and a close wording. */
    @Synchronized fun find(text: String): Pair<String, JSONArray>? {
        val n = norm(text)
        if (n.length < 6) return null
        var best: Skill? = null
        var bestScore = 0.0
        for (s in list) {
            if (s.fails >= 2) continue
            if (numbers(s.pattern) != numbers(n)) continue
            val sc = if (s.pattern == n) 1.0 else Matcher.jaroWinkler(s.pattern, n) * 0.5 + Matcher.levenshteinRatio(s.pattern, n) * 0.5
            if (sc > bestScore) { best = s; bestScore = sc }
        }
        val b = best ?: return null
        if (bestScore < 0.93) return null
        return runCatching { b.pattern to JSONArray(b.calls) }.getOrNull()
    }

    /** Remember a plan that worked. Only multi-step or non-trivial plans are worth keeping. */
    @Synchronized fun learn(text: String, calls: JSONArray?) {
        if (calls == null || calls.length() == 0) return
        val n = norm(text)
        if (n.length < 6) return
        val raw = calls.toString()
        val old = list.firstOrNull { it.pattern == n }
        if (old != null) { old.calls = raw; old.uses++; old.fails = 0; old.ts = System.currentTimeMillis() }
        else { list.add(Skill(n, raw, 1, 0, System.currentTimeMillis())); AppLog.d("skills", "learned '$n'") }
        while (list.size > 150) list.remove(list.minByOrNull { it.uses * 1_000_000_000L + it.ts })
        save()
    }

    @Synchronized fun failed(pattern: String) {
        list.firstOrNull { it.pattern == pattern }?.let { it.fails++; if (it.fails >= 2) list.remove(it); save() }
    }

    @Synchronized fun succeeded(pattern: String) { list.firstOrNull { it.pattern == pattern }?.let { it.uses++; it.fails = 0; it.ts = System.currentTimeMillis(); save() } }

    @Synchronized fun clear() { list.clear(); save() }
    @Synchronized fun count() = list.size

    fun toCommand(calls: JSONArray): Command? = Tools.toPlan(calls)
}
