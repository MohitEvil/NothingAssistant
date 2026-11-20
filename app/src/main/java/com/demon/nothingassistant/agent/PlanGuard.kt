package com.demon.nothingassistant.agent

import com.demon.nothingassistant.intent.Command

/**
 * A small model can pick a wrong tool ("change default assistant to Google" -> update_app Google).
 * Before a plan from Needle or the cloud runs, every step that changes something must be backed by words the user actually said.
 */
object PlanGuard {
    private val I = RegexOption.IGNORE_CASE
    private fun has(t: String, rx: String) = Regex(rx, I).containsMatchIn(t)

    private fun flat(c: Command): List<Command> = if (c is Command.Chain) c.steps.flatMap { flat(it) } else listOf(c)

    fun ok(text: String, plan: Command): Boolean {
        val t = text.lowercase()
        for (c in flat(plan)) {
            val good = when (c) {
                is Command.InstallApp -> has(t, "\\b(install|download|get|add|setup|set up)\\b")
                is Command.UpdateApp -> has(t, "\\b(update|upgrade|updates)\\b")
                is Command.Call -> has(t, "\\b(call|dial|ring|phone|contact)\\b")
                is Command.SendSms, is Command.ComposeSms, is Command.UiMessage, is Command.UiSend -> has(t, "\\b(message|msg|text|sms|send|tell|say|reply|whatsapp|telegram|write|type|dm)\\b")
                is Command.Priv -> has(t, "\\b(clear|force|kill|revoke|remove|disable|enable|turn|switch|reset|animation|storage|permission|permissions|audit|location|cache|data|wifi|wi-fi|bluetooth|airplane|mobile)\\b")
                is Command.DeleteScreenshot -> has(t, "\\b(delete|remove|erase|trash)\\b")
                is Command.AppAction -> has(t, "\\b(uninstall|remove|force|stop|clear|cache|info|details|kill)\\b")
                else -> true
            }
            if (!good) return false
        }
        return true
    }
}
