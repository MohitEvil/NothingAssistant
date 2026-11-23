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
        installedOffline(context) { list ->
            if (list.isNotEmpty()) { settings.setGoogleOffline(list); cache = list }
        }
    }

    /** Last known list of offline-installed Google languages, for the engine to decide quickly. */
    @Volatile var cache: List<String> = emptyList()

    fun hasOffline(tag: String): Boolean {
        val base = tag.substringBefore('-').lowercase()
        return cache.any { it.equals(tag, true) } || cache.any { it.substringBefore('-').lowercase() == base }
    }

    /** True when the phone has a working internet connection. */
    fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * Languages whose offline speech pack is downloaded in the phone's Google speech service (Android 13+). Three routes are asked and
     * their answers merged, because a ROM may answer on only one of them: the speech service itself, the on-device recogniser, and the default one.
     */
    @android.annotation.SuppressLint("NewApi")
    fun installedOffline(context: Context, done: (List<String>) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33) { done(emptyList()); return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val found = LinkedHashSet<String>()
        var pending = 0
        var finished = false
        fun finish() { if (!finished) { finished = true; done(found.toList().sorted()) } }
        fun one() { pending--; if (pending <= 0) finish() }
        fun check(make: () -> SpeechRecognizer?) {
            pending++
            val r = runCatching { make() }.getOrNull()
            if (r == null) { main.post { one() }; return }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val ok = runCatching {
                r.checkRecognitionSupport(intent, { it.run() }, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(support: android.speech.RecognitionSupport) {
                        main.post {
                            found += support.installedOnDeviceLanguages.map(::norm).filter { it.isNotBlank() }
                            runCatching { r.destroy() }; one()
                        }
                    }
                    override fun onError(error: Int) { main.post { runCatching { r.destroy() }; one() } }
                })
            }.isSuccess
            if (!ok) { runCatching { r.destroy() }; main.post { one() } }
        }
        check { service(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, android.content.ComponentName(it.packageName, it.name)) } }
        check { if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null }
        check { SpeechRecognizer.createSpeechRecognizer(context) }
        main.postDelayed({ finish() }, 6000)
    }

    /** "ru-RU" -> "Russian (Russia)" in the phone's language. */
    fun niceName(tag: String): String {
        val loc = Locale.forLanguageTag(tag.replace('_', '-'))
        val n = loc.getDisplayName(Locale.getDefault())
        return if (n.isNullOrBlank() || n.equals(tag, true)) tag else n
    }
}
