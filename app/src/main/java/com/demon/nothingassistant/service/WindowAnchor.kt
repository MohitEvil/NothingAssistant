package com.demon.nothingassistant.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A one-pixel invisible window. Android only lets an app open a screen from the background when it already has a window showing;
 * holding this while the assistant works (and while the wake word fires) means the small panel can always come back on top,
 * so the assistant never ends up listening with nothing on screen.
 */
object WindowAnchor {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val drop = Runnable { remove() }

    fun hold(ctx: Context, ms: Long = 20_000L) {
        val app = ctx.applicationContext
        main.post {
            try {
                if (!android.provider.Settings.canDrawOverlays(app)) return@post
                if (view == null) {
                    val v = View(app)
                    val lp = WindowManager.LayoutParams(
                        1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = Gravity.TOP or Gravity.START
                    app.getSystemService(WindowManager::class.java).addView(v, lp)
                    view = v
                }
                main.removeCallbacks(drop)
                main.postDelayed(drop, ms)
            } catch (_: Exception) { }
        }
    }

    /** For background threads only: puts the window up and waits a moment so the system sees it before an activity is started. */
    fun holdAndWait(ctx: Context, ms: Long = 20_000L) {
        hold(ctx, ms)
        try { Thread.sleep(220) } catch (_: InterruptedException) { }
    }

    fun release() { main.post { remove() } }

    private fun remove() {
        main.removeCallbacks(drop)
        val v = view ?: return
        view = null
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) { }
    }
}
