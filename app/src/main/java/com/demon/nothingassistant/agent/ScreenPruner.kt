package com.demon.nothingassistant.agent

import com.demon.nothingassistant.access.NothingAccess
import com.demon.nothingassistant.action.Targeting

/**
 * Cuts the accessibility tree down to what a tiny model can use: only visible, interactive or labelled things, in reading order,
 * short labels and centre points. Layout containers and off-screen nodes never reach the prompt.
 */
object ScreenPruner {
    class Item(val n: Int, val kind: String, val label: String, val x: Int, val y: Int, val checked: Boolean?)

    fun snapshot(svc: NothingAccess, max: Int = 28): List<Item> {
        val seen = HashSet<String>()
        val ordered = Targeting.reading(svc.candidates().filter { it.label.isNotBlank() || it.editable || it.checkable })
        val out = ArrayList<Item>()
        for (c in ordered) {
            val label = c.label.trim().replace(Regex("\\s+"), " ").take(34)
            val kind = when { c.editable -> "field"; c.checkable -> "switch"; c.clickable -> "button"; else -> "text" }
            if (kind == "text" && label.length < 3) continue
            if (!seen.add(kind + "|" + label.lowercase())) continue
            val checked = if (c.checkable) runCatching { c.node.isChecked }.getOrNull() else null
            out.add(Item(out.size + 1, kind, label, c.hit.centerX(), c.hit.centerY(), checked))
            if (out.size >= max) break
        }
        return out
    }

    /** The exact GOAL / HIST / UI layout the agent prompt uses. */
    fun prompt(goal: String, hist: List<String>, ui: List<Item>, front: String): String = buildString {
        append("GOAL: ").append(goal).append('\n')
        append("HIST: ").append(if (hist.isEmpty()) "none" else hist.takeLast(6).joinToString(" > ")).append('\n')
        append("UI(").append(front.substringAfterLast('.')).append("): ")
        append(ui.joinToString(" | ") { "[${it.n}]${it.kind} '${it.label}'" + (it.checked?.let { c -> if (c) " on" else " off" } ?: "") + " @${it.x},${it.y}" })
    }

    fun hash(ui: List<Item>): Int = ui.joinToString("|") { it.kind + it.label + (it.checked ?: "") }.hashCode()
}
