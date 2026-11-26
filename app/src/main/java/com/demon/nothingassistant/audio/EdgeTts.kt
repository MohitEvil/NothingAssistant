package com.demon.nothingassistant.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge's free "Read Aloud" voices (350+ neural voices, no account, no key), adapted from the AndroidHarness project.
 * Speech is a short WebSocket session: send the SSML, collect MP3 frames until turn.end. The voice list is one plain HTTP call.
 * The service is unofficial, so every call can fail; callers fall back to the offline voice.
 */
object EdgeTts {
    const val URL_PREFIX = "edge://"
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
    private const val VOICES = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=$TOKEN"
    private const val FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val FALLBACK_BUILD = "156.0.4314.80"
    private const val EPOCH = 11_644_473_600L

    @Volatile private var build = FALLBACK_BUILD
    @Volatile private var buildAt = 0L

    class Voice(val shortName: String, val locale: String, val localeName: String, val gender: String) {
        /** "en-US-AriaNeural" -> "Aria"; multilingual ones keep the marker. */
        val name: String get() = shortName.split('-').drop(2).joinToString("-").removeSuffix("Neural").ifBlank { shortName }
        val label: String get() = shortName.split('-').drop(2).joinToString("-").removeSuffix("Neural") + "  ·  " + localeName.ifBlank { locale } + (if (gender.isNotBlank()) "  ·  $gender" else "")
    }

    class EdgeError(message: String, val status: Int?) : IOException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val plain = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()

    private fun sha256(v: String) = MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.uppercase(Locale.US)

    private fun gec(now: Long): String {
        val sec = now / 1000
        val rounded = sec - sec % 300
        return sha256("${(rounded + EPOCH) * 10_000_000L}$TOKEN")
    }

    private fun stamp(now: Long): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))

    private fun tag(short: String): String {
        val p = short.split('-')
        if (p.size < 3) return "Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)"
        return "Microsoft Server Speech Text to Speech Voice (${p[0]}-${p[1]}, ${p.drop(2).joinToString("-")})"
    }

    private fun esc(t: String) = buildString { for (c in t) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c) } }

    private fun ssml(text: String, short: String, speed: Float): String {
        val pct = ((speed.coerceIn(0.5f, 2f) - 1f) * 100).toInt()
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='${tag(short)}'>" +
            "<prosody rate='${if (pct >= 0) "+" else ""}$pct%' pitch='+0Hz'>${esc(text)}</prosody></voice></speak>"
    }

    /** Microsoft refuses clients that claim an old Edge build, so the current one is read from its public update feed (cached a day). */
    private fun refreshBuild(force: Boolean) {
        if (!force && System.currentTimeMillis() - buildAt < 24L * 3600_000L) return
        try {
            val req = Request.Builder().url("https://edgeupdates.microsoft.com/api/products?view=enterprise").build()
            plain.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return
                val arr = JSONArray(r.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.optString("Product").equals("Stable", true)) continue
                    val rel = o.optJSONArray("Releases") ?: continue
                    var best = ""; var bestAt = ""
                    for (j in 0 until rel.length()) {
                        val x = rel.optJSONObject(j) ?: continue
                        if (!x.optString("Platform").equals("Windows", true)) continue
                        val v = x.optString("ProductVersion"); val at = x.optString("PublishedTime")
                        if (Regex("""\d+\.\d+\.\d+\.\d+""").matches(v) && at >= bestAt) { best = v; bestAt = at }
                    }
                    if (best.isNotEmpty()) { build = best; buildAt = System.currentTimeMillis(); return }
                }
            }
        } catch (_: Exception) { }
    }

    /** Gender of a voice from the cached list, without touching the network. "" when unknown. */
    fun genderOf(cache: File, short: String): String = runCatching {
        val arr = JSONArray(File(cache, "edge_voices.json").readText())
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("ShortName") == short) return@runCatching o.optString("Gender") }
        ""
    }.getOrDefault("")

    /** All Edge voices: [shortName, locale, language name, gender]. Cached on disk for a day. */
    fun voices(cache: File): List<Voice> {
        val file = File(cache, "edge_voices.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 24L * 3600_000L
        var body = if (fresh) runCatching { file.readText() }.getOrNull() else null
        if (body == null) {
            body = try {
                plain.newCall(Request.Builder().url(VOICES).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (_: Exception) { null }
            if (body != null) runCatching { cache.mkdirs(); file.writeText(body) }
        }
        if (body == null && file.exists()) body = runCatching { file.readText() }.getOrNull()
        if (body == null) throw IOException("Couldn't reach Edge's voice list. Check the internet.")
        val arr = JSONArray(body)
        val out = ArrayList<Voice>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("ShortName")
            if (s.isNotBlank()) out.add(Voice(s, o.optString("Locale"), o.optString("LocaleName"), o.optString("Gender")))
        }
        if (out.isEmpty()) throw IOException("Edge sent an empty voice list.")
        // The language the phone uses first, then the rest alphabetically.
        val lang = Locale.getDefault().language
        return out.sortedWith(compareBy<Voice>({ if (it.locale.startsWith("en-US")) 0 else if (it.locale.startsWith(lang)) 1 else 2 }, { it.locale }, { it.shortName }))
    }

    /** Blocking: one clip of speech as MP3 bytes. Call from a background thread. */
    fun synthesize(text: String, short: String, speed: Float = 1f): ByteArray {
        if (text.none { it.isLetterOrDigit() }) throw IllegalArgumentException("Nothing to speak")
        if (short.isBlank()) throw IllegalStateException("Choose an Edge voice first.")
        refreshBuild(false)
        // 403 = stale client build (refresh it); 429, 5xx and "no audio" are brief server hiccups, so one or two quick retries usually fix them.
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                return request(text.take(4000), short, speed)
            } catch (e: EdgeError) {
                last = e
                val st = e.status
                val transient = st == 429 || (st != null && st >= 500) || e.message.orEmpty().contains("no audio", true)
                if (st == 403 && attempt == 0) refreshBuild(true)
                else if (transient && attempt < 2) Thread.sleep(250L * (attempt + 1) * (if (st == 429) 3 else 1))
                else throw e
            }
        }
        throw last ?: IOException("Edge voice failed")
    }

    private fun request(text: String, short: String, speed: Float): ByteArray {
        val now = System.currentTimeMillis()
        val major = build.substringBefore('.')
        val url = "$WSS?Ocp-Apim-Subscription-Key=$TOKEN&Sec-MS-GEC=${gec(now)}&Sec-MS-GEC-Version=1-$build&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0")
            .build()
        val audio = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var error: EdgeError? = null
        val rid = UUID.randomUUID().toString().replace("-", "")
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("X-Timestamp:${stamp(now)}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"$FORMAT"}}}}""")
                webSocket.send("X-RequestId:$rid\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${stamp(now)}Z\r\nPath:ssml\r\n\r\n" + ssml(text, short, speed))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val f = bytes.toByteArray()
                if (f.size < 2) return
                val hl = ((f[0].toInt() and 0xFF) shl 8) or (f[1].toInt() and 0xFF)
                val start = 2 + hl
                if (hl > 0 && start < f.size) audio.write(f, start, f.size - start)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("Path:turn.end")) latch.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val c = response?.code
                error = EdgeError(when (c) { 403 -> "Edge refused the connection (403)."; 401 -> "Edge asked for sign-in (401)."; 451 -> "Edge is blocked in this region (451)."; null -> t.message ?: "Edge connection failed."; else -> "Edge connection failed (HTTP $c)." }, c)
                latch.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); latch.countDown() }
        })
        try {
            if (!latch.await(40, TimeUnit.SECONDS)) throw EdgeError("Edge timed out without sending audio.", null)
        } finally { runCatching { ws.cancel() } }
        error?.let { throw it }
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw EdgeError("Edge returned no audio.", null)
        return bytes
    }
}
