package com.demon.nothingassistant.audio

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.demon.nothingassistant.data.Settings
import java.util.Locale

/** Finds out which speech recogniser this phone has and which languages it speaks, and picks a sensible default. */
object SpeechInfo {
    /** A real recognition service from another app (ours is only a placeholder that lets the system list us as an assistant). */
    fun service(context: Context): android.content.pm.ServiceInfo? =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }.filter { it.packageName != context.packageName }
            .let { l -> l.firstOrNull { it.packageName.contains("googlequicksearchbox") } ?: l.firstOrNull() }

    fun available(context: Context): Boolean = service(context) != null || SpeechRecognizer.isRecognitionAvailable(context)

    fun label(context: Context): String? = service(context)?.let { runCatching { it.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault(it.packageName) }

    fun isGoogle(context: Context): Boolean = service(context)?.packageName?.contains("google", true) == true

    private fun norm(t: String) = t.replace('_', '-').trim()

    /** Asks the recogniser for its languages. [done] runs on the main thread with the list and the recogniser's own preference (maybe null). */
    fun languages(context: Context, done: (List<String>, String?) -> Unit) {
        val intent = runCatching { RecognizerIntent.getVoiceDetailsIntent(context) }.getOrNull() ?: Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val extras = getResultExtras(false)
                val list = extras?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES).orEmpty().map(::norm).filter { it.isNotBlank() }.distinct()
                val pref = extras?.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)?.let(::norm)
                done(list, pref)
            }
        }
        val ok = runCatching {
            context.sendOrderedBroadcast(intent, null, receiver, null, Activity.RESULT_OK, null, null)
        }.isSuccess
        if (!ok) done(emptyList(), null)
    }

    /** en-US if the recogniser has it, else the phone's language, else its own preference, else the first it lists. Never overrides a choice the user made. */
    fun pickDefault(langs: List<String>, pref: String?): String? {
        if (langs.isEmpty()) return null
        fun find(t: String) = langs.firstOrNull { it.equals(t, true) }
        val sys = Locale.getDefault()
        return find("en-US") ?: find(sys.toLanguageTag()) ?: langs.firstOrNull { it.startsWith(sys.language + "-", true) } ?: pref?.let { find(it) } ?: langs.first()
    }

    /** Detect and apply. Safe to call as often as you like. */
    fun autoConfigure(context: Context, settings: Settings) {
        languages(context) { langs, pref ->
            if (langs.isNotEmpty()) settings.setSupportedLangs(langs)
            if (!settings.langChosen) pickDefault(langs, pref)?.let { settings.setLanguageAuto(it) }
        }
    }

    /** Languages whose offline speech pack is actually downloaded on this phone (Android 13+). Empty when unknown. */
    @android.annotation.SuppressLint("NewApi")
    fun installedOffline(context: Context, done: (List<String>) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33) { done(emptyList()); return }
        runCatching {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) { done(emptyList()); return }
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            r.checkRecognitionSupport(intent, { it.run() }, object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(support: android.speech.RecognitionSupport) {
                    val l = support.installedOnDeviceLanguages.map(::norm).filter { it.isNotBlank() }.distinct()
                    main.post { runCatching { r.destroy() }; done(l) }
                }
                override fun onError(error: Int) { main.post { runCatching { r.destroy() }; done(emptyList()) } }
            })
        }.onFailure { done(emptyList()) }
    }

    /** "ru-RU" -> "Russian (Russia)" in the phone's language. */
    fun niceName(tag: String): String {
        val loc = Locale.forLanguageTag(tag.replace('_', '-'))
        val n = loc.getDisplayName(Locale.getDefault())
        return if (n.isNullOrBlank() || n.equals(tag, true)) tag else n
    }
}
