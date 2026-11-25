package com.demon.nothingassistant.ai

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/** Instant offline answers that need no AI: unit conversion, dice, random numbers and day counts. Input is lower-case text. */
object QuickAnswers {

    fun answer(t: String): String? = convert(t) ?: dice(t) ?: randomNumber(t) ?: daysUntil(t)

    // ---- Unit conversion ----

    private class U(val dim: String, val toBase: Double, val spoken: String)

    private val units: Map<String, U> = buildMap {
        fun add(dim: String, factor: Double, spoken: String, vararg names: String) { names.forEach { put(it, U(dim, factor, spoken)) } }
        add("len", 1000.0, "kilometres", "km", "kms", "kilometer", "kilometers", "kilometre", "kilometres")
        add("len", 1609.344, "miles", "mile", "miles", "mi")
        add("len", 1.0, "metres", "m", "meter", "meters", "metre", "metres")
        add("len", 0.3048, "feet", "ft", "foot", "feet")
        add("len", 0.0254, "inches", "inch", "inches", "in")
        add("len", 0.01, "centimetres", "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add("mass", 1.0, "kilograms", "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.45359237, "pounds", "lb", "lbs", "pound", "pounds")
        add("mass", 0.001, "grams", "g", "gram", "grams")
        add("mass", 0.028349523, "ounces", "oz", "ounce", "ounces")
        add("vol", 1.0, "litres", "l", "liter", "liters", "litre", "litres")
        add("vol", 3.785411784, "gallons", "gallon", "gallons", "gal")
        add("vol", 0.001, "millilitres", "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        add("temp", 1.0, "degrees Celsius", "c", "celsius", "centigrade")
        add("temp", 1.0, "degrees Fahrenheit", "f", "fahrenheit")
    }

    private val num = "(-?\\d+(?:\\.\\d+)?)"
    private val patterns = listOf(
        Regex("(?:convert\\s+)?$num\\s*(?:degrees?\\s+)?([a-z]+)\\s+(?:to|in|into)\\s+(?:degrees?\\s+)?([a-z]+)"),
        Regex("how many\\s+([a-z]+)\\s+(?:are\\s+)?(?:in|is in)\\s+$num\\s*([a-z]+)")
    )

    private fun convert(t: String): String? {
        for ((i, rx) in patterns.withIndex()) {
            val m = rx.find(t) ?: continue
            val (v, from, to) = if (i == 0) Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            else Triple(m.groupValues[2], m.groupValues[3], m.groupValues[1])
            val a = units[from] ?: continue
            val b = units[to] ?: continue
            if (a.dim != b.dim) continue
            val x = v.toDoubleOrNull() ?: continue
            val out = if (a.dim == "temp") {
                val aC = a.spoken.endsWith("Celsius"); val bC = b.spoken.endsWith("Celsius")
                when { aC == bC -> x; aC -> x * 9 / 5 + 32; else -> (x - 32) * 5 / 9 }
            } else x * a.toBase / b.toBase
            return "${fmt(x)} ${a.spoken} is about ${fmt(out)} ${b.spoken}."
        }
        return null
    }

    // ---- Dice and random numbers ----

    private fun dice(t: String): String? {
        if (!Regex("\\b(roll|throw)\\b.*\\b(dice|die)\\b|\\b(dice|die) roll\\b").containsMatchIn(t)) return null
        val n = Regex("(\\d+|two|three|four)\\s+dice").find(t)?.groupValues?.get(1)?.let {
            it.toIntOrNull() ?: mapOf("two" to 2, "three" to 3, "four" to 4)[it]
        }?.coerceIn(1, 6) ?: 1
        val rolls = List(n) { Random.nextInt(1, 7) }
        return if (n == 1) "You rolled a ${rolls[0]}." else "You rolled ${rolls.joinToString(", ")}. That's ${rolls.sum()} in total."
    }

    private fun randomNumber(t: String): String? {
        val m = Regex("(?:pick|give me|choose|say|tell me)?\\s*(?:a )?random number\\s*(?:between|from)\\s*(\\d+)\\s*(?:and|to)\\s*(\\d+)").find(t) ?: return null
        val a = m.groupValues[1].toLongOrNull() ?: return null
        val b = m.groupValues[2].toLongOrNull() ?: return null
        val lo = minOf(a, b); val hi = maxOf(a, b)
        return "How about ${Random.nextLong(lo, hi + 1)}."
    }

    // ---- Days until a date ----

    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun daysUntil(t: String): String? {
        val lead = "(?:how many days|days)\\s+(?:until|till|to|left until|left till)\\s+(?:the\\s+)?"
        Regex(lead + "(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?([a-z]+)").find(t)?.let { return daysTo(it.groupValues[1].toIntOrNull(), it.groupValues[2]) }
        Regex(lead + "([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?").find(t)?.let { return daysTo(it.groupValues[2].toIntOrNull(), it.groupValues[1]) }
        return null
    }

    private fun daysTo(day: Int?, monthWord: String): String? {
        val mi = months.indexOfFirst { it == monthWord || (monthWord.length >= 3 && it.startsWith(monthWord)) }
        if (day == null || mi < 0 || day !in 1..31) return null
        val today = LocalDate.now()
        var target = runCatching { LocalDate.of(today.year, mi + 1, day) }.getOrNull() ?: return null
        if (target.isBefore(today)) target = target.plusYears(1)
        val d = ChronoUnit.DAYS.between(today, target)
        val label = "${day} ${months[mi].replaceFirstChar { it.uppercase() }}"
        return when (d) { 0L -> "That's today."; 1L -> "$label is tomorrow."; else -> "$d days until $label." }
    }

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString() else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
}
