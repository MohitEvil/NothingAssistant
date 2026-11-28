package com.demon.nothingassistant.ai

import com.demon.nothingassistant.data.PersonaPresets
import com.demon.nothingassistant.data.Provider
import com.demon.nothingassistant.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Turn(val role: String, val text: String) // role: "user" or "assistant"

class BrainException(message: String) : Exception(message)

/**
 * Optional chat brain. Uses the user's own API key and talks straight to the provider
 * (OpenAI-style chat completions, or Anthropic's messages API). Nothing is sent unless a key is set.
 */
class Brain(private val settings: Settings) {

    private class Reply(val code: Int, val body: String)

    /** "female", "male" or "" : the gender of the voice that speaks the replies. Set by the controller. */
    var genderProvider: () -> String = { "" }

    private fun genderClause(): String = when (genderProvider()) {
        "female" -> " Your spoken voice is female, so speak as a woman: in English refer to yourself naturally as a woman would, and in Hindi or Hinglish always use feminine forms for yourself (main karti hoon, bolti hoon, kar rahi hoon, meri, ...), never masculine ones."
        "male" -> " Your spoken voice is male, so speak as a man: in Hindi or Hinglish always use masculine forms for yourself (main karta hoon, bolta hoon, kar raha hoon, mera, ...), never feminine ones."
        else -> ""
    }

    fun configured(): Boolean = settings.aiEnabled && settings.providers.any { it.ready }

    @Volatile private var warmedAt = 0L

    /** Opens the network connection to the first AI provider ahead of time (DNS, TCP and TLS), so the real request starts faster. Costs no AI usage. */
    fun warmUp() {
        val now = System.currentTimeMillis()
        if (now - warmedAt < 40_000 || !configured()) return
        warmedAt = now
        val p = settings.providers.toList().firstOrNull { it.ready } ?: return
        val base = p.baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) return
        Thread {
            runCatching {
                val c = URL(base).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000
                c.responseCode // the connection is kept in the pool for the next request to the same host
                runCatching { c.inputStream.close() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Tries each ready provider in order and uses the first that answers. */
    suspend fun ask(history: List<Turn>, userText: String, system: String? = null, image: String? = null, big: Boolean = false, fast: Boolean = false): Result<String> {
        var last: Result<String> = Result.failure(BrainException("No AI provider is set up."))
        for (p in settings.providers.toList().filter { it.ready }) {
            val r = askOne(p, history, userText, system, image, big, fast)
            if (r.isSuccess) return r
            last = r
        }
        return last
    }

    suspend fun askOne(p: Provider, history: List<Turn>, userText: String, system: String? = null, image: String? = null, big: Boolean = false, fast: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(call(p, history, userText, system, image, big, fast))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrainException) {
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(BrainException("No internet, or ${p.name}'s address can't be found."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(BrainException("${p.name} took too long to answer."))
        } catch (e: javax.net.ssl.SSLException) {
            Result.failure(BrainException("Secure connection failed. Check the phone's date and time, or try another network."))
        } catch (e: java.net.MalformedURLException) {
            Result.failure(BrainException("The ADDRESS for ${p.name} isn't valid. It should start with https://"))
        } catch (e: IOException) {
            Result.failure(BrainException("Network problem (${e.javaClass.simpleName}: ${e.message?.take(60)})."))
        } catch (e: Exception) {
            Result.failure(BrainException("I couldn't read the AI's answer (${e.javaClass.simpleName})."))
        }
    }

    /** Asks the provider which models this key can use. Returns plain model ids, sorted. */
    suspend fun fetchModels(p: Provider): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val base = p.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) throw BrainException("Fill in ADDRESS first.")
            if (p.key.isBlank() && !p.keyless) throw BrainException("Fill in API KEY first.")
            val anthropic = p.id == "anthropic" || base.contains("anthropic.com")
            val conn = URL("$base/models" + if (anthropic) "?limit=100" else "").openConnection() as HttpURLConnection
            val reply = try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 25_000
                if (anthropic) {
                    conn.setRequestProperty("x-api-key", p.key.trim())
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                } else if (p.key.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer " + p.key.trim())
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Reply(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
            if (reply.code !in 200..299) throw BrainException(errorMessage(reply.code, reply.body))
            val root = JSONObject(reply.body)
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val ids = ArrayList<String>()
            val kilo = base.contains("api.kilo.ai")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                // Kilo lists paid models too: keep only the ones it marks free.
                if (kilo && o?.optBoolean("isFree", false) != true) continue
                val id = (o?.optString("id").orEmpty().ifBlank { o?.optString("name").orEmpty() })
                    .removePrefix("models/")
                if (id.isNotBlank()) ids.add(id)
            }
            if (ids.isEmpty()) throw BrainException("No models came back from that address.")
            Result.success(ids.distinct().sorted())
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrainException) {
            Result.failure(e)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(BrainException("No internet, or the address can't be found."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(BrainException("The provider took too long to answer."))
        } catch (e: Exception) {
            Result.failure(BrainException("Couldn't fetch models (${e.javaClass.simpleName})."))
        }
    }

    private fun call(p: Provider, history: List<Turn>, userText: String, systemOverride: String? = null, image: String? = null, big: Boolean = false, fast: Boolean = false): String {
        val base = p.baseUrl.trim().trimEnd('/')
        val anthropic = p.id == "anthropic" || base.contains("anthropic.com")
        // Fast mode (planning and screen steps): no thinking, short reply. The answer is a small JSON object, so deliberating only costs seconds.
        val thinking = settings.aiThinking && !fast
        val system = (systemOverride ?: systemPrompt()) + genderClause() + (if (systemOverride == null) Mem.block(userText) else "")

        val messages = JSONArray()
        if (!anthropic) messages.put(msg("system", system))
        history.takeLast(10).forEach { messages.put(msg(it.role, it.text)) }
        if (image == null) messages.put(msg("user", userText))
        else {
            val parts = JSONArray()
            if (anthropic) parts.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", image)))
            else parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")))
            parts.put(JSONObject().put("type", "text").put("text", userText))
            messages.put(JSONObject().put("role", "user").put("content", parts))
        }

        // Thinking tokens count against the limit, so leave plenty of room or answers get cut off mid-sentence.
        val room = if (fast) (if (alwaysThinks(p)) 3072 else 900) else if (big) (if (thinking) 6144 else 2048) else if (thinking) 4096 else 1024

        val reply: Reply = if (anthropic) {
            var r = post(p, base + "/messages", anthropicBody(p, messages, system, room, thinking), anthropic = true, big = big, fast = fast)
            if (r.code == 400 && thinking) r = post(p, base + "/messages", anthropicBody(p, messages, system, room, false), anthropic = true, big = big, fast = fast)
            r
        } else {
            val url = base + "/chat/completions"
            var r = post(p, url, openAiBody(p, messages, room, thinking, useCompletionTokens = false, extras = true), anthropic = false, big = big, fast = fast)
            // Some models reject certain fields. Retry with gentler requests before giving up.
            if (r.code == 400 || r.code == 422) {
                r = post(p, url, openAiBody(p, messages, room, thinking, useCompletionTokens = true, extras = true), anthropic = false, big = big, fast = fast)
            }
            if (r.code == 400 || r.code == 422) {
                r = post(p, url, openAiBody(p, messages, room, thinking, useCompletionTokens = true, extras = false), anthropic = false, big = big, fast = fast)
            }
            r
        }
        if (reply.code !in 200..299) throw BrainException(errorMessage(reply.code, reply.body))
        return parse(reply.body, anthropic)
    }

    private fun anthropicBody(p: Provider, messages: JSONArray, system: String, room: Int, thinking: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", p.model.trim())
            .put("max_tokens", room)
            .put("system", system)
            .put("messages", messages)
        if (thinking) body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 2048))
        return body
    }

    private fun openAiBody(
        p: Provider,
        messages: JSONArray,
        room: Int,
        thinking: Boolean,
        useCompletionTokens: Boolean,
        extras: Boolean
    ): JSONObject {
        val body = JSONObject().put("model", p.model.trim()).put("messages", messages)
        if (useCompletionTokens) body.put("max_completion_tokens", room) else body.put("max_tokens", room)
        if (!useCompletionTokens) body.put("temperature", 0.7)
        if (extras) addThinkingParams(p, body, thinking)
        return body
    }

    /** Turns thinking on or off. Each provider spells this differently, so cover the common ones by host and by model name. */
    private fun addThinkingParams(p: Provider, body: JSONObject, thinking: Boolean) {
        val host = p.baseUrl.lowercase()
        val model = p.model.lowercase()
        when {
            host.contains("generativelanguage.googleapis.com") ->
                body.put(
                    "reasoning_effort",
                    when {
                        thinking -> "medium"
                        model.contains("2.5-pro") || model.contains("gemini-3") -> "low" // these cannot be fully switched off
                        else -> "none"
                    }
                )
            host.contains("groq.com") -> when {
                model.contains("gpt-oss") -> body.put("reasoning_effort", if (thinking) "medium" else "low")
                model.contains("qwen3") -> body.put("reasoning_effort", if (thinking) "default" else "none")
            }
            host.contains("openrouter.ai") -> {
                body.put("reasoning", JSONObject().put("enabled", thinking))
                if (!thinking) body.put("include_reasoning", false)
            }
            host.contains("openai.com") ->
                if (model.startsWith("gpt-5") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")) {
                    body.put("reasoning_effort", if (thinking) "medium" else if (model.startsWith("gpt-5")) "minimal" else "low")
                }
            host.contains("deepseek.com") ->
                body.put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            host.contains("x.ai") ->
                if (model.contains("mini")) body.put("reasoning_effort", if (thinking) "high" else "low")
            host.contains("dashscope") || host.contains("aliyun") ->
                body.put("enable_thinking", thinking)
            host.contains("nvidia.com") ->
                body.put("chat_template_kwargs", JSONObject().put("thinking", thinking).put("enable_thinking", thinking))
            host.contains("11434") || host.contains("ollama") || host.contains("localhost") ->
                body.put("think", thinking)
        }
        if (!thinking && !body.has("reasoning_effort") && !body.has("thinking") && !body.has("reasoning")) {
            // Gateways and custom addresses: decide by model family.
            when {
                model.contains("qwen") || model.contains("qwq") -> {
                    body.put("enable_thinking", false)
                    body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }
                model.contains("deepseek") || model.contains("glm") || model.contains("kimi") ->
                    body.put("thinking", JSONObject().put("type", "disabled"))
                model.contains("gpt-oss") -> body.put("reasoning_effort", "low")
                model.contains("gemini-2.5-flash") -> body.put("reasoning_effort", "none")
            }
        }
    }

    /** Models that reason no matter what is sent. Used to warn the user. */
    fun alwaysThinks(p: Provider): Boolean {
        val m = p.model.lowercase()
        return m.contains("reasoner") || m.contains("-r1") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
            m.contains("thinking") || m.contains("2.5-pro") || m.contains("gemini-3") || m.contains("qwq") || m.contains("gpt-oss")
    }

    private fun post(p: Provider, url: String, body: JSONObject, anthropic: Boolean, big: Boolean = false, fast: Boolean = false): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = if (big) 75_000 else if (fast) 14_000 else 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (anthropic) {
                conn.setRequestProperty("x-api-key", p.key.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else if (p.key.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + p.key.trim())
            }
            if (p.keyless) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/MohitEvil/NothingAssistant")
                conn.setRequestProperty("X-Title", "Nothing Assistant")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = try {
                conn.responseCode
            } catch (e: java.net.HttpRetryException) {
                e.responseCode()
            } catch (e: IOException) {
                // Android reports a rejected key (HTTP 401) as an IOException on POST requests.
                if (e.message?.contains("authentication challenge", ignoreCase = true) == true) 401 else throw e
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, anthropic: Boolean): String {
        val json = JSONObject(body)
        val raw: String
        val truncated: Boolean
        if (anthropic) {
            val blocks = json.getJSONArray("content")
            raw = buildString {
                for (i in 0 until blocks.length()) {
                    val b = blocks.getJSONObject(i)
                    if (b.optString("type") == "text") append(b.optString("text"))
                }
            }
            truncated = json.optString("stop_reason") == "max_tokens"
        } else {
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            raw = if (message.isNull("content")) "" else message.optString("content")
            truncated = choice.optString("finish_reason") == "length"
        }
        var reply = TextClean.forDisplay(raw)
        if (truncated) {
            if (reply.isBlank()) {
                throw BrainException("The AI ran out of room while thinking. Turn off Thinking mode in Settings.")
            }
            reply = endAtSentence(reply)
        }
        if (reply.isBlank()) throw BrainException("The AI sent an empty answer.")
        return reply
    }

    /** If the answer was cut off by a length limit, end it cleanly at the last full sentence. */
    private fun endAtSentence(text: String): String {
        val last = text.indexOfLast { it == '.' || it == '!' || it == '?' || it == '।' }
        return if (last >= text.length / 2) text.substring(0, last + 1) else text
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val j = JSONObject(body)
            when (val e = j.opt("error")) {
                is JSONObject -> e.optString("message")
                is String -> e
                else -> ""
            }
        }.getOrDefault("")
        return when (code) {
            400 -> "The AI rejected the request. Check the model name in Settings." +
                if (detail.isNotBlank()) " (${detail.take(70)})" else ""
            401, 403 -> "The AI key was rejected. Check it in Settings."
            404 -> "The AI address or model wasn't found. Check Settings."
            429 -> "The AI is rate limited right now. Try again in a minute. (The free AI allows about 200 requests an hour.)"
            402 -> "The free AI limit is used up for now. Try again later, or add your own key."
            else -> if (detail.isNotBlank()) "AI error $code: ${detail.take(80)}" else "The AI service had an error ($code)."
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        val persona = settings.aiPersona.trim().ifEmpty { PersonaPresets.DEFAULT }
        return "You are Nothing Assistant, a voice assistant on the user's Android phone. " +
            "How you should behave and sound: $persona " +
            "Your replies are spoken aloud: plain text only, no markdown, no lists, no emojis, no links. " +
            "Use natural punctuation so the voice sounds expressive. Always finish your sentences. " +
            "Reply in the language the user used. If they speak Hindi or Hinglish (Hindi in Latin letters), " +
            "reply the same way, using Devanagari only if they did (unless the persona above says how to write Hindi). " +
            "Be honest when you don't know something. " +
            "Phone actions such as the flashlight, apps, timers, alarms, calls and texts are handled by the phone separately, " +
            "so never claim you did one. The phone can also restart or shut down, run system commands and operate other apps by looking at the screen (it asks the user to confirm anything risky), " +
            "so never tell the user that you can't do such a thing: say you'll do it, or ask them to repeat the command. You have no live internet, so say so for news, scores or prices. " +
            "Current date and time: $now."
    }
}
