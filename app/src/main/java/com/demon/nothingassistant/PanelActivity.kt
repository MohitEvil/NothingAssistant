package com.demon.nothingassistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demon.nothingassistant.core.AssistantController
import com.demon.nothingassistant.core.AssistantState
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.data.ThemeMode
import com.demon.nothingassistant.ui.DarkPalette
import com.demon.nothingassistant.ui.LightPalette
import com.demon.nothingassistant.ui.withAccent
import com.demon.nothingassistant.ui.PanelScreen
import com.demon.nothingassistant.ui.rememberPerfMode

/**
 * The small bubble panel. It is a transparent activity, so the app you were using stays visible behind it.
 * It starts listening right away and closes itself when the conversation ends.
 */
class PanelActivity : ComponentActivity() {

    private val controller: AssistantController get() = (application as NothingApp).controller
    private val settings: Settings get() = (application as NothingApp).settings

    private var autoStart by mutableStateOf(true)

    /** True while we are handing the conversation to the full app, so leaving doesn't cancel it. */
    private var handOff = false

    companion object {
        @Volatile
        var current: PanelActivity? = null
            private set

        /** True only while the panel is really on screen (not merely alive behind another app). */
        @Volatile
        var visible = false
            private set

        /** The floating bubble was tapped while the panel is open: stop listening and close it. Returns true if it closed one. */
        /** Closes the panel so the app underneath gets focus (used before tapping or going back in another app). */
        fun hide(): Boolean {
            val p = current ?: return false
            if (p.isFinishing || p.isDestroyed) return false
            com.demon.nothingassistant.service.WindowAnchor.hold(p.applicationContext, 90_000L) // so the panel can come back over the other app
            p.runOnUiThread { p.finish() }
            return true
        }

        /** Brings the panel back over whatever app is in front, already listening-ready (used between tasks). */
        fun show(ctx: android.content.Context) {
            val app = ctx.applicationContext
            com.demon.nothingassistant.service.WindowAnchor.hold(app, 60_000L)
            val i = Intent(app, PanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("quiet", true)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ launch(app, i) }, 160)
        }

        /** Starts the panel. If Android silently refuses a background start, a PendingIntent that opts in is sent as well. */
        fun launch(app: android.content.Context, i: Intent) {
            val ok = runCatching { app.startActivity(i) }.isSuccess
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!visible && !MainActivity.resumed) viaPending(app, i)
            }, if (ok) 900L else 0L)
        }

        private fun viaPending(app: android.content.Context, i: Intent) {
            runCatching {
                val pi = android.app.PendingIntent.getActivity(app, 7, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                if (Build.VERSION.SDK_INT >= 34) {
                    val o = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(app, 0, null, null, null, null, o.toBundle())
                } else pi.send()
            }
        }

        fun closeIfOpen(): Boolean {
            val p = current ?: return false
            if (p.isFinishing || p.isDestroyed) return false
            p.controller.cancel()
            p.finish()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (routeToMain()) return
        autoStart = !intent.getBooleanExtra("quiet", false)
        current = this
        enableEdgeToEdge()
        setContent { androidx.compose.runtime.CompositionLocalProvider(com.demon.nothingassistant.ui.LocalUiStyle provides com.demon.nothingassistant.ui.UiStyle(settings.glassStyle, settings.blob, settings.typing)) { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (routeToMain()) return
        autoStart = !intent.getBooleanExtra("quiet", false)
    }

    /** If the full app is already on screen, listen there instead of stacking the panel on top of it. */
    private fun routeToMain(): Boolean {
        if (!MainActivity.resumed) return false
        handOff = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onDestroy() {
        if (current === this) { current = null; visible = false }
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        if (!handOff) controller.onUiHidden()
    }

    @Composable
    private fun Root() {
        val ui by controller.ui.collectAsStateWithLifecycle()
        val dark = when (settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        val palette = (if (dark) DarkPalette else LightPalette).withAccent(settings.accent)
        val perf = rememberPerfMode(settings)
        var seenActive by remember { mutableStateOf(false) }

        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            controller.startListening() // starts if granted, otherwise shows the permission message
        }

        fun startOrAsk() {
            if (controller.hasMicPermission()) controller.startListening()
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        LaunchedEffect(autoStart) {
            if (autoStart) {
                autoStart = false
                if (ui.state == AssistantState.IDLE || ui.state == AssistantState.SUCCESS || ui.state == AssistantState.FAILURE) {
                    startOrAsk()
                }
            }
        }

        // Close once the conversation is over (back to idle after having been active).
        LaunchedEffect(ui.state) {
            if (ui.state != AssistantState.IDLE) seenActive = true
            else if (seenActive) { kotlinx.coroutines.delay(900); finish() } // a short pause between tasks must not close the window under a listening assistant
        }

        BackHandler {
            controller.cancel()
            finish()
        }

        PanelScreen(
            ui = ui,
            amplitude = controller.amplitude,
            palette = palette,
            perf = perf,
            onTapLine = {
                when (ui.state) {
                    AssistantState.IDLE, AssistantState.SUCCESS, AssistantState.FAILURE -> startOrAsk()
                    else -> controller.onGlyphTap()
                }
            },
            onConfirm = controller::confirmPending,
            onDismissConfirm = controller::dismissPending,
            onExpand = {
                handOff = true
                startActivity(
                    Intent(this@PanelActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            },
            onClose = {
                controller.cancel()
                finish()
            }
        )
    }
}
