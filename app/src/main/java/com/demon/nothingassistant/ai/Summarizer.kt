package com.demon.nothingassistant.ai

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Offline summariser (no model, no network): picks the few sentences that carry the most information.
 * Used for web answers when AI chat is off, to read long text (screen, messages) in a sentence or two,
 * and to squeeze old chat turns so the AI and Needle only see what matters.
 */
object Summarizer {
    private val stop = setOf("a", "an", "the", "is", "are", "was", "were", "of", "in", "on", "at", "to", "for", "and", "or", "it", "its", "this", "that", "with", "by",
        "as", "from", "be", "has", "have", "had", "he", "she", "they", "his", "her", "their", "which", "who", "also", "but", "not", "can", "will", "than", "then", "so", "i", "you", "we", "my", "your")

    fun words(t: String): List<String> = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }.map { stem(it) }

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 3 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun sentences(text: String): List<String> =
        text.replace(Regex("\\s*\\[[^\\]]*]"), "").replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'“])|\\n+")).map { it.trim() }.filter { it.length > 12 }

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b }.toDouble() / minOf(a.size, b.size)
    }

    /** The best sentences of [text] (at most [maxSentences] and about [maxChars]), kept in their original order. [query] pulls the choice toward what was asked. */
    fun summarize(text: String, maxChars: Int = 330, maxSentences: Int = 3, query: String? = null): String {
        val sents = sentences(text)
        if (sents.isEmpty()) return text.trim().take(maxChars)
        if (sents.size == 1) return sents[0].take(maxChars + 60)
        val toks = sents.map { words(it) }
        val df = HashMap<String, Int>()
        for (t in toks) for (w in t.toSet()) df[w] = (df[w] ?: 0) + 1
        val tf = HashMap<String, Int>()
        for (t in toks) for (w in t) tf[w] = (tf[w] ?: 0) + 1
        val q = query?.let { words(it).toSet() }.orEmpty()
        val n = sents.size
        val scores = DoubleArray(n) { i ->
            val t = toks[i]
            if (t.isEmpty()) 0.0 else {
                // frequent words in the text count, words in nearly every sentence count less
                var s = 0.0
                for (w in t.toSet()) s += ln(1.0 + (tf[w] ?: 1)) * ln(1.0 + n.toDouble() / (df[w] ?: 1))
                s /= sqrt(t.size.toDouble())
                if (q.isNotEmpty()) s += 3.0 * overlap(q, t.toSet())
                if (i == 0) s *= 1.25
                if (sents[i].length > 260) s *= 0.8
                if (Regex("\\d").containsMatchIn(sents[i])) s *= 1.08
                s
            }
        }
        val picked = ArrayList<Int>()
        var len = 0
        val order = scores.indices.sortedByDescending { scores[it] }
        for (i in order) {
            if (picked.size >= maxSentences) break
            if (picked.any { overlap(toks[it].toSet(), toks[i].toSet()) > 0.6 }) continue
            if (len + sents[i].length > maxChars && picked.isNotEmpty()) continue
            picked.add(i)
            len += sents[i].length
        }
        return picked.sorted().joinToString(" ") { sents[it] }.ifBlank { sents[0] }
    }

    /** Old turns squeezed into one short line; the newest [keep] stay word for word. */
    fun compactHistory(turns: List<Turn>, keep: Int = 4): List<Turn> {
        if (turns.size <= keep + 1) return turns
        val old = turns.dropLast(keep)
        val asked = old.filter { it.role == "user" }.map { it.text.take(80) }.takeLast(4)
        val gist = old.filter { it.role == "assistant" }.takeLast(2).joinToString(" ") { summarize(it.text, 120, 1) }
        val line = "Earlier in this conversation the user asked: " + asked.joinToString("; ") + (if (gist.isNotBlank()) ". You answered: $gist" else "")
        return listOf(Turn("user", "(Summary of earlier chat) $line"), Turn("assistant", "Understood.")) + turns.takeLast(keep)
    }

    /** One short spoken line for a list of texts (messages, notifications). */
    fun gist(items: List<String>, maxChars: Int = 260): String = summarize(items.joinToString(". ") { it.trim().trimEnd('.') }, maxChars, 2)
}
