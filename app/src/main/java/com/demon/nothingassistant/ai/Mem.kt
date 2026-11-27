package com.demon.nothingassistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's memory, kept as small named files, the way a person keeps notes:
 *   profile            who the user is (identity, work, tastes, routines, how they like to be spoken to)
 *   topics/<name>      things they care about, by subject
 *   areas/<name>       ongoing things in their life (a project, a trip, an exam)
 *   system/settings    where settings live on THIS phone and how each one is really changed
 *   system/apps        the apps they use and how often
 *   system/commands    commands that worked and how, so they can be repeated faster
 * The AI decides what is worth keeping after each conversation; nobody has to say "remember". It is never announced.
 * Everything stays on the phone and can be read, edited or deleted on the Memory files page.
 */
object Mem {
    class MFile(val path: String, var desc: String, var body: String, var updated: Long) {
        val lines: List<String> get() = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val count: Int get() = lines.size
    }

    private var sp: android.content.SharedPreferences? = null
    private val files = LinkedHashMap<String, MFile>()
    @Volatile var on: () -> Boolean = { true }
    @Volatile var version = 0
        private set
    private var lastLearn = 0L
    private val pending = ArrayList<Pair<String, String>>()

    val SYSTEM_DESC = mapOf(
        "system/settings" to "Where settings live on this phone and how each one is really changed (switch, menu or its own page).",
        "system/apps" to "The apps you open, and how often.",
        "system/commands" to "Commands that worked and how they were done, so they can be repeated faster."
    )

    @Synchronized fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("assistant_memory", Context.MODE_PRIVATE)
        runCatching {
            val a = JSONArray(sp!!.getString("files", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                files[o.getString("p")] = MFile(o.getString("p"), o.optString("d"), o.optString("b"), o.optLong("u"))
            }
        }
        // Notes from the first version of memory (a flat list of facts) become the start of the profile.
        if (files.isEmpty()) runCatching {
            val old = JSONArray(sp!!.getString("facts", "[]"))
            val sb = StringBuilder()
            for (i in 0 until old.length()) sb.append("- ").append(old.getJSONObject(i).getString("t")).append('\n')
            if (sb.isNotBlank()) files["profile"] = MFile("profile", "Who the user is.", sb.toString().trim(), System.currentTimeMillis())
        }
    }

    private fun save() {
        val a = JSONArray()
        files.values.forEach { a.put(JSONObject().put("p", it.path).put("d", it.desc).put("b", it.body).put("u", it.updated)) }
        sp?.edit()?.putString("files", a.toString())?.apply()
        version++
    }

    // ---- Reading ----

    private fun order(p: String) = when { p == "profile" -> 0; p.startsWith("topics/") -> 1; p.startsWith("areas/") -> 2; else -> 3 }
    @Synchronized fun list(): List<MFile> = files.values.sortedWith(compareBy({ order(it.path) }, { it.path }))
    @Synchronized fun get(path: String): MFile? = files[path]
    @Synchronized fun total(): Int = files.values.sumOf { it.count }
    fun title(path: String) = if (path == "profile") "Profile" else path.substringAfter('/').replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
    fun group(path: String) = when (order(path)) { 0 -> "You"; 1 -> "Topics"; 2 -> "Areas"; else -> "System" }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    private fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return a.equals(b, true)
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size) >= 0.75
    }

    // ---- Writing ----

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)

    @Synchronized fun addLine(path0: String, line0: String, desc: String = "") {
        val line = line0.trim().trimStart('-', '*', '•', ' ').trimEnd('.').take(200)
        if (line.length < 4) return
        val path = normPath(path0)
        val f = files.getOrPut(path) { MFile(path, desc.ifBlank { SYSTEM_DESC[path] ?: defaultDesc(path) }, "", System.currentTimeMillis()) }
        if (desc.isNotBlank() && f.desc.isBlank()) f.desc = desc
        val ls = f.lines.toMutableList()
        val i = ls.indexOfFirst { similar(it.removePrefix("- "), line) }
        val text = "- $line"
        if (i >= 0) ls[i] = text else ls.add(text)
        val cap = if (path == "profile") 90 else 60
        while (ls.size > cap) ls.removeAt(0)
        f.body = ls.joinToString("\n")
        f.updated = System.currentTimeMillis()
        save()
    }

    private fun normPath(p: String): String {
        val t = p.trim().lowercase()
        return when {
            t == "profile" || t == "me" || t == "user" -> "profile"
            t.startsWith("system/") -> if (t in SYSTEM_DESC) t else "system/commands"
            t.startsWith("topics/") -> "topics/" + slug(t.substringAfter('/')).ifBlank { "general" }
            t.startsWith("areas/") -> "areas/" + slug(t.substringAfter('/')).ifBlank { "general" }
            else -> "topics/" + slug(t).ifBlank { "general" }
        }
    }

    private fun defaultDesc(path: String) = when (order(path)) {
        0 -> "Who the user is: identity, work, tastes, routines and how they like to be spoken to."
        1 -> "What the user knows or cares about in this subject."
        2 -> "An ongoing part of the user's life."
        else -> ""
    }

    @Synchronized fun setBody(path: String, body: String) {
        val f = files[path] ?: return
        f.body = body.trim(); f.updated = System.currentTimeMillis(); save()
    }

    @Synchronized fun delete(path: String) { if (files.remove(path) != null) save() }
    @Synchronized fun clear() { files.clear(); pending.clear(); save() }

    /** Removes lines (outside the system files) that mention the words in [what]. Returns how many went. */
    @Synchronized fun forget(what: String): Int {
        val w = words(what)
        if (w.isEmpty()) return 0
        var n = 0
        for (f in files.values.filter { !it.path.startsWith("system/") }) {
            val keep = f.lines.filter { l -> (words(l).intersect(w).size < minOf(w.size, 2).coerceAtLeast(1)).also { if (!it) n++ } }
            if (keep.size != f.lines.size) { f.body = keep.joinToString("\n"); f.updated = System.currentTimeMillis() }
        }
        files.values.removeAll { it.body.isBlank() && !it.path.startsWith("system/") }
        if (n > 0) save()
        return n
    }

    // ---- Things the assistant learns by doing (no AI call) ----

    /** [kind]: "settings" or "commands" or "apps". */
    fun learnHow(kind: String, line: String) { if (on()) addLine("system/$kind", line) }

    @Synchronized fun noteApp(name: String) {
        if (!on()) return
        val n = name.trim().replaceFirstChar { it.uppercase() }
        if (n.length < 2) return
        val f = files.getOrPut("system/apps") { MFile("system/apps", SYSTEM_DESC.getValue("system/apps"), "", System.currentTimeMillis()) }
        val ls = f.lines.toMutableList()
        val re = Regex("^- (.+?) — opened (\\d+) times?$")
        val i = ls.indexOfFirst { re.find(it)?.groupValues?.get(1)?.equals(n, true) == true }
        val c = if (i >= 0) (re.find(ls[i])!!.groupValues[2].toInt() + 1) else 1
        val line = "- $n — opened $c time" + if (c == 1) "" else "s"
        if (i >= 0) ls[i] = line else ls.add(line)
        while (ls.size > 40) ls.remove(ls.minByOrNull { re.find(it)?.groupValues?.get(2)?.toIntOrNull() ?: 0 }!!)
        f.body = ls.joinToString("\n"); f.updated = System.currentTimeMillis()
        save()
    }

    @Synchronized fun topApps(n: Int = 3): List<String> {
        val re = Regex("^- (.+?) — opened (\\d+) times?$")
        return files["system/apps"]?.lines?.mapNotNull { re.find(it)?.let { m -> m.groupValues[1] to m.groupValues[2].toInt() } }
            ?.filter { it.second >= 3 }?.sortedByDescending { it.second }?.take(n)?.map { it.first.lowercase() } ?: emptyList()
    }

    // ---- Using memory ----

    /** For the AI's instructions when chatting: the profile, then notes that share words with what was just said. */
    @Synchronized fun block(query: String, budget: Int = 900): String {
        if (!on() || files.isEmpty()) return ""
        val q = words(query)
        val sb = StringBuilder(" What you know about the user (use it naturally when it helps; never recite it and never mention that you keep notes): ")
        files["profile"]?.lines?.forEach { if (sb.length + it.length < budget * 2 / 3) sb.append(it.removePrefix("- ")).append("; ") }
        val rel = files.values.filter { !it.path.startsWith("system/") && it.path != "profile" }.flatMap { f -> f.lines.map { it to f.path } }
            .map { (l, p) -> Triple(l, p, words(l).intersect(q).size) }.filter { it.third > 0 }.sortedByDescending { it.third }
        for ((l, _, _) in rel) { if (sb.length + l.length > budget) break; sb.append(l.removePrefix("- ")).append("; ") }
        topApps().takeIf { it.isNotEmpty() }?.let { sb.append("They often open ").append(it.joinToString(", ")).append('.') }
        return if (sb.length < 140) "" else sb.toString()
    }

    /** For the screen-working AI: what has already been learned about this phone that bears on the task. */
    @Synchronized fun systemBlock(task: String, budget: Int = 700): String {
        if (!on()) return ""
        val q = words(task)
        val lines = files.values.filter { it.path.startsWith("system/") && it.path != "system/apps" }.flatMap { it.lines }
            .map { it to words(it).intersect(q).size }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (lines.isEmpty()) return ""
        val sb = StringBuilder("\nKNOWN ON THIS PHONE (learned earlier, use it to go straight to the right place):\n")
        for ((l, _) in lines) { if (sb.length + l.length > budget) break; sb.append(l).append('\n') }
        return sb.toString()
    }

    /** Names and words to help the speech recogniser (Whisper prompt). */
    @Synchronized fun vocabulary(): String {
        if (!on()) return ""
        val names = files.values.filter { !it.path.startsWith("system/") }.flatMap { f -> Regex("\\b[A-Z][a-zA-Z]{2,}\\b").findAll(f.body).map { it.value }.toList() }.distinct().take(20)
        return (names + topApps(6)).joinToString(", ")
    }

    // ---- The AI decides what to keep ----

    private val personal = Regex(
        "\\b(my|i am|i'm|i m|i live|i work|i like|i love|i hate|i prefer|i usually|i always|i never|call me|mera|meri|mere|main|mujhe|i have|i study|i drive|i wake|i sleep|i need|i want|i use|i play|i watch|i listen|i'm learning|i am learning|preparing|exam|my name)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * After a chat turn: keeps the exchange, and when it looks personal (or a few turns have gathered) asks the AI, in fast mode and
     * never more than every 30 seconds, which lasting facts to file where, and which old notes are now wrong. Silent.
     */
    suspend fun learn(brain: Brain, user: String, reply: String) {
        if (!on() || !brain.configured()) return
        synchronized(this) { pending.add(user to reply); while (pending.size > 6) pending.removeAt(0) }
        val hit = personal.containsMatchIn(user) && user.length in 8..400
        val now = System.currentTimeMillis()
        val due = hit || pending.size >= 4
        if (!due || now - lastLearn < 30_000) return
        lastLearn = now
        val turns = synchronized(this) { pending.toList().also { pending.clear() } }
        val have = synchronized(this) {
            "PROFILE NOW:\n" + (files["profile"]?.body?.take(1400) ?: "(empty)") + "\nOTHER FILES: " + files.keys.filter { !it.startsWith("system/") && it != "profile" }.joinToString(", ").ifBlank { "(none)" }
        }
        val convo = turns.joinToString("\n") { "USER: ${it.first.take(300)}\nASSISTANT: ${it.second.take(200)}" }
        val sys = "You keep a private memory file system about the user of a voice assistant, like a thoughtful personal assistant would. From the conversation, decide what is worth remembering for months: " +
            "who they are, their name, family and people, where they live and work or study, goals and exams, interests, tastes, diet, habits and routines, devices, languages and how they like replies (short, Hindi, Hinglish, formal…). " +
            "Skip small talk, one-off questions, commands, temporary moods, and anything already in the files. Keep lines short, third person, factual, e.g. 'Preparing for the CDS exam.' " +
            "Output one operation per line, at most 6, nothing else:\n" +
            "ADD profile | <fact>\nADD topics/<subject> | <fact>\nADD areas/<ongoing-thing> | <fact>\nDEL <file> | <words of an old note that is now wrong or contradicted>\n" +
            "If nothing is worth keeping output exactly NONE."
        val out = brain.ask(emptyList(), have + "\n\nCONVERSATION:\n" + convo, system = sys, fast = true).getOrNull()?.trim().orEmpty()
        if (out.isEmpty() || out.startsWith("NONE", true)) return
        for (raw in out.lines().take(8)) {
            val l = raw.trim().trimStart('-', '*', '•', ' ')
            val m = Regex("^(ADD|DEL)\\s+([^|]+)\\|\\s*(.+)$", RegexOption.IGNORE_CASE).find(l) ?: continue
            val path = m.groupValues[2].trim()
            val txt = m.groupValues[3].trim()
            if (path.startsWith("system", true)) continue
            if (m.groupValues[1].equals("ADD", true)) addLine(path, txt) else removeLike(normPath(path), txt)
        }
    }

    @Synchronized private fun removeLike(path: String, text: String) {
        val f = files[path] ?: return
        val keep = f.lines.filterNot { similar(it.removePrefix("- "), text) }
        if (keep.size != f.lines.size) { f.body = keep.joinToString("\n"); f.updated = System.currentTimeMillis(); if (f.body.isBlank()) files.remove(path); save() }
    }

    // ---- Spoken commands ----

    private val rememberRe = Regex("^(?:please )?(?:remember|note|save|yaad rakh(?:o|na)?|yaad rakho)(?: that| ki)? (.{3,})$", RegexOption.IGNORE_CASE)
    private val forgetRe = Regex("^(?:please )?(?:forget|delete|remove|bhool ja(?:o|na)?) (?:that |about |what i said about )?(.{3,})$", RegexOption.IGNORE_CASE)
    private val listRe = Regex("^(?:what do you (?:remember|know) about me|what have you learned about me|tell me what you remember)\\??$", RegexOption.IGNORE_CASE)
    private val wipeRe = Regex("^(?:forget everything(?: about me)?|clear (?:my )?memory|erase (?:my )?memory)$", RegexOption.IGNORE_CASE)

    /** "remember that…", "forget…", "what do you know about me". Returns the spoken answer, or null if it isn't a memory command. */
    fun command(raw: String): String? {
        val t = raw.trim().trimEnd('.', '!')
        if (wipeRe.matches(t)) { val n = total(); clear(); return if (n == 0) "I wasn't keeping anything about you." else "Done. I've forgotten everything." }
        if (listRe.matches(t)) {
            val l = files["profile"]?.lines?.take(6).orEmpty()
            return if (l.isEmpty()) "Not much yet. The more we talk, the more I'll know." else "Here's what I know. " + l.joinToString(" ") { it.removePrefix("- ").trimEnd('.') + "." }
        }
        rememberRe.find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.startsWith("to ", true) || what.split(' ').size < 2) return null // "remember to call mom" is a reminder
            addLine("profile", what.replaceFirstChar { it.uppercase() })
            return "Noted."
        }
        forgetRe.find(t)?.let { m ->
            val what = m.groupValues[1]
            if (Regex("^(?:what you|your |the (?:chat|conversation)|this chat|skills|everything$)", RegexOption.IGNORE_CASE).containsMatchIn(what)) return null
            return if (forget(what) == 0) "I don't have anything like that." else "Done."
        }
        return null
    }
}
