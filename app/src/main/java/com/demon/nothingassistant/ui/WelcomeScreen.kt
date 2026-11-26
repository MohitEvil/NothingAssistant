package com.demon.nothingassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.demon.nothingassistant.access.NothingAccess
import com.demon.nothingassistant.data.Settings

/**
 * First-run setup. Walks through everything the assistant needs, one clear step at a time, shows what is already done,
 * and lets every step be skipped (the same switches live in Settings). [refresh] changes whenever the user comes back from a system screen.
 */
@Composable
fun WelcomeScreen(
    settings: Settings,
    palette: GlyphPalette,
    refresh: Int,
    onAllowMic: () -> Unit,
    onAllowNotifications: () -> Unit,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current
    val mono = FontFamily.Monospace
    val pages = listOf("hello", "name", "mic", "notif", "overlay", "access", "assistant", "voice", "ready")
    var index by remember { mutableStateOf(0) }
    var voiceMatch by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(settings.userName) }
    val page = pages[index]

    fun next() { if (index < pages.lastIndex) index++ }
    fun finish() { settings.setOnboarded(true); onFinish() }

    // Live state of each permission, re-read every time the user returns to the app.
    val micOk = remember(refresh, index) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val notifOk = remember(refresh, index) { Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val overlayOk = remember(refresh, index) { AndroidSettings.canDrawOverlays(ctx) }
    val accessOk = remember(refresh, index) { NothingAccess.isEnabled(ctx) }
    val voiceOk = remember(refresh, index, voiceMatch) { com.demon.nothingassistant.audio.Voiceprint.ready(ctx) }
    val assistOk = remember(refresh, index) {
        runCatching { AndroidSettings.Secure.getString(ctx.contentResolver, "assistant").orEmpty().contains(ctx.packageName) }.getOrDefault(false)
    }

    BackHandler { if (voiceMatch) voiceMatch = false else if (index > 0) index-- }

    if (voiceMatch) {
        WakeSetup(settings, palette) { voiceMatch = false; next() }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .glassBackdrop(palette)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOTHING ASSISTANT", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            if (page != "ready") Text(
                "SKIP SETUP", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable { finish() }.padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            pages.forEachIndexed { i, _ ->
                Box(Modifier.weight(1f).height(3.dp).background(if (i <= index) palette.fg else palette.dim, RoundedCornerShape(2.dp)))
            }
        }

        // Live "is it done" for each page that needs something.
        fun okOf(p: String): Boolean? = when (p) { "mic" -> micOk; "notif" -> notifOk; "overlay" -> overlayOk; "access" -> accessOk; "assistant" -> assistOk; "voice" -> voiceOk; else -> null }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(targetState = index, animationSpec = tween(240), label = "welcome") { i ->
                val pg = pages[i]
                val inf = pageInfo(pg, okOf(pg) == true, accessOk)
                BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    val pitch = minOf(5.dp, maxWidth / dotTextCols(inf.title))
                    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp)) {
                        DotIcon(ICONS[pg] ?: ICONS.getValue("assistant"), palette, Modifier.size(112.dp))
                        Spacer(Modifier.height(26.dp))
                        DotText(text = inf.title, color = palette.fg, offColor = palette.dim.copy(alpha = 0.45f), pitch = pitch)
                        Spacer(Modifier.height(20.dp))
                        Text(inf.body, color = palette.fg, fontSize = 17.sp, lineHeight = 25.sp)
                        if (pg == "name") {
                            Spacer(Modifier.height(18.dp))
                            Box(
                                Modifier.fillMaxWidth().border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                if (name.isEmpty()) Text("Your name", color = palette.muted, fontSize = 16.sp)
                                BasicTextField(
                                    value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                                    textStyle = TextStyle(color = palette.fg, fontSize = 16.sp), cursorBrush = SolidColor(palette.fg),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        okOf(pg)?.let { ok ->
                            Spacer(Modifier.height(18.dp))
                            StatusChip(if (ok) "ALLOWED" else if (pg == "mic") "REQUIRED" else "NOT YET", ok, palette)
                        }
                        if (inf.note.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(inf.note, color = palette.muted, fontFamily = mono, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        if (pg == "ready") {
                            Spacer(Modifier.height(20.dp))
                            Column(
                                Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(20.dp)).border(BorderStroke(1.dp, palette.dim), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                listOf("mic" to "Microphone", "notif" to "Alerts", "overlay" to "Overlay", "access" to "Control", "assistant" to "Default assistant", "voice" to "Voice Match").forEach { (k, label) ->
                                    val ok = okOf(k) == true
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, color = palette.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Text(if (ok) "ON" else "LATER", color = if (ok) palette.fg else palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Anything marked LATER can be switched on in Settings whenever you like.", color = palette.muted, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }

        // What the main button does on each page, and whether that page is already done.
        val done = when (page) { "mic" -> micOk; "notif" -> notifOk; "overlay" -> overlayOk; "access" -> accessOk; "assistant" -> assistOk; "voice" -> voiceOk; else -> false }
        val action = when (page) {
            "hello" -> "GET STARTED"
            "name" -> "CONTINUE"
            "mic" -> if (micOk) "CONTINUE" else "ALLOW MICROPHONE"
            "notif" -> if (notifOk) "CONTINUE" else "ALLOW ALERTS"
            "overlay" -> if (overlayOk) "CONTINUE" else "OPEN SETTINGS"
            "access" -> if (accessOk) "CONTINUE" else "OPEN ACCESSIBILITY"
            "assistant" -> if (assistOk) "CONTINUE" else "SET AS DEFAULT"
            "voice" -> if (done) "CONTINUE" else "START VOICE MATCH"
            else -> "START TALKING"
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PillButton(action, filled = true, palette = palette, modifier = Modifier.fillMaxWidth(), onClick = {
                when (page) {
                    "name" -> { settings.setUserName(name.trim()); next() }
                    "mic" -> if (micOk) next() else onAllowMic()
                    "notif" -> if (notifOk) next() else onAllowNotifications()
                    "overlay" -> if (overlayOk) next() else openSafely(ctx, Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    "access" -> if (accessOk) next() else openSafely(ctx, Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                    "assistant" -> if (assistOk) next() else openSafely(ctx, Intent("android.settings.VOICE_INPUT_SETTINGS"))
                    "voice" -> if (done) next() else voiceMatch = true
                    "ready" -> finish()
                    else -> next()
                }
            })
            if (page in listOf("name", "notif", "overlay", "access", "assistant", "voice") && !(done && page != "name")) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "SKIP FOR NOW", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { next() }.padding(vertical = 10.dp, horizontal = 16.dp)
                )
            }
        }
    }
}

private fun openSafely(ctx: Context, intent: Intent) {
    val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { ctx.startActivity(i) }.isFailure) {
        runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}


private class PageInfo(val title: String, val body: String, val note: String)

private fun pageInfo(page: String, ok: Boolean, accessOk: Boolean): PageInfo = when (page) {
    "hello" -> PageInfo("HELLO", "I'm your voice assistant. Talk to me to open apps, send messages, set timers, control settings and ask questions. A few quick steps and I'm ready.", "Everything here can be changed later in Settings.")
    "name" -> PageInfo("YOUR NAME", "What should I call you?", "Optional. I only use it to greet you.")
    "mic" -> PageInfo("MICROPHONE", "So I can hear you. I only listen after you tap me or say the wake phrase, and the mic is closed the rest of the time.", "Needed to talk to me.")
    "notif" -> PageInfo("ALERTS", "Lets me show the small notice Android requires while the bubble or wake word is running, and read out your notifications if you ask.", "Recommended.")
    "overlay" -> PageInfo("OVERLAY", "Lets me appear over other apps when you call me, and show the floating bubble. Switch on \"Allow display over other apps\".", "Recommended.")
    "access" -> PageInfo("CONTROL", "Lets me tap, scroll and type for you, like opening a chat and sending a message. Find Nothing Assistant in the list and switch it on.", if (accessOk) "" else "If the switch is greyed out: App info, three dots menu, \"Allow restricted settings\", then try again.")
    "assistant" -> PageInfo("ASSISTANT", "Make me the default digital assistant so the power-button hold or home-corner swipe opens me instead of another assistant.", "Optional.")
    "voice" -> PageInfo("MY VOICE", "Teach me your voice so I answer only to you, and wake when you say the phrase. It takes about a minute and everything stays on this phone.", "Optional. Everything stays on this phone.")
    else -> PageInfo("READY", "That's it. Tap the glyph and speak, or type a request. Try \"what can you do\" to hear everything I can help with.", "")
}

@Composable
private fun StatusChip(text: String, ok: Boolean, palette: GlyphPalette) {
    Row(
        Modifier.border(BorderStroke(1.dp, if (ok) palette.fg else palette.dim), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(if (ok) palette.fg else if (text == "REQUIRED") palette.red else palette.dim, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(text, color = if (ok) palette.fg else palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
    }
}

/** A 9 by 9 dot-matrix picture whose dots shimmer in a slow wave. */
@Composable
private fun DotIcon(rows: List<String>, palette: GlyphPalette, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "icon")
    val phase by t.animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val cell = size.minDimension / 9f
        for (r in 0 until 9) for (c in 0 until 9) {
            val on = rows.getOrNull(r)?.getOrNull(c) == '#'
            val a = if (on) 0.62f + 0.38f * kotlin.math.sin(phase + (r + c) * 0.55f).toFloat().let { (it + 1f) / 2f } else 1f
            drawCircle(
                color = if (on) palette.fg.copy(alpha = a) else palette.dim.copy(alpha = 0.35f),
                radius = cell * (if (on) 0.36f else 0.12f),
                center = Offset((c + 0.5f) * cell, (r + 0.5f) * cell)
            )
        }
    }
}

private val ICONS: Map<String, List<String>> = mapOf(
    "hello" to listOf("..#####..", ".#.....#.", "#.......#", "#...#...#", "#..###..#", "#...#...#", "#.......#", ".#.....#.", "..#####.."),
    "assistant" to listOf("..#####..", ".#.....#.", "#.......#", "#...#...#", "#..###..#", "#...#...#", "#.......#", ".#.....#.", "..#####.."),
    "name" to listOf("...###...", "..#####..", "..#####..", "...###...", ".........", ".#######.", "#########", "#########", "........."),
    "mic" to listOf("...###...", "..#####..", "..#####..", "..#####..", "#.#####.#", "#..###..#", ".#.....#.", "....#....", "..#####.."),
    "notif" to listOf("....#....", "...###...", "..#####..", "..#####..", "..#####..", ".#######.", "#########", "...###...", "........."),
    "overlay" to listOf("######...", "#....#...", "#....#...", "#..######", "#..#....#", "######..#", "...#....#", "...#....#", "...######"),
    "access" to listOf("#........", "##.......", "###......", "####.....", "#####....", "######...", "##.##....", "#..##....", "....##..."),
    "voice" to listOf("....#....", "....#.#..", "..#.#.#..", "#.#.#.#.#", "#.#.#.#.#", "#.#.#.#.#", "..#.#.#..", "....#.#..", "....#...."),
    "ready" to listOf(".........", "........#", ".......##", "#.....##.", "##...##..", ".##.##...", "..###....", "...#.....", ".........")
)
