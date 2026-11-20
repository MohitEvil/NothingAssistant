package com.demon.nothingassistant.intent

import com.demon.nothingassistant.action.Blackboard

/** Messages, screenshots, undo and summaries: things that used to fall through to a web search. */
object DevicePhrases {
    private val I = RegexOption.IGNORE_CASE
    private val numWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "a" to 1, "the" to 1)
    private val otherApps = Regex("\\b(whatsapp|telegram|instagram|signal|slack|discord|gmail|email|mail|notifications?|messenger)\\b", I)

    fun parse(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?').replace("’", "'").replace(Regex("\\s+"), " ")
        val t = s.lowercase()

        // ---- default assistant ----
        Regex("^(?:change|set|switch|select|choose|make|pick)(?: the| my)?(?: default)?(?: digital)? assistant(?: app)?(?: to| as)\\s+(.+)$", I).find(t)?.let { m ->
            return Command.SetDefaultAssistant(m.groupValues[1].trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        }
        Regex("^(?:make|set) (.+?) (?:as )?(?:my |the )?default(?: digital)? assistant(?: app)?$", I).find(t)?.let { m ->
            return Command.SetDefaultAssistant(m.groupValues[1].trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        }
        Regex("^(?:change|set|switch)(?: the| my)? default(?: digital)? assistant(?: app)?(?: to| as)\\s+(.+)$", I).find(t)?.let { m ->
            return Command.SetDefaultAssistant(m.groupValues[1].trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        }

        // ---- screenshots ----
        if (Regex("\\b(delete|remove|erase|trash|get rid of)\\b", I).containsMatchIn(t) && Regex("\\bscreen ?shots?\\b", I).containsMatchIn(t)) return Command.DeleteScreenshot
        if (Regex("^(?:delete|remove|erase|trash|get rid of)(?: it| that| this| the last one| the one (?:you|we|i) (?:just )?(?:took|made|captured)| the (?:picture|photo|image|pic) (?:you|we|i) (?:just )?(?:took|made|captured))(?: please)?$", I).matches(t)
            && Blackboard.subjectKind() == "screenshot") return Command.DeleteScreenshot

        // ---- undo ----
        Regex("^(?:actually,? )?(?:turn|switch|set|put|change) (?:it|that) back(?: (on|off))?$|^(?:actually,? )?(?:turn|switch) (?:it|that) (on|off) again$|^(?:undo|revert)(?: that| it| the last (?:thing|action|one|change))?$|^(?:go|change) back$", I).find(t)?.let { m ->
            val last = Blackboard.lastSetting()
            if (last != null && (Blackboard.subjectKind() == "toggle" || m.value.startsWith("undo") || m.value.startsWith("revert"))) {
                val explicit = (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() })?.lowercase()
                if (explicit == null) return Command.Undo
                val on = explicit == "on"
                return if (last.first == "flashlight") Command.Flashlight(on) else Command.Toggle(last.first, on)
            }
        }

        // ---- summaries ----
        Regex("^(?:summari[sz]e|sum up|summary of|give me (?:a |the )?(?:summary|gist) of|tl;?dr)(?: of)?(?: this| the| my| what'?s on(?: the)?)?(?: (screen|page|article|text|messages?|sms|texts?|website|post))?$", I).find(t)?.let { m ->
            return Command.Summarize(m.groupValues[1].ifEmpty { "screen" })
        }

        // ---- reading text messages ----
        if (Regex("\\b(sms|texts?|text messages?|messages?|inbox)\\b", I).containsMatchIn(t) && !otherApps.containsMatchIn(t) &&
            !Regex("\\b(send|write|compose|dictate|reply|call|forward|delete|to)\\b|^(?:msg|message|text) \\w+", I).containsMatchIn(t.replace(Regex("\\bfrom\\b.*$"), "")) &&
            Regex("^(?:what(?:'s| is| was| are| were)?|whats|tell me|read|show|check|get|any|do i have|have i got|did i get|did anyone|who (?:sent|texted|messaged)|latest|last|my|summari[sz]e|read out|open)\\b", I).containsMatchIn(t)
        ) {
            val n = Regex("\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\b", I).find(t)?.value?.lowercase()?.let { it.toIntOrNull() ?: numWords[it] } ?: if (Regex("\\b(messages|texts|sms messages)\\b|\\blast few\\b|\\brecent\\b", I).containsMatchIn(t) && !Regex("\\b(latest|last|newest|new) (?:sms|text|message)\\b", I).containsMatchIn(t)) 3 else 1
            val from = Regex("\\bfrom ([a-z][a-z .'-]{1,30})$", I).find(t)?.groupValues?.get(1)?.trim()?.takeIf { it !in setOf("me", "today", "yesterday", "them") }
            return Command.ReadSms(n.coerceIn(1, 10), from, Regex("summar|gist|tl;?dr", I).containsMatchIn(t))
        }
        if (Regex("^who (?:sent|texted|messaged) me(?: last| latest| recently)?$|^who (?:texted|messaged|sent me a message) (?:last|recently)$", I).matches(t)) return Command.ReadSms(1, null, false)
        return null
    }
}
