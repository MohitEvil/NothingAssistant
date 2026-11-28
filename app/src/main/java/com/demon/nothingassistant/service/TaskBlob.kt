package com.demon.nothingassistant.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.demon.nothingassistant.access.NothingAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small dot-matrix blob that stays on screen while the assistant works through other apps.
 * Tap it: stop the task. Press and hold it: choose DROP (stop) or CHANGE (stop and say something else). Drag it anywhere.
 * It lives in an accessibility overlay window of our own app, so the screen reader tools never see it, and it hides itself
 * for the instant a screenshot is taken and steps aside when a tap is about to land under it.
 */
object TaskBlob {
    private val main = Handler(Looper.getMainLooper())
    private var view: BlobView? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var appCtx: Context? = null

    /** Wired by the controller. */
    @Volatile var onStop: () -> Unit = {}
    /** Press and hold: the user wants to say something about the running task (drop it, change it, or nothing). */
    @Volatile var onHold: () -> Unit = {}

    val showing: Boolean get() = view != null

    fun show(ctx: Context, label: String) {
        main.post {
            appCtx = ctx.applicationContext
            view?.let { it.label = label; it.invalidate(); return@post }
            val svc = NothingAccess.instance
            val base: Context = svc ?: ctx.applicationContext
            val type = if (svc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else if (android.provider.Settings.canDrawOverlays(ctx)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else return@post
            val d = base.resources.displayMetrics.density
            val m = base.resources.displayMetrics
            val w = (64 * d).toInt()
            val p = WindowManager.LayoutParams(
                w, w, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = m.widthPixels - w - (10 * d).toInt()
            p.y = (m.heightPixels * 0.62f).toInt()
            val v = BlobView(base, d)
            v.label = label
            runCatching {
                val manager = base.getSystemService(WindowManager::class.java)
                manager.addView(v, p)
                wm = manager; lp = p; view = v
                v.begin()
            }
        }
    }

    fun update(label: String) { main.post { view?.let { it.label = label } } }

    fun hide() {
        main.post {
            val v = view ?: return@post
            view = null
            v.end()
            runCatching { wm?.removeView(v) }
        }
    }

    /** Runs [block] with the blob invisible (so it never appears in a screenshot or is read as text on screen). */
    suspend fun <T> hidden(block: suspend () -> T): T {
        val v = view ?: return block()
        withContext(Dispatchers.Main) { v.alpha = 0f }
        delay(70)
        try { return block() } finally { withContext(Dispatchers.Main) { v.alpha = 1f } }
    }

    /** If a tap or swipe is about to land where the blob is, move the blob to the other end of the screen. */
    fun dodge(x: Float, y: Float): Boolean {
        val v = view ?: return false
        val p = lp ?: return false
        val pad = 28 * v.d
        if (!(x >= p.x - pad && x <= p.x + v.width + pad && y >= p.y - pad && y <= p.y + v.height + pad)) return false
        val h = v.resources.displayMetrics.heightPixels
        main.post {
            p.y = if (y < h / 2f) (h * 0.78f).toInt() else (h * 0.14f).toInt()
            runCatching { wm?.updateViewLayout(v, p) }
        }
        return true
    }
    /** Called from gesture code on a worker thread: steps the blob aside and gives the window a moment to move. */
    fun clear(x: Float, y: Float) { if (dodge(x, y)) try { Thread.sleep(120) } catch (_: InterruptedException) { } }

    private class BlobView(ctx: Context, val d: Float) : View(ctx) {
        var label = ""
        private var running = false
        private var downAt = 0L
        private var sx = 0f; private var sy = 0f; private var startX = 0; private var startY = 0
        private var moved = false
        private var long = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5232B.toInt() }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f * d; typeface = com.demon.nothingassistant.ui.NFont.native; textAlign = Paint.Align.CENTER; letterSpacing = 0.12f }
        private val holdCheck = Runnable {
            if (!moved) { long = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onHold() }
        }
        private val tick = object : Runnable { override fun run() { if (running) { invalidate(); main.postDelayed(this, 40) } } }

        fun begin() { running = true; main.post(tick) }
        fun end() { running = false; main.removeCallbacks(tick); main.removeCallbacks(holdCheck) }

        override fun onDraw(c: Canvas) {
            val t = SystemClock.uptimeMillis()
            val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 2 * d
            val pulse = 0.5f + 0.5f * sin(t / 420.0).toFloat()
            c.drawCircle(cx, cy, r, fill)
            rim.alpha = (120 + 100 * pulse).toInt()
            c.drawCircle(cx, cy, r, rim)
            val n = 14
            val ring = r * 0.62f
            val spin = (t / 1600.0 * 2 * Math.PI).toFloat()
            for (i in 0 until n) {
                val a = spin + (2 * Math.PI * i / n).toFloat()
                val wave = 0.5f + 0.5f * sin(a * 1f + t / 300.0).toFloat()
                dot.alpha = (90 + 165 * wave).toInt()
                c.drawCircle(cx + cos(a) * ring, cy + sin(a) * ring, (1.3f + 1.2f * wave) * d, if (i == 0) red else dot)
            }
            dot.alpha = 255
            c.drawCircle(cx, cy, (2.4f + 1.2f * pulse) * d, dot)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = lp ?: return false
            val m = resources.displayMetrics
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis(); moved = false; long = false
                    sx = e.rawX; sy = e.rawY; startX = p.x; startY = p.y
                    main.postDelayed(holdCheck, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 14 * d || abs(dy) > 14 * d) { moved = true; main.removeCallbacks(holdCheck) }
                    if (moved) {
                        p.x = (startX + dx).toInt().coerceIn(0, m.widthPixels - width)
                        p.y = (startY + dy).toInt().coerceIn(0, m.heightPixels - height)
                        runCatching { wm?.updateViewLayout(this, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(holdCheck)
                    if (!moved && !long) onStop()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(holdCheck)
            }
            return true
        }
    }
}
