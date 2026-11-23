package com.demon.nothingassistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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

    private var recognizer: SpeechRecognizer? = null
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
                runCatching { recognizer?.cancel() }
                vosk.start(tag, listener)
                return
            }
            if (!systemOk || choice != "auto") { usingDevice = false; listener.onError(ERROR_NOMODEL); return }
        }
        usingDevice = false
        if (!systemOk) { listener.onError(ERROR_UNAVAILABLE); return }
        val gen = ++generation
        Cue.hushSystemBeep(context, 20_000) // muted for the whole listening turn, so neither the start nor the stop ding is heard
        val r = recognizer ?: (realService()?.let { SpeechRecognizer.createSpeechRecognizer(context, it) } ?: SpeechRecognizer.createSpeechRecognizer(context)).also { recognizer = it }
        runCatching { r.cancel() }
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = gen == generation

            override fun onReadyForSpeech(params: Bundle?) {}
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
                listener.onError(error)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineNow)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            // Two languages at once (for example English and Hindi) works with the online recogniser.
            val more = (extras + tag0).filter { !it.equals(tag, true) }.distinct()
            if (!offlineNow && more.isNotEmpty()) putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", ArrayList(more))
        }
        r.startListening(intent)
    }

    /** Stop capturing and deliver whatever was heard so far. */
    fun stopListening() {
        if (usingDevice) { vosk.finish(); return }
        Cue.hushThenRelease(context, 1500)
        recognizer?.stopListening()
    }

    /** Stop listening and ignore anything still on its way. The recognizer is kept for the next turn. */
    fun cancel() {
        generation++
        if (usingDevice) { vosk.cancel(); return }
        Cue.hushThenRelease(context, 1500)
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** Tear the recognizer down completely (screen closed, or the service dropped). */
    fun release() {
        generation++
        if (usingDevice) vosk.cancel()
        Cue.hushThenRelease(context, 1500)
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}
