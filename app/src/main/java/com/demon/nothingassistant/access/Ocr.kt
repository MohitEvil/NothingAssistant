package com.demon.nothingassistant.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One line of text found in a screenshot, with where it is on screen. */
class OcrLine(val text: String, val box: Rect)

/**
 * Reads what is actually drawn on screen by taking a screenshot through the accessibility service and running Google's
 * on-device text recogniser over it. Nothing leaves the phone and the screenshot is dropped right after.
 */
object Ocr {
    suspend fun read(svc: AccessibilityService): List<OcrLine>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(8000) {
            // takeScreenshot is rate limited (about one every third of a second): retry once instead of giving up.
            val bmp = screenshot(svc) ?: run { kotlinx.coroutines.delay(420); screenshot(svc) } ?: return@withTimeoutOrNull null
            val dm = svc.resources.displayMetrics
            val sx = dm.widthPixels.toFloat() / bmp.width
            val sy = dm.heightPixels.toFloat() / bmp.height
            val lines = recognise(bmp) ?: return@withTimeoutOrNull null
            // Boxes are in screenshot pixels: convert to screen coordinates so taps land on the words.
            if (kotlin.math.abs(sx - 1f) < 0.01f && kotlin.math.abs(sy - 1f) < 0.01f) lines
            else lines.map { OcrLine(it.text, Rect((it.box.left * sx).toInt(), (it.box.top * sy).toInt(), (it.box.right * sx).toInt(), (it.box.bottom * sy).toInt())) }
        }
    }

    /** The current screen as a small JPEG (base64), to show to an AI that can see pictures. Null when a screenshot isn't possible. */
    suspend fun screenJpeg(svc: AccessibilityService, maxSide: Int = 1100): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(8000) {
            val bmp = screenshot(svc) ?: run { kotlinx.coroutines.delay(420); screenshot(svc) } ?: return@withTimeoutOrNull null
            val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (small !== bmp) small.recycle()
            bmp.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }

    /** The blob is hidden for the instant of the capture so it never covers (or gets read as) screen content. */
    private suspend fun screenshot(svc: AccessibilityService): Bitmap? = com.demon.nothingassistant.service.TaskBlob.hidden { screenshotRaw(svc) }

    private suspend fun screenshotRaw(svc: AccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val out = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        copy
                    }.getOrNull()
                    runCatching { result.hardwareBuffer.close() }
                    if (cont.isActive) cont.resume(out)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun recognise(bmp: Bitmap): List<OcrLine>? = suspendCancellableCoroutine { cont ->
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (b in text.textBlocks) for (l in b.lines) {
                    val box = l.boundingBox ?: continue
                    if (l.text.isNotBlank()) lines.add(OcrLine(l.text.trim(), box))
                }
                client.close()
                if (cont.isActive) cont.resume(lines.sortedBy { it.box.top })
            }
            .addOnFailureListener {
                client.close()
                if (cont.isActive) cont.resume(null)
            }
    }
}
