package com.demon.nothingassistant.action

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import com.demon.nothingassistant.ai.Summarizer
import com.demon.nothingassistant.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Things on the phone that are not screen taps: reading text messages, finding and deleting the latest screenshot. */
class DeviceActions(private val ctx: Context, private val privShell: suspend (String) -> Boolean) {

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun ago(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m minute${if (m == 1L) "" else "s"} ago"
            m < 1440 -> "${m / 60} hour${if (m / 60 == 1L) "" else "s"} ago"
            else -> "${m / 1440} day${if (m / 1440 == 1L) "" else "s"} ago"
        }
    }

    private fun nameOf(number: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return number
        return runCatching {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: number
    }

    private class Sms(val from: String, val body: String, val ts: Long)

    suspend fun readSms(count: Int, from: String?, summary: Boolean): ActionResult = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.READ_SMS)) return@withContext ActionResult.Failure("I need SMS access to read your messages. Open Nothing Assistant, go to Phone control access and grant SMS.")
        val out = ArrayList<Sms>()
        runCatching {
            val q = Bundle().apply {
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf("date"))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, if (from != null) 150 else count.coerceIn(1, 10))
            }
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), q, null)?.use { c ->
                while (c.moveToNext()) out.add(Sms(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)))
            }
        }.onFailure { AppLog.e("sms", it.message ?: "failed") }
        var list: List<Sms> = out.map { Sms(nameOf(it.from), it.body, it.ts) }
        if (from != null) {
            val f = from.lowercase()
            list = list.filter { it.from.lowercase().contains(f) }.take(count.coerceIn(1, 10))
            if (list.isEmpty()) return@withContext ActionResult.Failure("I don't see any messages from $from.")
        }
        if (list.isEmpty()) return@withContext ActionResult.Success("Your inbox is empty.")
        val body = { m: Sms -> if (summary || m.body.length > 280) Summarizer.summarize(m.body, 240, 2) else m.body.trim() }
        if (list.size == 1) {
            val m = list[0]
            return@withContext ActionResult.Success("Your latest message is from ${m.from}, ${ago(m.ts)}: ${body(m)}")
        }
        if (summary) return@withContext ActionResult.Success("${list.size} recent messages. " + Summarizer.gist(list.map { "${it.from}: ${it.body}" }, 300))
        ActionResult.Success("Your last ${list.size} messages. " + list.mapIndexed { i, m -> "${i + 1}. From ${m.from}, ${ago(m.ts)}: ${Summarizer.summarize(m.body, 110, 1)}" }.joinToString(" "))
    }

    private fun imagesPermission() = has(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)

    /** The newest screenshot in the gallery, taken in the last [maxAgeMin] minutes if given. */
    fun latestScreenshot(maxAgeMin: Int = 0): Pair<Uri, String>? {
        if (!imagesPermission()) return null
        return runCatching {
            val q = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?)")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Screenshot%", "%/Screenshots/%"))
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            }
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATA), q, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val added = c.getLong(1) * 1000
                if (maxAgeMin > 0 && System.currentTimeMillis() - added > maxAgeMin * 60_000L) return@use null
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) to c.getString(2).orEmpty()
            }
        }.getOrNull()
    }

    /** Called after a screenshot was taken: waits for it to land in the gallery and remembers it for "delete it". */
    suspend fun rememberNewScreenshot() {
        val before = Blackboard.screenshotUri
        repeat(8) {
            delay(500)
            latestScreenshot(3)?.let { (u, _) -> if (u.toString() != before) { Blackboard.setScreenshot(u.toString()); return } }
        }
        // Even if it can't be seen yet, "it" now means a screenshot.
        Blackboard.touch("screenshot")
    }

    suspend fun deleteScreenshot(): ActionResult = withContext(Dispatchers.IO) {
        if (!imagesPermission()) return@withContext ActionResult.Failure("I need Photos access to find your screenshots. Open Nothing Assistant, Phone control access, and grant Photos.")
        val remembered = Blackboard.recentScreenshot()?.let { Uri.parse(it) }
        val found = latestScreenshot()
        val uri = remembered ?: found?.first ?: return@withContext ActionResult.Failure("I couldn't find a screenshot in your gallery.")
        val path = found?.takeIf { it.first == uri }?.second.orEmpty()
        // 1. Root or Shizuku: delete directly.
        if (path.isNotEmpty() && privShell("rm -f '${path.replace("'", "")}'")) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            Blackboard.clearScreenshot()
            return@withContext ActionResult.Success("Deleted your latest screenshot.")
        }
        // 2. Our own file, or an app with delete rights.
        val direct = runCatching { ctx.contentResolver.delete(uri, null, null) }.getOrDefault(0)
        if (direct > 0) { Blackboard.clearScreenshot(); return@withContext ActionResult.Success("Deleted your latest screenshot.") }
        // 3. Android 11+: the system asks the user once to allow this one delete.
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(uri))
            val i = Intent(ctx, DeleteRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pi", pi)
            Blackboard.clearScreenshot()
            return@withContext ActionResult.Success("Android needs your OK to delete it. Tap Allow.", launch = { ctx.startActivity(i) })
        }
        ActionResult.Failure("I couldn't delete it. Android blocked the delete.")
    }
}

/** Shows Android's own "allow delete?" sheet for a media delete request, then closes. */
class DeleteRequestActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pi = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("pi", android.app.PendingIntent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra("pi")
        if (pi == null) { finish(); return }
        runCatching { startIntentSenderForResult(pi.intentSender, 7, null, 0, 0, 0) }.onFailure { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finish()
    }
}
