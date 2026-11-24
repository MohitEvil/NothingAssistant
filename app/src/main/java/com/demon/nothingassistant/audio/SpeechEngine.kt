package com.demon.nothingassistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.demon.nothingassistant.debug.AppLog

/**
 * Thin wrapper over Android's SpeechRecognizer. It only listens between start() and the final
 * result (or cancel), so the microphone is never open while the assistant is idle.
 *
 * One recognizer is reused between sessions: creating a new one for every turn can make the
 * recognition service drop (error 11). If it does drop, the controller calls release() and retries.
 * All calls must be made on the main thread.
 */
class SpeechEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onRms(db: Float)
        fun onError(code: Int)
    }

    companion object {
        /** Our own code: no recognition service installed. */
        const val ERROR_UNAVAILABLE = -1
        /** No downloaded model for the chosen on-device recogniser. */
        const val ERROR_NOMODEL = -2
        /** The on-device recogniser failed to start. */
        const val ERROR_ENGINE = -3
    }

    /** The other guesses the recogniser made for the last thing it heard, best first (without the top one). */
    @Volatile
    var alternatives: List<String> = emptyList()
        private set

    private val recs = HashMap<Int, SpeechRecognizer>()
    private var current: SpeechRecognizer? = null
    private var lastError = SpeechRecognizer.ERROR_CLIENT
    private var generation = 0

    /** A real recognition service. Ours is only a placeholder that lets the system list us as an assistant, so it is never used. */
    private fun realService(): android.content.ComponentName? {
        val found = context.packageManager.queryIntentServices(android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }.filter { it.packageName != context.packageName }
        val pick = found.firstOrNull { it.packageName.contains("googlequicksearchbox") } ?: found.firstOrNull() ?: return null
        return android.content.ComponentName(pick.packageName, pick.name)
    }

    private val vosk get() = VoskAsr.get(context)
    private var usingDevice = false

    /** Other languages to listen for besides the main one (set by the controller; empty = single language). */
    var extraLanguages: () -> List<String> = { emptyList() }

    /** When true the first extra language is used as the main one (the retry after a turn nothing was understood in). */
    @Volatile var swapLanguage = false

    /**
     * Which recogniser a turn uses. "auto": with internet, Google's online recogniser (the most accurate, and it can take a second
     * language); without internet, Google's offline pack if that language is downloaded, otherwise the on-device Vosk model.
     * "system" and "device" force one. If the online attempt fails for lack of network, the same turn falls back to offline.
     */
    fun start(languageTag: String, preferOffline: Boolean, choice: String = "auto") = begin(languageTag, preferOffline, choice)

    private fun begin(tag0: String, preferOffline: Boolean, choice: String) {
        val extras = extraLanguages()
        val tag = if (swapLanguage && extras.isNotEmpty()) extras.first() else tag0
        val systemOk = SpeechRecognizer.isRecognitionAvailable(context) || realService() != null
        val online = SpeechInfo.online(context)
        val offlineNow = preferOffline || !online
        val deviceChoice = choice == "device" || choice == "live" || choice == "accurate" || choice == "twopass"
        val autoDevice = choice == "auto" && (!systemOk || (offlineNow && !SpeechInfo.hasOffline(tag) && vosk.anyInstalled()))
        if (deviceChoice || autoDevice) {
            if (vosk.anyInstalled()) {
                usingDevice = true
                generation++
                runCatching { current?.cancel() }
                vosk.start(tag, listener)
                return
            }
            if (!systemOk || choice != "auto") { usingDevice = false; listener.onError(ERROR_NOMODEL); return }
        }
        usingDevice = false
        if (!systemOk) { listener.onError(ERROR_UNAVAILABLE); return }
        Cue.hushSystemBeep(context, 20_000) // muted for the whole listening turn, so neither the start nor the stop ding is heard
        val routes = routesFor(offlineNow)
        AppLog.d("ASR", "turn tag=$tag online=$online offlineNow=$offlineNow routes=$routes")
        listen(tag0, tag, extras, offlineNow, choice, routes, 0)
    }

    /** The ways to reach Google's recogniser, best first. 0 = the speech service found by name, 1 = the on-device recogniser (Android 13+), 2 = the phone's default. */
    private fun routesFor(offlineNow: Boolean): List<Int> {
        val onDevice = android.os.Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val named = realService() != null
        // The phone's default recogniser (2) is the one that proved reliable for every language; the named service (0) often drops or refuses a language.
        val base = buildList {
            if (offlineNow && onDevice) add(1)
            add(2)
            if (named) add(0)
            if (!offlineNow && onDevice) add(1)
        }.distinct()
        val good = lastGood
        return if (good != null && good in base) listOf(good) + base.filter { it != good } else base
    }

    /** The route that last gave a real answer; tried first next time. */
    @Volatile private var lastGood: Int? = null

    @android.annotation.SuppressLint("NewApi")
    private fun recognizerFor(route: Int): SpeechRecognizer? = recs[route] ?: runCatching {
        when (route) {
            0 -> realService()?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            1 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()?.also { recs[route] = it }

    private fun listen(tag0: String, tag: String, extras: List<String>, offlineNow: Boolean, choice: String, routes: List<Int>, idx: Int) {
        val route = routes.getOrNull(idx)
        val r = route?.let { recognizerFor(it) }
        if (route == null || r == null) {
            if (route != null) { listen(tag0, tag, extras, offlineNow, choice, routes, idx + 1); return }
            if (vosk.anyInstalled()) { usingDevice = true; generation++; vosk.start(tag, listener) } else listener.onError(lastError)
            return
        }
        val gen = ++generation
        current = r
        runCatching { r.cancel() }
        fun next(code: Int) {
            lastError = code
            AppLog.d("ASR", "route $route failed with error $code (tag=$tag); trying the next way")
            runCatching { r.cancel() }
            if (code == SpeechRecognizer.ERROR_CLIENT || code == 10 || code == 11) { runCatching { r.destroy() }; recs.remove(route) }
            listen(tag0, tag, extras, offlineNow, choice, routes, idx + 1)
        }
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = gen == generation

            override fun onReadyForSpeech(params: Bundle?) { if (live()) AppLog.d("ASR", "route $route listening") }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Cue.hushThenRelease(context, 1500) }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                if (live()) listener.onRms(rmsdB)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!live()) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                Cue.hushThenRelease(context, 1500)
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                alternatives = all.drop(1)
                AppLog.d("ASR", "route $route heard: ${all.firstOrNull().orEmpty()}")
                if (all.isNotEmpty()) lastGood = route
                listener.onFinal(all.firstOrNull().orEmpty())
            }

            override fun onError(error: Int) {
                if (!live()) return
                Cue.hushThenRelease(context, 1500)
                // The online attempt couldn't reach the server (timeout, network, server): do the same turn offline instead of failing.
                if (!offlineNow && error in intArrayOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER)) {
                    val canOffline = SpeechInfo.hasOffline(tag) || vosk.anyInstalled()
                    if (canOffline) { runCatching { r.cancel() }; begin(tag0, true, choice); return }
                }
                // Nothing heard / busy / no permission are real answers; anything else means this route is broken, so try the next one.
                val answer = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (!answer && idx + 1 < routes.size) { next(error); return }
                if (!answer && vosk.anyInstalled() && (error == 12 || error == 13 || offlineNow)) {
                    AppLog.d("ASR", "Google could not do $tag (error $error); using Vosk")
                    usingDevice = true; generation++; vosk.start(tag, listener); return
                }
                AppLog.d("ASR", "route $route final error $error")
                listener.onError(error)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            if (route != 1) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineNow)
            // Two languages at once (for example English and Hindi) works with the online recogniser.
            val more = (extras + tag0).filter { !it.equals(tag, true) }.distinct()
            // Google's recogniser ignores EXTRA_LANGUAGE (and answers in the phone's language) unless this extra is present, even empty.
            // The real key has "EXTRA_" twice; the shorter spelling is sent too in case a version reads that one.
            val extra = if (offlineNow) emptyList() else more
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", extra.toTypedArray())
            putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", ArrayList(extra))
            // Android 14+: let the recogniser switch between the languages mid-sentence (ignored if the recogniser doesn't support it).
            if (!offlineNow && route != 1 && more.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 34) {
                val all = ArrayList(listOf(tag) + more)
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", all)
            }
            AppLog.d("ASR", "intent lang=$tag extra=$extra offline=$offlineNow route=$route")
        }
        if (runCatching { r.startListening(intent) }.isFailure) next(SpeechRecognizer.ERROR_CLIENT)
    }

    /** Stop capturing and deliver whatever was heard so far. */
    fun stopListening() {
        if (usingDevice) { vosk.finish(); return }
        Cue.hushThenRelease(context, 1500)
        current?.stopListening()
    }

    /** Stop listening and ignore anything still on its way. The recognizer is kept for the next turn. */
    fun cancel() {
        generation++
        if (usingDevice) { vosk.cancel(); return }
        Cue.hushThenRelease(context, 1500)
        current?.let { runCatching { it.cancel() } }
    }

    /** Tear the recognizer down completely (screen closed, or the service dropped). */
    fun release() {
        generation++
        if (usingDevice) vosk.cancel()
        Cue.hushThenRelease(context, 1500)
        recs.values.forEach { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recs.clear()
        current = null
    }
}
