package com.demon.nothingassistant.action

import android.content.Context

/**
 * The app you were last talking about, kept on the phone so "install it", "update it" or "open it" mean that app,
 * even after a restart. Only app names are stored.
 */
object Subject {
    private var sp: android.content.SharedPreferences? = null
    @Volatile private var app = ""
    @Volatile private var at = 0L

    private val pronouns = setOf("it", "that", "this", "@it", "that app", "this app", "the app", "it too", "that one", "this one", "same", "same app")

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("subject", Context.MODE_PRIVATE)
        app = sp?.getString("app", "").orEmpty()
        at = sp?.getLong("at", 0L) ?: 0L
    }

    fun isPronoun(name: String) = name.trim().lowercase().trimEnd('.', '!', '?') in pronouns

    fun set(name: String) {
        val n = name.trim()
        if (n.isEmpty() || isPronoun(n) || n.length > 40) return
        app = n
        at = System.currentTimeMillis()
        sp?.edit()?.putString("app", n)?.putLong("at", at)?.apply()
    }

    /** The remembered app, or null if there is none or it is more than six hours old. */
    fun get(): String? = app.takeIf { it.isNotEmpty() && System.currentTimeMillis() - at < 6 * 3_600_000L }

    /** A real app name for [name]: itself, or the remembered app when it is "it"/"that". */
    fun resolve(name: String): String? = if (isPronoun(name)) get() else name.trim().ifEmpty { null }
}
