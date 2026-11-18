package com.demon.nothingassistant.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.demon.nothingassistant.access.NothingAccess
import com.demon.nothingassistant.debug.AppLog
import com.demon.nothingassistant.intent.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Installs and updates apps through the Play Store screen, start to finish:
 * opens the right app page, checks what state it is in (not installed / update available / up to date / working),
 * presses only the Install or Update button that belongs to that app, then waits and reports until it is done.
 * Never taps anything that costs money.
 */
class PlayStore(
    private val app: Context,
    private val ui: UiControl,
    private val installed: (String) -> Pair<String, String>?,
    private val ensureAccess: suspend () -> Boolean,
    private val progress: (String) -> Unit,
    private val announce: (String) -> Unit
) {
    enum class Mode { INSTALL, UPDATE }
    private enum class St { NOT_INSTALLED, UPDATE, INSTALLED, WORKING, PAID, UNAVAILABLE, UNKNOWN }
    private class Page(val state: St, val button: NothingAccess.Cand?)

    private val dm get() = app.resources.displayMetrics
    private val store = "com.android.vending"

    private val known = mapOf(
        "telegram" to "org.telegram.messenger", "whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca", "snapchat" to "com.snapchat.android", "youtube" to "com.google.android.youtube", "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient", "twitter" to "com.twitter.android", "x" to "com.twitter.android", "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "google maps" to "com.google.android.apps.maps", "maps" to "com.google.android.apps.maps",
        "zomato" to "com.application.zomato", "swiggy" to "in.swiggy.android", "uber" to "com.ubercab", "ola" to "com.olacabs.customer",
        "paytm" to "net.one97.paytm", "phonepe" to "com.phonepe.app", "google pay" to "com.google.android.apps.nbu.paisa.user", "gpay" to "com.google.android.apps.nbu.paisa.user",
        "amazon" to "in.amazon.mShop.android.shopping", "flipkart" to "com.flipkart.android", "signal" to "org.thoughtcrime.securesms", "discord" to "com.discord",
        "reddit" to "com.reddit.frontpage", "linkedin" to "com.linkedin.android", "vlc" to "org.videolan.vlc", "truecaller" to "com.truecaller",
        "shazam" to "com.shazam.android", "pinterest" to "com.pinterest", "telegram x" to "org.thunderdog.challegram", "waze" to "com.waze",
        "google drive" to "com.google.android.apps.docs", "drive" to "com.google.android.apps.docs", "google photos" to "com.google.android.apps.photos",
        "photos" to "com.google.android.apps.photos", "meet" to "com.google.android.apps.tachyon", "zoom" to "us.zoom.videomeetings", "teams" to "com.microsoft.teams",
        "outlook" to "com.microsoft.office.outlook", "chatgpt" to "com.openai.chatgpt", "claude" to "com.anthropic.claude", "gemini" to "com.google.android.apps.bard",
        "brave" to "com.brave.browser", "firefox" to "org.mozilla.firefox", "duckduckgo" to "com.duckduckgo.mobile.android", "capcut" to "com.lemon.lvoverseas",
        "bgmi" to "com.pubg.imobile", "free fire" to "com.dts.freefireth", "jiocinema" to "com.jio.media.ondemand", "hotstar" to "in.startv.hotstar"
    )

    private fun pmHas(pkg: String?): Boolean = pkg != null && runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun norm(s: String) = NothingAccess.norm(s)
    private val price = Regex("^(?:₹|rs\\.?|inr|\\$)\\s?\\d", RegexOption.IGNORE_CASE)

    private fun readPage(svc: NothingAccess): Page {
        val h = dm.heightPixels
        val all = svc.candidates()
        val text = all.joinToString(" ") { it.label }.lowercase()
        val btn = all.filter { it.clickable && !it.editable && it.hit.centerY() < h * 0.64 }
        fun find(word: String) = btn.filter { norm(it.label) == word }.minByOrNull { it.hit.centerY() }
        val working = Regex("\\b(installing|downloading|updating|pending|waiting for download|\\d{1,3}\\s?%)").containsMatchIn(text)
        return when {
            Regex("isn.t available|not available for your device|can.t be installed|isn.t compatible|item not found|we couldn.t find that").containsMatchIn(text) -> Page(St.UNAVAILABLE, null)
            find("update") != null && !(find("cancel") != null && working) -> Page(St.UPDATE, find("update"))
            find("cancel") != null && working -> Page(St.WORKING, find("cancel"))
            find("install") != null -> Page(St.NOT_INSTALLED, find("install"))
            find("open") != null || find("uninstall") != null -> Page(St.INSTALLED, find("open"))
            btn.any { price.containsMatchIn(it.label.trim()) } -> Page(St.PAID, null)
            working -> Page(St.WORKING, null)
            else -> Page(St.UNKNOWN, null)
        }
    }

    private suspend fun waitFront(svc: NothingAccess, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (svc.frontPackage() == store) return true
            delay(150)
        }
        return false
    }

    private suspend fun waitPage(svc: NothingAccess, ms: Long): Page {
        val end = System.currentTimeMillis() + ms
        var p = readPage(svc)
        while (System.currentTimeMillis() < end && p.state == St.UNKNOWN) { delay(300); p = readPage(svc) }
        return p
    }

    /** From a search list, opens the page whose title is this app (never presses Install in the list: that is where wrong installs happen). */
    private suspend fun openFromResults(svc: NothingAccess, name: String): Boolean {
        val end = System.currentTimeMillis() + 7000
        var best: NothingAccess.Cand? = null
        while (System.currentTimeMillis() < end) {
            val c = svc.candidates().filter { !it.editable && it.label.isNotBlank() && !Regex("^(install|update|open|uninstall|get)$", RegexOption.IGNORE_CASE).matches(it.label.trim()) }
                .map { it to Targeting.score(name, it.label) }
            val top = c.filter { it.second >= 86 }.maxByOrNull { it.second * 10 - it.first.hit.centerY() / 100 }
            if (top != null) { best = top.first; break }
            delay(300)
        }
        val hit = best ?: return false
        svc.click(hit.node)
        delay(1300)
        return true
    }

    private suspend fun press(svc: NothingAccess, b: NothingAccess.Cand): Boolean {
        val before = readPage(svc).state
        svc.click(b.node)
        delay(900)
        if (readPage(svc).state != before) return true
        svc.tapAt(b.hit.exactCenterX(), b.hit.exactCenterY()) // the click didn't register: touch it
        delay(900)
        return readPage(svc).state != before
    }

    private fun percent(svc: NothingAccess): String? =
        svc.candidates().firstNotNullOfOrNull { Regex("\\b(\\d{1,3})\\s?%").find(it.label)?.groupValues?.get(1) }?.let { "$it%" }

    private suspend fun waitDone(svc: NothingAccess, label: String, update: Boolean, pkg: String?): ActionResult {
        announce(if (update) "It's updating." else "Installing $label.")
        val start = System.currentTimeMillis()
        val end = start + 240_000
        var worked = false
        var retries = 0
        var lastKick = start
        delay(1200)
        while (System.currentTimeMillis() < end) {
            if (svc.frontPackage() != store) { delay(1500); if (svc.frontPackage() != store && !update && pmHas(pkg)) return ActionResult.Success("Installed $label."); }
            val p = readPage(svc)
            AppLog.d("play", "wait state=${p.state}")
            when (p.state) {
                St.WORKING -> { worked = true; progress((if (update) "Updating " else "Installing ") + label + "…" + (percent(svc)?.let { " $it" } ?: "")) }
                St.INSTALLED -> if (worked || System.currentTimeMillis() - lastKick > 4500) return ActionResult.Success(if (update) "Updated $label. It's ready to open." else "Installed $label. It's ready to open.")
                St.NOT_INSTALLED, St.UPDATE -> {
                    if (!update && pmHas(pkg)) return ActionResult.Success("Installed $label.")
                    if (!worked && System.currentTimeMillis() - lastKick > 5000 && retries < 2) { retries++; lastKick = System.currentTimeMillis(); p.button?.let { press(svc, it) } }
                    else if (worked && System.currentTimeMillis() - lastKick > 8000) return ActionResult.Failure("The download of $label stopped. Check your connection and the Play Store.")
                }
                St.UNKNOWN -> svc.find(listOf("Skip", "Not now", "No thanks", "Maybe later", "Got it"))?.let { svc.click(it) }
                St.PAID -> return ActionResult.Failure("$label is a paid app, so I won't buy it.")
                St.UNAVAILABLE -> return ActionResult.Failure("$label isn't available for this phone on the Play Store.")
            }
            delay(1500)
        }
        return ActionResult.Failure("$label is still ${if (update) "updating" else "installing"}. I'll leave it; check the Play Store notification.")
    }

    suspend fun run(rawName: String, mode: Mode, approved: Boolean = false): ActionResult = withContext(Dispatchers.Default) {
        val name = rawName.trim().trim('"', '\'', '“', '”').ifEmpty { return@withContext ActionResult.Failure("Which app should I ${if (mode == Mode.UPDATE) "update" else "install"}?") }
        if (!ensureAccess()) return@withContext ui.needAccess(if (mode == Mode.UPDATE) "update apps" else "install apps")
        val svc = NothingAccess.instance ?: return@withContext ActionResult.Failure("Screen control just turned off. Turn it back on and ask again.")
        ui.clearWay(svc)
        val have = installed(name)
        val pkg = have?.first ?: known[name.lowercase()]
        val label = have?.second ?: name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val uri = if (pkg != null) Uri.parse("market://details?id=$pkg") else Uri.parse("market://search?q=${Uri.encode(name)}&c=apps")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(store).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { app.startActivity(intent) }.isFailure) return@withContext ActionResult.Failure("I couldn't open the Play Store.")
        if (!waitFront(svc, 8000)) return@withContext ActionResult.Failure("The Play Store didn't come to the front. Try again.")
        delay(900)
        if (pkg == null && !openFromResults(svc, name)) return@withContext ActionResult.Failure("I searched the Play Store but couldn't see an app called $name. Tell me which result to open.")
        val page = waitPage(svc, 9000)
        AppLog.d("play", "$name pkg=$pkg state=${page.state}")
        when (page.state) {
            St.UNAVAILABLE -> ActionResult.Failure("$label isn't available on the Play Store for this phone.")
            St.PAID -> ActionResult.Failure("$label is a paid app, so I won't buy it.")
            St.UNKNOWN -> ActionResult.Failure("I opened the Play Store page but couldn't read its buttons. Tell me what to tap.")
            St.WORKING -> waitDone(svc, label, update = have != null, pkg = pkg)
            St.INSTALLED ->
                if (mode == Mode.UPDATE) ActionResult.Success("$label is already up to date.") else ActionResult.Success("$label is already installed and up to date.")
            St.UPDATE ->
                if (mode == Mode.UPDATE || approved) {
                    val b = page.button ?: return@withContext ActionResult.Failure("I couldn't find the Update button.")
                    press(svc, b)
                    waitDone(svc, label, update = true, pkg = pkg)
                } else ActionResult.Confirm(
                    title = "Update $label?",
                    detail = "$label is already installed, but an update is available.",
                    spoken = "$label is already installed, but an update is available. Want me to update it?",
                    command = Command.UpdateApp(name, approved = true),
                    declineMessage = "Okay, I'll leave $label as it is.",
                    declineAsFailure = false
                )
            St.NOT_INSTALLED ->
                if (mode == Mode.UPDATE) ActionResult.Confirm(
                    title = "Install $label?",
                    detail = "$label isn't installed yet.",
                    spoken = "$label isn't installed. Want me to install it?",
                    command = Command.InstallApp(name),
                    declineMessage = "Okay, I won't install it.",
                    declineAsFailure = false
                ) else {
                    val b = page.button ?: return@withContext ActionResult.Failure("I couldn't find the Install button.")
                    press(svc, b)
                    waitDone(svc, label, update = false, pkg = pkg)
                }
        }
    }
}
