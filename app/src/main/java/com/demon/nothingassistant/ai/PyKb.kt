package com.demon.nothingassistant.ai

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Offline question and answer knowledge taken from PyBot (huggingface/pybot, Apache-2.0): about 6,000 hand-written
 * answers for everyday questions (science, health, cooking, home, money, travel, advice). Same matching rule as PyBot:
 * a phrase of a key scores 3, a single word 2, and a line needs 4, so two different signals are needed
 * and a line that only mentions a topic in passing is not answered with the wrong fact.
 */
object PyKb {
    private class Entry(val slots: Array<Array<String>>, val answer: String, val avoid: Array<String>)

    private var entries: Array<Entry> = emptyArray()
    private var index: HashMap<String, IntArray> = HashMap()
    private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var failed = false

    fun init(context: Context) { appContext = context.applicationContext }

    private val stop = setOf("a", "an", "the", "is", "are", "was", "were", "of", "in", "on", "at", "to", "for", "and", "or", "what", "whats", "who", "which", "when",
        "where", "why", "how", "does", "do", "did", "me", "my", "i", "you", "it", "its", "this", "that", "there", "with", "be", "can", "should", "would", "will", "have",
        "has", "had", "get", "got", "go", "s", "so", "if", "not", "no", "yes", "about", "from", "tell", "please", "your", "we", "they", "them", "he", "she", "his", "her")

    fun stem(w: String): String {
        var x = w
        if (x.length > 4 && x.endsWith("ies")) x = x.dropLast(3) + "y"
        else if (x.length > 4 && x.endsWith("ing")) x = x.dropLast(3)
        else if (x.length > 4 && x.endsWith("es") && !x.endsWith("ss")) x = x.dropLast(2)
        else if (x.length > 3 && x.endsWith("s") && !x.endsWith("ss")) x = x.dropLast(1)
        else if (x.length > 4 && x.endsWith("ed")) x = x.dropLast(2)
        return x
    }

    private fun norm(s: String) = s.lowercase().replace("’", "").replace("'", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    @Synchronized
    private fun load() {
        if (loaded || failed) return
        val ctx = appContext ?: run { failed = true; return }
        try {
            val intern = HashMap<String, String>()
            fun i(s: String) = intern.getOrPut(s) { s }
            val list = ArrayList<Entry>(6500)
            JsonReader(InputStreamReader(GZIPInputStream(ctx.assets.open("pybot_kb.json.gz")), Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginArray()
                    val slots = ArrayList<Array<String>>()
                    r.beginArray()
                    while (r.hasNext()) {
                        val alts = ArrayList<String>()
                        r.beginArray(); while (r.hasNext()) alts.add(i(r.nextString())); r.endArray()
                        slots.add(alts.toTypedArray())
                    }
                    r.endArray()
                    val ans = r.nextString()
                    val av = ArrayList<String>()
                    r.beginArray(); while (r.hasNext()) av.add(i(r.nextString())); r.endArray()
                    r.endArray()
                    list.add(Entry(slots.toTypedArray(), ans, av.toTypedArray()))
                }
                r.endArray()
            }
            val post = HashMap<String, ArrayList<Int>>()
            list.forEachIndexed { idx, e ->
                val seen = HashSet<String>()
                for (slot in e.slots) for (alt in slot) for (w in alt.split(' ')) {
                    val s = stem(w)
                    if (s.length > 2 && s !in stop && seen.add(s)) post.getOrPut(s) { ArrayList() }.add(idx)
                }
            }
            entries = list.toTypedArray()
            index = HashMap(post.mapValues { it.value.toIntArray() })
            loaded = true
        } catch (t: Throwable) {
            com.demon.nothingassistant.debug.AppLog.e("pykb", "load failed", t)
            failed = true
        }
    }

    /** An answer, or null when nothing in the knowledge fits well enough. Loads on first use (about a second). */
    fun answer(raw: String): String? {
        if (!loaded && !failed) load()
        if (!loaded) return null
        val q = norm(raw)
        if (q.isEmpty()) return null
        val words = q.split(' ')
        if (words.size < 2) return null
        val stems = words.map(::stem)
        val stemSet = stems.toHashSet()
        val padded = " " + stems.joinToString(" ") + " "
        val rawPadded = " $q "

        val cand = HashMap<Int, Int>()
        for (s in stemSet) {
            if (s.length <= 2 || s in stop) continue
            val p = index[s] ?: continue
            if (p.size > 1500) continue
            for (id in p) cand[id] = (cand[id] ?: 0) + 1
        }
        var best = -1; var bestScore = 0; var bestSlots = 0
        for ((id, _) in cand) {
            val e = entries[id]
            if (e.avoid.any { rawPadded.contains(" $it ") }) continue
            var score = 0; var hit = 0
            for (slot in e.slots) {
                var s = 0
                for (alt in slot) {
                    if (alt.contains(' ')) {
                        val ps = " " + alt.split(' ').joinToString(" ") { stem(it) } + " "
                        if (padded.contains(ps)) { s = 3; break }
                    } else if (s < 2 && stem(alt) in stemSet) s = 2
                }
                if (s > 0) { score += s; hit++ }
            }
            if (hit >= 2 && score >= 4 && (score > bestScore || (score == bestScore && hit > bestSlots))) { best = id; bestScore = score; bestSlots = hit }
        }
        // A weak match is a guess: only answer when the line hit at least one topic word as a phrase or three signals.
        if (best < 0 || (bestScore < 5 && words.size > 7)) return null
        return trim(entries[best].answer)
    }

    private fun trim(a: String): String {
        val t = a.replace(Regex("\\s+"), " ").trim()
        if (t.length <= 420) return t
        val parts = t.split(Regex("(?<=[.!?])\\s+"))
        var out = ""
        for (p in parts) { if ((out + p).length > 420 && out.isNotEmpty()) break; out += (if (out.isEmpty()) "" else " ") + p }
        return out.ifEmpty { t.take(420) }
    }
}
