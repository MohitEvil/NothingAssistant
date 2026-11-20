package com.demon.nothingassistant.intent

/**
 * Follow-up commands: "do it again", "and Bluetooth too", "same for brightness".
 * The last thing that worked is remembered with the words used, so a follow-up that has no verb of its own borrows the verb.
 */
object FollowUp {
    private val I = RegexOption.IGNORE_CASE
    @Volatile private var lastText = ""
    @Volatile private var lastCmd: Command? = null
    @Volatile private var at = 0L

    private val verb = Regex("^(turn (?:on|off)|switch (?:on|off)|open|launch|start|play|call|navigate to|take me to|directions to|search for|search|find|show me|set|increase|decrease|install|update)\\b", I)

    fun remember(text: String, cmd: Command) {
        if (cmd is Command.Say || cmd is Command.Question || cmd is Command.Unknown || cmd is Command.Wait || cmd === Command.UiRepeat) return
        lastText = text.trim(); lastCmd = cmd; at = System.currentTimeMillis()
    }

    private fun fresh() = lastCmd != null && System.currentTimeMillis() - at < 10 * 60_000L

    fun resolve(raw: String): Command? {
        if (!fresh()) return null
        val t = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (Regex("^(?:do|say|run) (?:it|that|the same(?: thing)?) again$|^(?:once more|one more time|same again|repeat that command|do that again)$", I).matches(t)) {
            val c = lastCmd
            // Screen taps repeat through their own handler; everything else is simply run again.
            if (c != null && c !is Command.Screen) return c
            return null
        }
        val obj = Regex("^(?:and |also |now |then )(?:(?:for|with|on|to) )?(.+?)(?: too| also| as well)?$", I).find(t)?.groupValues?.get(1)
            ?: Regex("^(.+?) (?:too|also|as well)$", I).find(t)?.groupValues?.get(1)
            ?: Regex("^(?:the )?same (?:for|with|on|to) (.+)$", I).find(t)?.groupValues?.get(1)
            ?: Regex("^what about (.+)$", I).find(t)?.groupValues?.get(1)
            ?: return null
        val v = verb.find(lastText)?.value ?: return null
        if (obj.split(' ').size > 5) return null
        val c = IntentEngine.parse("$v $obj")
        return if (c is Command.Unknown || c is Command.Question) null else c
    }
}
