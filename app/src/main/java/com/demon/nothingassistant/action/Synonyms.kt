package com.demon.nothingassistant.action

/**
 * The many names phones and people use for the same setting. Works with no AI: when a label asked for isn't on screen,
 * every name in its group is tried, so "dark mode" finds "Dark theme" and "screen timeout" finds "Display timeout" or "Sleep".
 */
object Synonyms {
    private val groups: List<List<String>> = listOf(
        listOf("dark mode", "dark theme", "night mode", "night theme", "dark appearance", "force dark"),
        listOf("light mode", "light theme", "day mode"),
        listOf("screen timeout", "display timeout", "screen off time", "screen sleep", "auto lock", "turn off screen after", "screen off timeout", "lock after"),
        listOf("brightness", "screen brightness", "display brightness", "brightness level"),
        listOf("adaptive brightness", "auto brightness", "automatic brightness", "brightness auto"),
        listOf("auto rotate", "auto rotate screen", "rotation", "screen rotation", "rotate screen", "autorotate", "orientation"),
        listOf("wifi", "wi-fi", "wi fi", "wlan", "wireless", "use wi-fi"),
        listOf("bluetooth", "use bluetooth", "blue tooth"),
        listOf("mobile data", "cellular data", "mobile network", "data connection", "internet data", "sim data"),
        listOf("hotspot", "wi-fi hotspot", "portable hotspot", "tethering", "mobile hotspot", "personal hotspot"),
        listOf("airplane mode", "flight mode", "aeroplane mode", "aeroplane", "airplane"),
        listOf("do not disturb", "dnd", "dont disturb", "silent mode", "priority mode", "focus mode"),
        listOf("battery saver", "power saving", "power saver", "low power mode", "battery saving", "extreme battery saver"),
        listOf("location", "gps", "use location", "location services", "location access"),
        listOf("nfc", "near field communication", "contactless"),
        listOf("flashlight", "torch", "flash light"),
        listOf("font size", "text size", "font scale", "display size and text", "font and display size"),
        listOf("screen lock", "lock screen", "lockscreen", "device lock", "screen security"),
        listOf("notifications", "notification", "app notifications", "notification settings"),
        listOf("vibration", "vibrate", "haptics", "haptic feedback", "touch feedback", "vibration and haptics"),
        listOf("ringtone", "phone ringtone", "ring tone", "call ringtone"),
        listOf("wallpaper", "wallpapers", "wallpaper and style", "home screen wallpaper"),
        listOf("language", "languages", "language and input", "system language", "languages and input"),
        listOf("keyboard", "on-screen keyboard", "virtual keyboard", "input method", "keyboards"),
        listOf("accessibility", "accessibility settings", "ease of access"),
        listOf("storage", "device storage", "memory and storage", "internal storage", "storage and memory"),
        listOf("apps", "applications", "app list", "installed apps", "all apps", "manage apps", "apps and notifications"),
        listOf("permissions", "app permissions", "permission manager", "privacy permissions"),
        listOf("default apps", "default applications", "preferred apps", "choose default apps"),
        listOf("battery", "battery usage", "battery and performance", "battery health"),
        listOf("always on display", "aod", "ambient display", "always-on display", "always on screen"),
        listOf("developer options", "developer settings", "dev options", "developer mode"),
        listOf("usb debugging", "adb debugging", "android debugging", "usb debug"),
        listOf("about phone", "about device", "about this phone", "device info", "phone info"),
        listOf("software update", "system update", "system updates", "update software", "ota update", "check for updates"),
        listOf("date and time", "time and date", "date & time", "clock settings", "time settings", "set time"),
        listOf("screen recorder", "screen recording", "record screen", "screen record"),
        listOf("screenshot", "screen capture", "screen shot", "capture screen"),
        listOf("night light", "eye comfort", "blue light filter", "reading mode", "eye protection", "night shield", "comfort view"),
        listOf("refresh rate", "screen refresh rate", "smooth display", "high refresh rate", "display refresh rate", "fps"),
        listOf("face unlock", "face recognition", "face id", "face authentication"),
        listOf("fingerprint", "fingerprint unlock", "fingerprints", "finger print", "biometrics"),
        listOf("gestures", "gesture navigation", "system navigation", "navigation mode", "navigation gestures", "navigation bar"),
        listOf("data saver", "data saving", "data usage saver", "reduce data usage", "lite mode"),
        listOf("sync", "auto sync", "auto-sync data", "automatic sync", "sync data", "background sync"),
        listOf("clear cache", "delete cache", "wipe cache"),
        listOf("force stop", "stop app", "kill app", "force close"),
        listOf("uninstall", "remove app", "delete app")
    )

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val index: Map<String, List<String>> by lazy {
        val m = HashMap<String, List<String>>()
        for (g in groups) for (a in g) m[key(a)] = g
        m
    }

    /** The label itself plus every other name for it (original text, not squashed). Always starts with [label]. */
    fun variants(label: String): List<String> {
        val k = key(label)
        if (k.isEmpty()) return listOf(label)
        val g = index[k] ?: return listOf(label)
        return listOf(label) + g.filter { key(it) != k }
    }

    /** Variants of every label in the list, without repeats, originals first. */
    fun expand(labels: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        labels.forEach { out.add(it) }
        labels.forEach { l -> variants(l).forEach { out.add(it) } }
        return out.toList()
    }

    /** Best of [Targeting.score] over every name for the query (a small penalty for a swapped-in name, so the real words still win a tie). */
    fun bestScore(query: String, label: String, scorer: (String, String) -> Int): Int {
        var best = scorer(query, label)
        if (best >= 96) return best
        for (v in variants(query).drop(1)) best = maxOf(best, scorer(v, label) - 2)
        return best
    }
}
