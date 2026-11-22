package com.demon.nothingassistant.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.audio.WakeVoice
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.service.WakeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private class Prompt(val title: String, val hint: String)

private fun prompts(phrase: String) = listOf(
    Prompt("Say \"$phrase\"", "In your normal voice."),
    Prompt("Say it again", "Just like before."),
    Prompt("A little softer", "As if someone is asleep nearby."),
    Prompt("Now louder", "Like calling from the next room."),
    Prompt("Faster", "Quick and casual."),
    Prompt("Slower", "Take your time over each word."),
    Prompt("Cheerful", "Say it with a smile."),
    Prompt("Sleepy", "Like you just woke up."),
    Prompt("From farther away", "Hold the phone at arm's length or more."),
    Prompt("Say it with a different tone", "Curious, as if asking a question."),
    Prompt("Hold the phone differently", "Near your chest or in your lap, the way you'll really use it."),
    Prompt("One last time", "Natural, the way you'll really say it.")
)

/** A full-screen setup, like training a voice assistant: several prompts, each in a different way of speaking, then a live test. */
@Composable
fun WakeSetup(settings: Settings, palette: GlyphPalette, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val mono = FontFamily.Monospace
    var stage by remember { mutableStateOf("intro") } // intro, train, test
    var phrase by remember { mutableStateOf(settings.wakePhrase.ifBlank { "hey nothing" }) }
    val samples = remember { mutableStateListOf<Array<FloatArray>>() }
    var level by remember { mutableStateOf(0f) }
    var msg by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    var model by remember { mutableStateOf<WakeVoice.Model?>(null) }
    var verdict by remember { mutableStateOf("") }
    var heardOk by remember { mutableStateOf(0) }
    var needMic by remember { mutableStateOf(false) }
    val list = remember(phrase, stage) { prompts(phrase.trim().ifBlank { "hey nothing" }) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { needMic = false; stage = "train" } else needMic = true
    }
    DisposableEffect(Unit) { onDispose { WakeService.paused = false } }
    BackHandler { onDone() }

    LaunchedEffect(stage, samples.size, attempt) {
        if (stage == "train" && samples.size < list.size) {
            msg = ""
            WakeService.paused = true
            delay(500) // lets the always-on listener let go of the microphone
            val pcm = withContext(Dispatchers.IO) { WakeVoice.capture(10_000, { !isActive }) { level = it } }
            level = 0f
            val f = pcm?.let { withContext(Dispatchers.Default) { WakeVoice.features(it) } }
            if (f == null) { msg = if (pcm == null) "I didn't hear anything. Try again." else "That was too short or too long. Say just the phrase."; delay(1100); attempt++ }
            else { samples.add(f); msg = "Got it"; delay(650) }
        } else if (stage == "train") {
            msg = "Learning your voice…"
            val t = samples.toList()
            val thr = withContext(Dispatchers.Default) { WakeVoice.calibrate(t) }
            withContext(Dispatchers.IO) { WakeVoice.save(ctx, t, thr) }
            settings.setWakePhrase(phrase)
            model = WakeVoice.load(ctx)
            msg = ""
            stage = "test"
        } else if (stage == "test") {
            val m = model ?: return@LaunchedEffect
            WakeService.paused = true
            while (true) {
                val pcm = withContext(Dispatchers.IO) { WakeVoice.capture(60_000, { !isActive }) { level = it } }
                level = 0f
                if (pcm == null) continue
                val f = withContext(Dispatchers.Default) { WakeVoice.features(pcm) }
                if (f == null) { verdict = "Too short or too long."; continue }
                if (m.accepts(f, settings.wakeSens)) { heardOk++; verdict = "Heard you." } else verdict = "That didn't match. Say your phrase."
            }
        }
    }

    val phase by rememberInfiniteTransition(label = "wake").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "p")

    Column(
        Modifier.fillMaxSize().glassBackdrop(palette).systemBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WAKE WORD", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("CLOSE", color = palette.muted, fontFamily = mono, fontSize = 11.sp, letterSpacing = 2.sp,
                modifier = Modifier.padding(8.dp).then(Modifier.clickableNoRipple { onDone() }))
        }
        Spacer(Modifier.height(24.dp))

        when (stage) {
            "intro" -> {
                DotText("HEY", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
                Spacer(Modifier.height(24.dp))
                Text("Teach me your voice", color = palette.fg, fontSize = 26.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    "You'll say your phrase ${list.size} times, each a little differently: soft, loud, fast, slow, far away. That teaches me how you really sound, so I wake for you and stay quiet for everything else. It works for any phrase, in any language, and needs no download.",
                    color = palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))
                Field("YOUR WAKE PHRASE", phrase, palette) { phrase = it }
                Spacer(Modifier.height(6.dp))
                Text("Two or three words work best, for example \"hey nothing\".", color = palette.muted, fontSize = 11.sp)
                if (needMic) { Spacer(Modifier.height(8.dp)); Text("The microphone permission is needed to learn your voice.", color = palette.red, fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                PillButton("CONTINUE", filled = true, palette = palette, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), onClick = {
                    if (phrase.trim().length < 4) return@PillButton
                    samples.clear()
                    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) stage = "train"
                    else permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                })
            }
            "train" -> {
                val i = samples.size.coerceAtMost(list.size - 1)
                Text("${(samples.size + 1).coerceAtMost(list.size)} of ${list.size}", color = palette.muted, fontFamily = mono, fontSize = 12.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.indices.forEach { k -> Box(Modifier.size(8.dp).background(if (k < samples.size) palette.red else palette.dim, CircleShape)) }
                }
                Spacer(Modifier.height(30.dp))
                Text(list[i].title, color = palette.fg, fontSize = 26.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(list[i].hint, color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                Pulse(level, phase, palette)
                Spacer(Modifier.height(16.dp))
                Text(if (msg.isNotBlank()) msg else "Listening…", color = if (msg.startsWith("I didn't") || msg.startsWith("That was")) palette.red else palette.fg, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
            }
            else -> {
                DotText("READY", palette.fg, palette.dim.copy(alpha = 0.45f), 5.dp)
                Spacer(Modifier.height(20.dp))
                Text("Try it", color = palette.fg, fontSize = 26.sp)
                Spacer(Modifier.height(8.dp))
                Text("Say \"${phrase.trim()}\" in any way. I'll tell you if I heard you.", color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                Pulse(level, phase, palette)
                Spacer(Modifier.height(16.dp))
                Text(if (verdict.isBlank()) "Listening…" else verdict, color = if (verdict == "Heard you.") palette.fg else palette.muted, fontSize = 16.sp)
                if (heardOk > 0) Text("✓ ×$heardOk", color = palette.red, fontFamily = mono, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("RETRAIN", filled = false, palette = palette, modifier = Modifier.weight(1f), onClick = { samples.clear(); verdict = ""; heardOk = 0; stage = "train" })
                    PillButton("DONE", filled = true, palette = palette, modifier = Modifier.weight(1f), onClick = {
                        WakeService.paused = false
                        settings.setWakeWord(true)
                        WakeService.stop(ctx); WakeService.start(ctx)
                        onDone()
                    })
                }
            }
        }
    }
}

/** A ring of dots that swells with your voice. */
@Composable
private fun Pulse(level: Float, phase: Float, palette: GlyphPalette) {
    val fg = palette.fg
    val red = palette.red
    Canvas(Modifier.size(210.dp)) {
        val c = center
        val base = size.minDimension * 0.27f
        val n = 56
        for (ring in 0..1) {
            for (i in 0 until n) {
                val a = i.toFloat() / n * 2f * PI.toFloat()
                val wob = (0.5f + 0.5f * sin(a * 3f + phase * (if (ring == 0) 1f else -1.3f))) * (4f + level * size.minDimension * 0.2f)
                val r = base + ring * 16f + wob
                drawCircle(if (ring == 0) red else fg.copy(alpha = 0.55f), radius = if (ring == 0) 3.2f else 2.2f, center = Offset(c.x + cos(a) * r, c.y + sin(a) * r))
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null, onClick = onClick)
)
