package com.demon.nothingassistant.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Nothing's NType 82 family, shipped in assets/fonts: Regular for text, Mono for small labels, Headline for the big spoken lines.
 * A copy found on the phone (Nothing OS) is only a fallback if an asset is ever missing. The faces hold Latin letters only;
 * Hindi and other scripts fall back to the system font automatically.
 */
object NFont {
    var body: FontFamily = FontFamily.Default
        private set
    var label: FontFamily = FontFamily.Monospace
        private set
    var headline: FontFamily = FontFamily.Default
        private set
    /** Same faces for Canvas/Paint drawing. */
    var native: android.graphics.Typeface = android.graphics.Typeface.MONOSPACE
        private set
    var found = false
        private set

    fun init(ctx: Context) {
        val reg = load(ctx, "regular")
        val mono = load(ctx, "mono")
        val head = load(ctx, "headline")
        fun fam(t: android.graphics.Typeface?) = t?.let { FontFamily(androidx.compose.ui.text.font.Typeface(it)) }
        fam(reg)?.let { body = it; found = true }
        fam(mono)?.let { label = it }
        fam(head ?: reg)?.let { headline = it }
        (mono ?: reg)?.let { native = it }
        if (!found) fam(fromSystem())?.let { body = it; label = it; headline = it; found = true }
    }

    private fun load(ctx: Context, part: String): android.graphics.Typeface? = runCatching {
        val pick = ctx.assets.list("fonts").orEmpty().firstOrNull { it.contains(part, true) && (it.endsWith(".otf", true) || it.endsWith(".ttf", true)) } ?: return null
        android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/$pick")
    }.getOrNull()

    private fun fromSystem(): android.graphics.Typeface? = runCatching {
        val dirs = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts")
        val all = dirs.flatMap { d -> File(d).listFiles()?.toList().orEmpty() }.filter { it.isFile && (it.name.endsWith(".otf", true) || it.name.endsWith(".ttf", true)) }
        val f = all.filter { it.name.contains("ntype", true) }.let { l -> l.firstOrNull { it.name.contains("regular", true) || it.name.contains("82") } ?: l.firstOrNull() } ?: return null
        android.graphics.Typeface.createFromFile(f)
    }.getOrNull()
}

/** Gives every plain Text the Nothing typeface. */
@Composable
fun NFontScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = NFont.body))) { content() }
}
