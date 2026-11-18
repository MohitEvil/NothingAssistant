package com.demon.nothingassistant.action

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Text matching that costs almost nothing (a few KB, no model download): Jaro-Winkler, Levenshtein, a concept table
 * for synonyms ("Preferences" = "Settings", "Navigate up" = "Back"), and a small hashed character-trigram embedding with cosine similarity.
 * It stands in for a neural sentence embedder: same idea (nearest meaning wins), in microseconds and without extra native libraries.
 */
object Matcher {
    fun levenshteinRatio(a: String, b: String): Double = Fuzzy.similarity(a, b)

    fun jaroWinkler(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val range = max(0, max(s1.length, s2.length) / 2 - 1)
        val m1 = BooleanArray(s1.length)
        val m2 = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val lo = max(0, i - range)
            val hi = min(s2.length - 1, i + range)
            for (j in lo..hi) if (!m2[j] && s1[i] == s2[j]) { m1[i] = true; m2[j] = true; matches++; break }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in s1.indices) if (m1[i]) { while (!m2[k]) k++; if (s1[i] != s2[k]) t++; k++ }
        val m = matches.toDouble()
        val jaro = (m / s1.length + m / s2.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(4, min(s1.length, s2.length)) && s1[prefix] == s2[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    // ---- concepts ----

    private val groups: List<Set<String>> = listOf(
        setOf("settings", "setting", "preferences", "preference", "options", "configuration", "config", "setup", "gear", "cog"),
        setOf("back", "return", "navigate up", "up", "previous", "go back"),
        setOf("search", "find", "look up", "magnifier", "magnifying glass"),
        setOf("menu", "more", "more options", "overflow", "three dots", "hamburger", "navigation drawer", "open navigation drawer"),
        setOf("ok", "okay", "confirm", "yes", "accept", "agree", "allow", "got it"),
        setOf("cancel", "dismiss", "no", "deny", "not now", "no thanks", "later", "maybe later"),
        setOf("close", "exit", "quit"),
        setOf("install", "get", "download"),
        setOf("update", "upgrade"),
        setOf("delete", "remove", "trash", "bin", "erase", "discard"),
        setOf("home", "main", "start screen"),
        setOf("share", "send to", "forward"),
        setOf("camera", "photo", "take picture", "take photo", "shutter", "capture"),
        setOf("call", "dial", "phone", "ring"),
        setOf("message", "messages", "chat", "chats", "text", "sms", "conversation", "dm"),
        setOf("notification", "notifications", "alerts", "alert"),
        setOf("wifi", "wi-fi", "wireless", "wlan"),
        setOf("volume", "sound", "audio", "sounds"),
        setOf("display", "screen", "brightness"),
        setOf("battery", "power"),
        setOf("apps", "app", "applications", "application"),
        setOf("account", "profile", "user", "accounts"),
        setOf("login", "log in", "sign in", "signin"),
        setOf("logout", "log out", "sign out", "signout"),
        setOf("save", "keep", "store"),
        setOf("edit", "modify", "change", "pencil", "rename"),
        setOf("add", "new", "create", "plus", "+"),
        setOf("play", "resume", "start"),
        setOf("pause", "stop"),
        setOf("favorite", "favourite", "like", "heart", "star", "bookmark"),
        setOf("next", "continue", "proceed", "forward", "go on"),
        setOf("default", "preferred", "current"),
        setOf("assistant", "assist", "digital assistant", "voice assistant")
    )

    private val conceptOf: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        groups.forEachIndexed { i, g -> for (w in g) m.putIfAbsent(w, i) }
        m
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}+ ]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** Concept ids the text mentions (whole phrases first, then single words). */
    fun concepts(text: String): Set<Int> {
        val t = norm(text)
        val out = HashSet<Int>()
        if (t.isEmpty()) return out
        conceptOf[t]?.let { out.add(it) }
        val words = t.split(' ')
        for (n in 3 downTo 2) for (i in 0..words.size - n) conceptOf[words.subList(i, i + n).joinToString(" ")]?.let { out.add(it) }
        for (w in words) conceptOf[w]?.let { out.add(it) }
        return out
    }

    // ---- tiny embedding ----

    private const val DIM = 192

    private fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        val t = " " + norm(text).replace(" ", "  ") + " "
        for (i in 0..t.length - 3) {
            val h = (t.substring(i, i + 3).hashCode() and 0x7fffffff) % DIM
            v[h] += 1f
        }
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        if (n > 0) for (i in v.indices) v[i] /= n
        return v
    }

    fun cosine(a: String, b: String): Double {
        val x = embed(a); val y = embed(b)
        var d = 0f
        for (i in x.indices) d += x[i] * y[i]
        return d.toDouble()
    }

    /**
     * 0..100: how close [label] is in meaning or spelling to what the user asked for [query].
     * A shared concept scores 84-88, near-identical spelling up to 92, loose likeness stays below the "just do it" line.
     */
    fun score(query: String, label: String): Int {
        val q = norm(query); val l = norm(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        var best = 0.0
        val cq = concepts(q); val cl = concepts(l)
        if (cq.isNotEmpty() && cl.isNotEmpty() && cq.any { it in cl }) {
            // all content words of the shorter text are covered by shared concepts or words
            val qw = q.split(' ').filter { it.length > 1 }
            val lw = l.split(' ').filter { it.length > 1 }
            val short = if (qw.size <= lw.size) qw else lw
            val other = if (qw.size <= lw.size) lw else qw
            val covered = short.count { w -> w in other || conceptOf[w]?.let { c -> other.any { conceptOf[it] == c } } == true }
            best = max(best, if (covered == short.size) (if (lw.size <= qw.size + 2) 88.0 else 80.0) else 70.0)
        }
        val jw = jaroWinkler(q.replace(" ", ""), l.replace(" ", ""))
        if (jw >= 0.93) best = max(best, 70 + (jw - 0.93) / 0.07 * 22)
        val cos = cosine(q, l)
        if (cos >= 0.8) best = max(best, 60 + (cos - 0.8) / 0.2 * 24)
        return best.toInt().coerceIn(0, 92)
    }
}
