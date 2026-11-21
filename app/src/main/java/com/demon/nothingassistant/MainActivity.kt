package com.demon.nothingassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demon.nothingassistant.core.AssistantState
import com.demon.nothingassistant.core.AssistantController
import com.demon.nothingassistant.data.Settings
import com.demon.nothingassistant.data.ThemeMode
import com.demon.nothingassistant.service.OverlayService
import com.demon.nothingassistant.ui.AccessScreen
import com.demon.nothingassistant.ui.AiScreen
import com.demon.nothingassistant.ui.AiVoiceScreen
import com.demon.nothingassistant.ui.AssistantScreen
import com.demon.nothingassistant.ui.DarkPalette
import com.demon.nothingassistant.ui.LightPalette
import com.demon.nothingassistant.ui.AppearanceScreen
import com.demon.nothingassistant.ui.withAccent
import com.demon.nothingassistant.ui.LocalUiStyle
import com.demon.nothingassistant.ui.UiStyle
import com.demon.nothingassistant.ui.LogScreen
import com.demon.nothingassistant.ui.PersonaScreen
import com.demon.nothingassistant.ui.VoiceScreen
import com.demon.nothingassistant.ui.SettingsScreen
import com.demon.nothingassistant.ui.rememberPerfMode

class MainActivity : ComponentActivity() {

    private val controller: AssistantController get() = (application as NothingApp).controller
    private val settings: Settings get() = (application as NothingApp).settings

    /** Set when the bubble or the assist gesture launched us: start listening as soon as the mic is allowed. */
    private var autoStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        autoStart = shouldAutoStart(intent)

        setContent { androidx.compose.runtime.CompositionLocalProvider(com.demon.nothingassistant.ui.LocalUiStyle provides com.demon.nothingassistant.ui.UiStyle(settings.glassStyle, settings.blob, settings.typing)) { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldAutoStart(intent)) autoStart = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    companion object {
        /** True while the full app is on screen. The small panel uses this to avoid opening on top of it. */
        @Volatile
        var resumed = false
            private set
    }

    override fun onStart() {
        super.onStart()
        // The bubble is a foreground service; bring it back if the user wants it and the app was restarted.
        if (settings.bubble && android.provider.Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.onUiHidden()
    }

    private fun shouldAutoStart(i: Intent?): Boolean =
        i?.getBooleanExtra(OverlayService.EXTRA_AUTO_START, false) == true || i?.action == Intent.ACTION_ASSIST

    @Composable
    private fun Root() {
        val ui by controller.ui.collectAsStateWithLifecycle()
        var showSettings by remember { mutableStateOf(false) }
        var showPersona by remember { mutableStateOf(false) }
        var showVoice by remember { mutableStateOf(false) }
        var showAi by remember { mutableStateOf(false) }
        var showAiVoice by remember { mutableStateOf(false) }
        var showAccess by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var showLive by remember { mutableStateOf(false) }
        var showAppearance by remember { mutableStateOf(false) }

        val dark = when (settings.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        val palette = (if (dark) DarkPalette else LightPalette).withAccent(settings.accent)
        val perf = rememberPerfMode(settings)

        // Keep the screen awake only while the assistant is actively working.
        LaunchedEffect(ui.state) {
            val busy = ui.state != AssistantState.IDLE
            if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            controller.startListening() // starts if granted, otherwise shows the permission message
        }
        val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val anyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

        // First launch: ask for notifications (needed to show the bubble service) once.
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        fun startOrAsk() {
            if (controller.hasMicPermission()) controller.startListening()
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        LaunchedEffect(autoStart) {
            if (autoStart) {
                autoStart = false
                showSettings = false
                if (ui.state == AssistantState.IDLE || ui.state == AssistantState.SUCCESS || ui.state == AssistantState.FAILURE) {
                    startOrAsk()
                }
            }
        }

        // Contacts permission is requested lazily, the first time a command fails because of it.
        LaunchedEffect(ui.status) {
            if (ui.state == AssistantState.FAILURE) {
                when {
                    ui.status.startsWith("Allow Contacts") -> contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                    ui.status.startsWith("Allow Phone") -> contactsLauncher.launch(Manifest.permission.CALL_PHONE)
                    ui.status.startsWith("Allow Photos") -> contactsLauncher.launch(
                        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }

        BackHandler(enabled = showLive || showAppearance || showLog || showAi || showAiVoice || showAccess || showSettings || showPersona || showVoice || ui.state != AssistantState.IDLE) {
            when {
                showLive -> { controller.stopLive(); showLive = false }
                showAppearance -> showAppearance = false
                showLog -> showLog = false
                showVoice -> showVoice = false
                showAccess -> showAccess = false
                showPersona -> showPersona = false
                showAiVoice -> showAiVoice = false
                showAi -> showAi = false
                showSettings -> showSettings = false
                else -> controller.cancel()
            }
        }

        com.demon.nothingassistant.ui.GlassRoot(palette) {
        if (showLive) {
            com.demon.nothingassistant.ui.LiveScreen(
                controller = controller, ui = ui, settings = settings, palette = palette,
                onClose = { controller.stopLive(); showLive = false }
            )
        } else if (showLog) {
            LogScreen(palette = palette, onClose = { showLog = false })
        } else if (showAppearance) {
            AppearanceScreen(settings = settings, palette = palette, onClose = { showAppearance = false })
        } else if (showAccess) {
            AccessScreen(
                system = controller.actions.system,
                settings = settings,
                palette = palette,
                onOpenAppPermissions = { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                onRequestPermission = { anyPermLauncher.launch(it) },
                onOpenIntent = { runCatching { if (it.action == "android.app.role.action.REQUEST_ROLE") @Suppress("DEPRECATION") startActivityForResult(it, 901) else startActivity(it) } },
                onSetAssistant = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val r = controller.actions.setDefaultAssistant()
                        val m = when (r) { is com.demon.nothingassistant.action.ActionResult.Success -> r.message; is com.demon.nothingassistant.action.ActionResult.Failure -> r.message; else -> "" }
                        android.widget.Toast.makeText(this@MainActivity, m, android.widget.Toast.LENGTH_LONG).show()
                        if (!com.demon.nothingassistant.access.NothingAccess.isEnabled(this@MainActivity) || r is com.demon.nothingassistant.action.ActionResult.Success) startActivity(android.content.Intent(this@MainActivity, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                },
                onClose = { showAccess = false }
            )
        } else if (showPersona) {
            PersonaScreen(
                settings = settings,
                palette = palette,
                onTestAi = controller::testAi,
                onClose = { showPersona = false }
            )
        } else if (showAiVoice) {
            AiVoiceScreen(
                settings = settings,
                palette = palette,
                onTestVoice = controller::testVoiceProvider,
                onFetchModels = controller::fetchVoiceModels,
                onFetchVoices = controller::fetchVoiceVoices,
                onOpenChatTab = { showAiVoice = false; showAi = true },
                onClose = { showAiVoice = false }
            )
        } else if (showAi) {
            AiScreen(
                settings = settings,
                palette = palette,
                onTestProvider = controller::testProvider,
                onFetchModels = controller::fetchModels,
                alwaysThinks = controller::modelAlwaysThinks,
                onOpenPersona = { showPersona = true },
                onOpenVoiceTab = { showAi = false; showAiVoice = true },
                onClose = { showAi = false }
            )
        } else if (showVoice) {
            VoiceScreen(
                settings = settings,
                voice = controller.voice,
                palette = palette,
                onOpenLog = { showLog = true },
                onClose = { showVoice = false }
            )
        } else if (showSettings) {
            SettingsScreen(
                settings = settings,
                palette = palette,
                perf = perf,
                onBubbleToggle = { on ->
                    if (on) {
                        if (android.provider.Settings.canDrawOverlays(this)) {
                            settings.setBubble(true)
                            OverlayService.start(this)
                        } else {
                            startActivity(
                                Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    } else {
                        settings.setBubble(false)
                        OverlayService.stop(this)
                    }
                },
                onOpenAppPermissions = {
                    startActivity(
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                },
                onOpenAi = { showAi = true },
                onOpenAccess = { showAccess = true },
                onOpenVoice = { showVoice = true },
                onOpenLive = {
                    if (controller.hasMicPermission()) { showSettings = false; showLive = true; controller.startLive() }
                    else anyPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onOpenAppearance = { showAppearance = true },
                onOpenLog = { showLog = true },
                onClose = { showSettings = false }
            )
        } else {
            AssistantScreen(
                ui = ui,
                amplitude = controller.amplitude,
                palette = palette,
                perf = perf,
                onGlyphTap = {
                    when (ui.state) {
                        AssistantState.IDLE, AssistantState.SUCCESS, AssistantState.FAILURE -> startOrAsk()
                        else -> controller.onGlyphTap()
                    }
                },
                onCancel = controller::cancel,
                onConfirm = controller::confirmPending,
                onDismissConfirm = controller::dismissPending,
                onOpenSettings = { showSettings = true },
                onOpenLive = {
                    if (controller.hasMicPermission()) { showLive = true; controller.startLive() }
                    else anyPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            )
        }
        }
    }
}
