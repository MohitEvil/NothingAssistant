package com.demon.nothingassistant.action

import com.demon.nothingassistant.access.NothingAccess

/** The safety gate: taps that spend money or destroy things are never made without a spoken "confirm". Lives in Kotlin, never in a model. */
object Guardian {
    private val risky = Regex(
        "\\b(pay|pay now|paying|payment|send money|transfer|confirm order|place order|confirm booking|book now|buy|buy now|purchase|checkout|check out|" +
            "delete|uninstall|factory reset|reset phone|erase|wipe|format|withdraw|donate|subscribe|proceed to pay|order now|confirm payment|clear all data|clear data|remove account|sign out all)\\b|₹\\s?\\d|\\$\\s?\\d",
        RegexOption.IGNORE_CASE
    )

    fun isRisky(label: String): Boolean = risky.containsMatchIn(label)

    private val amount = Regex("(?:₹|Rs\\.?|INR)\\s?[\\d,]+(?:\\.\\d{1,2})?")

    /** An amount visible on screen ("₹499"), to read out in the question. */
    fun amountOnScreen(svc: NothingAccess?): String? = runCatching {
        svc?.candidates()?.firstNotNullOfOrNull { amount.find(it.label)?.value }
    }.getOrNull()

    fun ask(svc: NothingAccess?, label: String, command: com.demon.nothingassistant.intent.Command): ActionResult.Confirm {
        val amt = amountOnScreen(svc)
        val what = label.take(40)
        return ActionResult.Confirm(
            title = "Confirm: $what?",
            detail = "This could spend money or delete something." + (amt?.let { " I can see $it on screen." } ?: ""),
            spoken = "Say confirm to tap $what" + (amt?.let { " for $it" } ?: "") + ".",
            command = command,
            declineMessage = "Okay, I didn't tap it.",
            declineAsFailure = false
        )
    }
}
