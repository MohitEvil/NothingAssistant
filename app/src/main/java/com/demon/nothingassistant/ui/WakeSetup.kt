package com.demon.nothingassistant.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demon.nothingassistant.audio.VoiceBands
import com.demon.nothingassistant.audio.Voiceprint
import com.demon.nothingassistant.audio.VoskAsr
import com.demon.nothingassistant.audio.WakeVoice
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.service.WakeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** kind 0: the wake phrase alone (teaches the wake word). kind 1: the phrase plus a sentence (teaches your voice over longer speech). */
private class Step(val words: List<String>, val kind: Int)

private val TAILS = listOf(
    "what's the weather like today", "set a timer for five minutes", "tell me about the Northern Lights", "explain how a rainbow is formed",
    "help me pick a movie for tonight", "open settings and turn on Wi-Fi", "remind me to buy milk tomorrow morning"
)

private fun stepsFor(phrase: String): List<Step> {
    val p = phrase.trim().ifBlank { "hey nothing" }.split(" ").filter { it.isNotBlank() }
    val long = TAILS.map { t ->
        val head = p.dropLast(1) + (p.last() + ",")
        Step(head + t.split(" "), 1)
    }
    return List(5) { Step(p, 0) } + long
}

private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()

private fun lev(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

private fun sim(a: String, b: String): Float {
    if (a == b) return 1f
    if (a.isEmpty() || b.isEmpty()) return 0f
    return 1f - lev(a, b).toFloat() / max(a.length, b.length)
}

/** How many of the prompt's words, in order, the recogniser has heard so far. Skips a word or two, so a slip doesn't stall it. */
private fun matched(prompt: List<String>, heard: List<String>): Int {
    var j = 0
    for (w in heard) {
        var found = -1
        var k = j
        while (k < min(prompt.size, j + 3)) { if (sim(prompt[k], w) >= 0.62f) { found = k; break }; k++ }
        if (found >= 0) j = found + 1
    }
    return j
}

/**
 * Voice Match, in the style of Google's and Siri's set-up: an introduction, a short "how it works", then your phrase and a handful of
 * sentences read out loud. The words light up as they are recognised, a colour wave follows your voice, and each one is checked off.
 * From the recordings it learns both the wake phrase and who you are, so other people saying it are turned away.
 */
@Composable
fun WakeSetup(settings: Settings, palette: GlyphPalette, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val mono = FontFamily.Monospace
    var stage by remember { mutableStateOf("intro") } // intro, more, train, build, done, test
    var phrase by remember { mutableStateOf(settings.wakePhrase.ifBlank { "hey nothing" }) }
    val steps = remember(phrase) { stepsFor(phrase) }
    val templates = remember { ArrayList<Array<FloatArray>>() }
    val embeds = remember { ArrayList<FloatArray>() }
    var step by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(0) }
    var attempt by remember { mutableStateOf(0) }
    var fails by remember { mutableStateOf(0) }
    var lit by remember { mutableStateOf(0) }
    var checked by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    var msg by remember { mutableStateOf("") }
    var needMic by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf<WakeVoice.Model?>(null) }
    var profile by remember { mutableStateOf<Voiceprint.Profile?>(null) }
    var verdict by remember { mutableStateOf("") }
    var matchPct by remember { mutableStateOf(-1) }
    var heardOk by remember { mutableStateOf(0) }
    var vosk by remember { mutableStateOf<org.vosk.Model?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { needMic = false; stage = "train" } else needMic = true
    }
    DisposableEffect(Unit) { onDispose { WakeService.paused = false; VoiceBands.clear() } }
    BackHandler { if (stage == "more") stage = "intro" else onDone() }

    // The phone's offline recogniser, if one is downloaded, lets the words light up for real. Without it they follow your speaking time.
    LaunchedEffect(stage == "train") {
        if (stage == "train" && vosk == null) vosk = withContext(Dispatchers.IO) { runCatching { VoskAsr.get(ctx).modelFor(settings.language) }.getOrNull() }
    }

    LaunchedEffect(stage, step, attempt) {
        when (stage) {
            "train" -> {
                if (step >= steps.size) { stage = "build"; return@LaunchedEffect }
                val st = steps[step]
                val shown = st.words
                val want = shown.map { norm(it) }
                msg = ""; lit = 0; checked = false
                WakeService.paused = true
                delay(if (step == 0 && attempt == 0) 900 else 350) // lets the always-on listener let go of the microphone
                val sentence = st.kind == 1
                val rec = vosk?.let { m -> runCatching { Recognizer(m, 16000f) }.getOrNull() }
                val finals = StringBuilder()
                var voicedMs = 0
                val pcm = withContext(Dispatchers.IO) {
                    WakeVoice.capture(15_000, { !isActive }, maxFrames = if (sentence) 150 else 70, endSilence = if (sentence) 15 else 9,
                        onChunk = { b, n ->
                            if (rec != null) {
                                val fin = runCatching { rec.acceptWaveForm(b, n) }.getOrDefault(false)
                                val txt = runCatching { JSONObject(if (fin) rec.result else rec.partialResult).let { it.optString("text", it.optString("partial", "")) } }.getOrDefault("")
                                if (fin && txt.isNotBlank()) finals.append(' ').append(txt)
                                val heard = norm((if (fin) "" else "$finals ") + " " + txt).split(" ").filter { it.isNotBlank() }
                                val all = if (fin) norm(finals.toString()).split(" ").filter { it.isNotBlank() } else heard
                                lit = max(lit, matched(want, all))
                            } else {
                                if (level > 0.12f) voicedMs += 50
                                val frac = (voicedMs / (want.size * 380f)).coerceIn(0f, 0.92f)
                                lit = max(lit, (frac * want.size).toInt())
                            }
                        }) { level = it }
                }
                level = 0f
                val last = rec?.let { r -> runCatching { JSONObject(r.finalResult).optString("text") }.getOrDefault("") }.orEmpty()
                rec?.let { runCatching { it.close() } }
                if (pcm == null) { msg = "I didn't hear anything. Say it out loud."; lit = 0; delay(1200); attempt++; return@LaunchedEffect }
                if (rec != null) {
                    val all = norm("$finals $last").split(" ").filter { it.isNotBlank() }
                    val frac = matched(want, all).toFloat() / want.size
                    if (frac < 0.5f && fails < 2) { fails++; msg = "I didn't catch every word. Read it out loud."; lit = 0; delay(1300); attempt++; return@LaunchedEffect }
                }
                val e = withContext(Dispatchers.Default) { Voiceprint.embed(pcm) }
                if (e == null) { fails++; msg = "I couldn't hear you clearly. A little louder, please."; lit = 0; delay(1300); attempt++; return@LaunchedEffect }
                if (st.kind == 0) {
                    val f = withContext(Dispatchers.Default) { WakeVoice.features(pcm) }
                    if (f == null) { fails++; msg = "That was too short or too long. Say just the phrase."; lit = 0; delay(1300); attempt++; return@LaunchedEffect }
                    templates.add(f)
                }
                embeds.add(e)
                lit = want.size; fails = 0
                done++
                checked = true
                delay(1000)
                step++
            }
            "build" -> {
                WakeService.paused = true
                val t = templates.toList(); val es = embeds.toList()
                withContext(Dispatchers.Default) {
                    val thr = WakeVoice.calibrate(t)
                    WakeVoice.save(ctx, t, thr)
                    val p = Voiceprint.build(es)
                    if (p != null) Voiceprint.save(ctx, p) else Voiceprint.clear(ctx)
                }
                settings.setWakePhrase(phrase.trim())
                model = WakeVoice.load(ctx); profile = Voiceprint.load(ctx)
                delay(1500)
                stage = "done"
            }
            "test" -> {
                val m = model ?: WakeVoice.load(ctx) ?: return@LaunchedEffect
                profile = profile ?: Voiceprint.load(ctx)
                WakeService.paused = true
                delay(500)
                verdict = ""; matchPct = -1
                while (true) {
                    val pcm = withContext(Dispatchers.IO) { WakeVoice.capture(60_000, { !isActive }) { level = it } }
                    level = 0f
                    if (pcm == null) continue
                    val f = withContext(Dispatchers.Default) { WakeVoice.features(pcm) }
                    val e = withContext(Dispatchers.Default) { Voiceprint.embed(pcm) }
                    val prof = profile
                    matchPct = if (e != null && prof != null) prof.percent(e) else -1
                    val words = f != null && m.accepts(f, settings.wakeSens)
                    val mine = prof == null || (e != null && prof.accepts(e, settings.wakeSens))
                    verdict = when {
                        f == null -> "Too short or too long."
                        !words -> "That wasn't your phrase."
                        settings.voiceLock && !mine -> "Right words, but not your voice."
                        else -> { heardOk++; "Heard you." }
                    }
                }
            }
        }
    }

    val phase = rememberInfiniteTransition(label = "vm").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "p")

    Box(Modifier.fillMaxSize().glassBackdrop(palette)) {
        if (stage == "train" && !checked) AuroraWave(palette, phase, level, false, Color.Black, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f))
        if (stage == "test") AuroraWave(palette, phase, level, false, Color.Black, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f))
        if (stage == "train" && checked) CheckRing(palette, Modifier.align(Alignment.Center).offset(y = 110.dp).size(270.dp))
        if (stage == "build") Spinner(palette, Modifier.align(Alignment.Center).size(260.dp))

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                DotText("N", palette.fg, palette.dim.copy(alpha = 0.35f), 4.dp)
                Spacer(Modifier.weight(1f))
                if (stage == "train") CounterRing(done, steps.size, palette)
            }
            Spacer(Modifier.height(26.dp))

            when (stage) {
                "intro" -> {
                    Text("Set up Voice Match for your assistant to do more by talking", color = palette.fg, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(14.dp))
                    Text("Voice Match lets your assistant recognise you when you say your wake phrase, and stay quiet for everyone else.", color = palette.muted, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(22.dp))
                    Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black)) {
                        AuroraWave(palette, phase, 0f, true, Color.Black, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(18.dp))
                    Field("YOUR WAKE PHRASE", phrase, palette) { phrase = it }
                    Spacer(Modifier.height(4.dp))
                    Text("Two or three words work best, for example \"hey nothing\".", color = palette.muted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.End) {
                        PillButton("MORE", filled = true, palette = palette, onClick = { if (phrase.trim().length >= 4) stage = "more" })
                    }
                }
                "more" -> {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("Voice Match learns your voice", color = palette.fg, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(14.dp))
                        Text("Your assistant can then wake hands-free to your phrase, and ignore other people who say it. It also gets better at hearing you in noisy places.", color = palette.muted, fontSize = 15.sp, lineHeight = 22.sp)
                        Spacer(Modifier.height(26.dp))
                        Text("How it works", color = palette.fg, fontSize = 17.sp)
                        Spacer(Modifier.height(10.dp))
                        Bullet("You'll say your phrase a few times, then read some short sentences, so it can build a model of your voice.", palette)
                        Bullet("When someone speaks, their voice is compared with that model to know whether it's you.", palette)
                        Bullet("The model is stored only on this phone. Nothing is uploaded, and no download is needed.", palette)
                        Spacer(Modifier.height(22.dp))
                        Text("Keep in mind", color = palette.fg, fontSize = 17.sp)
                        Spacer(Modifier.height(10.dp))
                        Bullet("A very similar voice, or a recording of yours, could make Voice Match think it's you.", palette)
                        Bullet("You can retrain it, turn the voice check off or delete it any time in Voice settings.", palette)
                        if (needMic) { Spacer(Modifier.height(12.dp)); Text("The microphone permission is needed to learn your voice.", color = palette.red, fontSize = 13.sp) }
                        Spacer(Modifier.height(16.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PillButton("NO THANKS", filled = false, palette = palette, onClick = { onDone() })
                        PillButton("I AGREE", filled = true, palette = palette, onClick = {
                            templates.clear(); embeds.clear(); step = 0; done = 0; attempt = 0; fails = 0
                            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) stage = "train"
                            else permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        })
                    }
                }
                "train" -> {
                    val st = steps[step.coerceAtMost(steps.size - 1)]
                    val prefix = if (done == 0) "Say \"" else "Now say \""
                    val fg = palette.fg
                    val dim = palette.muted
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = fg)) { append(prefix.dropLast(1)) }
                            withStyle(SpanStyle(color = if (lit > 0) fg else dim)) { append("\"") }
                            st.words.forEachIndexed { i, w ->
                                withStyle(SpanStyle(color = if (i < lit) fg else dim)) { append(if (i > 0) " $w" else w) }
                            }
                            withStyle(SpanStyle(color = if (lit >= st.words.size) fg else dim)) { append("\"") }
                        },
                        fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light
                    )
                    Spacer(Modifier.height(14.dp))
                    if (msg.isNotBlank()) Text(msg, color = palette.red, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("Cancel", color = palette.muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 24.dp).clickable { onDone() })
                }
                "build" -> {
                    Text("Just a sec...", color = palette.fg, fontSize = 32.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.weight(1f))
                }
                "done" -> {
                    Spacer(Modifier.weight(1f))
                    Text("You're all set!", color = palette.fg, fontSize = 40.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (Voiceprint.ready(ctx)) "Say \"${phrase.trim()}\" any time. Your assistant wakes for your voice and ignores everyone else."
                        else "Say \"${phrase.trim()}\" any time and your assistant wakes.",
                        color = palette.muted, fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.weight(1.3f))
                    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PillButton("TRY IT", filled = false, palette = palette, onClick = { verdict = ""; heardOk = 0; stage = "test" })
                        PillButton("DONE", filled = true, palette = palette, onClick = { finish(settings, ctx, onDone) })
                    }
                }
                else -> { // test
                    Text("Say \"${phrase.trim()}\"", color = palette.fg, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(14.dp))
                    Text(if (verdict.isBlank()) "Listening…" else verdict, color = if (verdict == "Heard you.") palette.fg else palette.muted, fontSize = 18.sp)
                    if (matchPct >= 0) Text("VOICE MATCH $matchPct%", color = palette.red, fontFamily = mono, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                    if (heardOk > 0) Text("✓ ×$heardOk", color = palette.muted, fontFamily = mono, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PillButton("RETRAIN", filled = false, palette = palette, onClick = { templates.clear(); embeds.clear(); step = 0; done = 0; attempt = 0; fails = 0; stage = "train" })
                        PillButton("DONE", filled = true, palette = palette, onClick = { finish(settings, ctx, onDone) })
                    }
                }
            }
        }
    }
}

private fun finish(settings: Settings, ctx: android.content.Context, onDone: () -> Unit) {
    WakeService.paused = false
    settings.setWakeWord(true)
    WakeService.stop(ctx); WakeService.start(ctx)
    onDone()
}

@Composable
private fun Bullet(text: String, palette: GlyphPalette) {
    Row(Modifier.padding(bottom = 12.dp)) {
        Text("•", color = palette.muted, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp))
        Text(text, color = palette.muted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** Steps done so far, as a number inside a ring that fills up. */
@Composable
private fun CounterRing(done: Int, total: Int, palette: GlyphPalette) {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 3.dp.toPx()
            drawCircle(palette.dim, radius = size.minDimension / 2f - sw / 2f, style = Stroke(sw))
            drawArc(palette.fg, -90f, 360f * done / max(total, 1), false, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("$done", color = palette.muted, fontSize = 22.sp)
    }
}

/** A soft wave of colour whose crest follows the sound of your voice, band by band (deep voice on the left, high on the right). */
@Composable
private fun AuroraWave(palette: GlyphPalette, phase: State<Float>, level: Float, demo: Boolean, floor: Color, modifier: Modifier) {
    val cols = remember { listOf(Color(0xFF3F8CFF), Color(0xFF2EC27E), Color(0xFFE6C619), Color(0xFFFF8A3D), Color(0xFFFF4F7B)) }
    val xs = remember { FloatArray(26) }
    val ys = remember { FloatArray(26) }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = phase.value
        val n = VoiceBands.N
        val lv = VoiceBands.levels
        var tot = 0f
        for (v in lv) tot += v
        val energy = if (demo) 0.5f else max((tot / n * 1.8f).coerceIn(0f, 1f), level)
        val glow = 0.28f + 0.72f * energy
        val baseY = h * 0.60f
        for (i in cols.indices) {
            val cx = w * i / (cols.size - 1)
            val cy = baseY + h * 0.06f * sin(p + i * 1.3f)
            val r = w * 0.55f
            drawCircle(Brush.radialGradient(listOf(cols[i].copy(alpha = 0.9f * glow), Color.Transparent), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
        }
        val segs = 24
        for (k in 0..segs) {
            val u = k.toFloat() / segs * (n - 1)
            val i0 = min(u.toInt(), n - 2)
            val fr = u - i0
            val b = if (demo) 0.35f + 0.25f * sin(p * 1.3f + u * 0.9f) else lv[i0] * (1f - fr) + lv[i0 + 1] * fr
            xs[k] = w * k / segs
            ys[k] = baseY - h * 0.34f * b - h * 0.02f * sin(p * 1.6f + k * 0.45f) + h * 0.03f
        }
        val path = Path()
        path.moveTo(0f, ys[0])
        for (k in 0 until segs) path.quadraticBezierTo(xs[k], ys[k], (xs[k] + xs[k + 1]) / 2f, (ys[k] + ys[k + 1]) / 2f)
        path.lineTo(w, ys[segs])
        path.lineTo(w, h); path.lineTo(0f, h); path.close()
        drawPath(path, Brush.verticalGradient(listOf(floor.copy(alpha = 0.50f), floor.copy(alpha = 0.92f), floor), startY = baseY - h * 0.3f, endY = h))
    }
}

/** The blue-ring-and-tick of a finished step: the circle draws itself, then the tick. */
@Composable
private fun CheckRing(palette: GlyphPalette, modifier: Modifier) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(1f, tween(700, easing = LinearEasing)) }
    val ring = palette.red
    Canvas(modifier) {
        val sw = 5.dp.toPx()
        val p = prog.value
        drawArc(ring, -90f, 360f * min(1f, p / 0.65f), false, topLeft = Offset(sw, sw), size = Size(size.width - 2 * sw, size.height - 2 * sw), style = Stroke(sw, cap = StrokeCap.Round))
        val k = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (k > 0f) {
            val c = center; val r = size.minDimension / 2f
            val a = Offset(c.x - r * 0.28f, c.y + r * 0.02f)
            val b = Offset(c.x - r * 0.06f, c.y + r * 0.24f)
            val d = Offset(c.x + r * 0.30f, c.y - r * 0.20f)
            val path = Path()
            path.moveTo(a.x, a.y)
            if (k < 0.4f) { val q = k / 0.4f; path.lineTo(a.x + (b.x - a.x) * q, a.y + (b.y - a.y) * q) }
            else { val q = (k - 0.4f) / 0.6f; path.lineTo(b.x, b.y); path.lineTo(b.x + (d.x - b.x) * q, b.y + (d.y - b.y) * q) }
            drawPath(path, ring, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A short arc chasing round a circle while the voice model is being built. */
@Composable
private fun Spinner(palette: GlyphPalette, modifier: Modifier) {
    val ang = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val c = palette.red
    Canvas(modifier) {
        val sw = 5.dp.toPx()
        for (i in 0 until 14) {
            drawArc(c.copy(alpha = (1f - i / 14f) * 0.9f), ang.value - i * 4f, 4f, false, topLeft = Offset(sw, sw), size = Size(size.width - 2 * sw, size.height - 2 * sw), style = Stroke(sw, cap = StrokeCap.Butt))
        }
    }
}
