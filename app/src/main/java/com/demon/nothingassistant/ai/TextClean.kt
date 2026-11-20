package com.demon.nothingassistant.ai

/** Keeps answers plain: no markdown on screen, and nothing awkward for the voice to read out. */
object TextClean {
    private val url = Regex("https?://\\S+")
    private val emphasis = Regex("(\\*\\*|__|`+|~~)")
    private val listMarker = Regex("(?m)^\\s*(#{1,6}|[-*•]|\\d+\\.)\\s+")
    private val spaces = Regex("\\s+")

    private val thinkBlock = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val numEntity = Regex("&#(x[0-9a-fA-F]+|\\d+);")

    /** Turns &#x27;, &#39;, &amp; and friends into real characters, and tidies stray spaces before punctuation. */
    fun decode(s: String): String {
        var t = numEntity.replace(s) { m ->
            val v = m.groupValues[1]
            val cp = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
        return t.replace(Regex("\\s+([.,;:!?])"), "$1").replace(Regex("\"\\s+([^\"]+?)\\s+\""), "\"$1\"").replace(Regex("[ \\t]+"), " ").trim()
    }

    fun forDisplay(s: String): String =
        decode(s).replace(thinkBlock, "")
            .replace(emphasis, "")
            .replace(listMarker, "")
            .replace("*", "")
            .trim()

    fun forSpeech(s: String): String {
        val t = forDisplay(s)
            .replace(url, "link")
            .filter { !it.isSurrogate() && Character.getType(it) != Character.OTHER_SYMBOL.toInt() }
        return t.replace(spaces, " ").trim()
    }
}
