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

    private val sherpa get() = SherpaAsr.get(context)
    private var usingSherpa = false

    /** Which recogniser a turn uses: "auto" (Google/system if the ROM has one, else on-device), "system", "live", "accurate" or "twopass". */
    private fun pickSherpa(choice: String, systemOk: Boolean): SherpaAsr.Mode? {
        val sh = sherpa
        return when (choice) {
            "system" -> null
            "live" -> if (sh.ready(SherpaAsr.Mode.LIVE)) SherpaAsr.Mode.LIVE else null
            "accurate" -> if (sh.ready(SherpaAsr.Mode.ACCURATE)) SherpaAsr.Mode.ACCURATE else null
            "twopass" -> when {
                sh.ready(SherpaAsr.Mode.TWOPASS) && sh.installed(AsrModel.ACCURATE) -> SherpaAsr.Mode.TWOPASS
                sh.ready(SherpaAsr.Mode.LIVE) -> SherpaAsr.Mode.LIVE
                else -> null
            }
            else -> if (systemOk) null else sh.bestAvailable()
        }
    }

    fun start(languageTag: String, preferOffline: Boolean, choice: String = "auto") {
        val systemOk = SpeechRecognizer.isRecognitionAvailable(context) || realService() != null
        val mode = pickSherpa(choice, systemOk)
        if (mode != null) {
            usingSherpa = true
            generation++
            runCatching { recognizer?.cancel() }
            sherpa.start(mode, listener)
            return
        }
        usingSherpa = false
        if (!systemOk) {
            if (choice != "system" && sherpa.libraryPresent) {
                if (choice == "auto" || choice == "live" || choice == "twopass") sherpa.download(AsrModel.LIVE)
                if (choice == "accurate") sherpa.download(AsrModel.ACCURATE)
            }
            listener.onError(if (choice == "system" || !sherpa.libraryPresent) ERROR_UNAVAILABLE else ERROR_NOMODEL)
            return
        }
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
                if (live()) { Cue.hushThenRelease(context, 1500); listener.onError(error) }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        r.startListening(intent)
    }

    /** Stop capturing and deliver whatever was heard so far. */
    fun stopListening() {
        if (usingSherpa) { sherpa.finish(); return }
        Cue.hushThenRelease(context, 1500)
        recognizer?.stopListening()
    }

    /** Stop listening and ignore anything still on its way. The recognizer is kept for the next turn. */
    fun cancel() {
        generation++
        if (usingSherpa) { sherpa.cancel(); return }
        Cue.hushThenRelease(context, 1500)
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** Tear the recognizer down completely (screen closed, or the service dropped). */
    fun release() {
        generation++
        if (usingSherpa) sherpa.cancel()
        Cue.hushThenRelease(context, 1500)
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}
