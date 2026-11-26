package com.lumenlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lumenlab.app.camera.AppSettings
import com.lumenlab.app.camera.AppState
import com.lumenlab.app.camera.CameraDetector
import com.lumenlab.app.camera.CrashLog
import com.lumenlab.app.camera.ProfileStore
import com.lumenlab.app.camera.SettingsStore
import com.lumenlab.app.camera.appVersion
import com.lumenlab.app.camera.buildProfile
import com.lumenlab.app.camera.controlLimits
import com.lumenlab.app.camera.deviceName
import com.lumenlab.app.ui.AstroScreen
import com.lumenlab.app.ui.AstroTheme
import com.lumenlab.app.ui.DoubleExposureScreen
import com.lumenlab.app.ui.LiveTestScreen
import com.lumenlab.app.ui.Mode
import com.lumenlab.app.ui.FocusStackScreen
import com.lumenlab.app.ui.HdrScreen
import com.lumenlab.app.ui.MoonScreen
import com.lumenlab.app.ui.NightScreen
import com.lumenlab.app.ui.PaintScreen
import com.lumenlab.app.ui.GalleryScreen
import com.lumenlab.app.ui.ModeTabs
import com.lumenlab.app.ui.MoreScreen
import com.lumenlab.app.ui.NightTheme
import com.lumenlab.app.ui.PanoramaScreen
import com.lumenlab.app.ui.SuperResScreen
import com.lumenlab.app.ui.PlannerScreen
import com.lumenlab.app.ui.ProbeScreen
import com.lumenlab.app.ui.RawScreen
import com.lumenlab.app.ui.SettingsScreen
import com.lumenlab.app.ui.SkyScreen
import com.lumenlab.app.ui.StackScreen
import com.lumenlab.app.ui.TesterScreen

private enum class Screen { Camera, Gallery, Stack, Tester, Planner, Sky, Settings, Probe, Raw, LiveTest }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        enableEdgeToEdge()
        setContent { LumenlabApp() }
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
    }
}

@Composable
private fun LumenlabApp() {
    val context = LocalContext.current
    val cameras = remember { CameraDetector.detect(context) }
    var limits by remember { mutableStateOf(ProfileStore.load(context)) }
    var rawResults by remember { mutableStateOf(ProfileStore.loadRaw(context)) }
    var liveResult by remember { mutableStateOf(ProfileStore.loadLive(context)) }
    val profile = remember(cameras, limits, rawResults, liveResult) {
        buildProfile(deviceName(), appVersion(context), cameras, limits, rawResults, liveResult)
    }
    val limitsForControls = remember(cameras, profile) { controlLimits(cameras, profile) }

    // Settings are saved as soon as they change, so they survive closing the app.
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    val updateSettings: (AppSettings) -> Unit = {
        settings = it
        SettingsStore.save(context, it)
    }
    // The app opens on the camera, in NORMAL. The tabs switch modes without leaving the screen.
    var screen by rememberSaveable { mutableStateOf(Screen.Camera) }
    var mode by rememberSaveable { mutableStateOf(Mode.Normal) }

    AstroTheme(if (settings.redMode) NightTheme.Red else NightTheme.Dark) {
        BackHandler(enabled = screen != Screen.Camera) {
            screen = when (screen) {
                Screen.Probe, Screen.Raw, Screen.LiveTest -> Screen.Settings
                else -> Screen.Camera
            }
        }
        when (screen) {
            Screen.Camera -> {
                // The lambda is named (onMode) because ModeTabs has an optional parameter after it.
                val tabs: @Composable () -> Unit = { ModeTabs(mode = mode, onMode = { mode = it }) }
                when (mode) {
                    Mode.More -> MoreScreen(
                        cameras = cameras,
                        profile = profile,
                        settings = settings,
                        onOpenGallery = { screen = Screen.Gallery },
                        onOpenStack = { screen = Screen.Stack },
                        onOpenTester = { screen = Screen.Tester },
                        onOpenPlanner = { screen = Screen.Planner },
                        onOpenSky = { screen = Screen.Sky },
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.DoubleExp -> DoubleExposureScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Focus -> FocusStackScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Hdr -> HdrScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Night -> NightScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Moon -> MoonScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Paint -> PaintScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Super -> SuperResScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    Mode.Pano -> PanoramaScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                    else -> AstroScreen(
                        limits = limitsForControls,
                        settings = settings,
                        onSettings = updateSettings,
                        mode = mode,
                        onOpenSettings = { screen = Screen.Settings },
                        tabs = tabs,
                    )
                }
            }
            Screen.Gallery -> GalleryScreen(onBack = { screen = Screen.Camera })
            Screen.Stack -> StackScreen(settings = settings, onBack = { screen = Screen.Camera })
            Screen.Planner -> PlannerScreen(
                limits = limitsForControls,
                settings = settings,
                onSettings = updateSettings,
                onBack = { screen = Screen.Camera },
            )
            Screen.Sky -> SkyScreen(onBack = { screen = Screen.Camera })
            Screen.Tester -> TesterScreen(cameras = cameras, profile = profile, onBack = { screen = Screen.Camera })
            Screen.Settings -> SettingsScreen(
                settings = settings,
                onSettings = updateSettings,
                profile = profile,
                onResetProfile = {
                    limits = ProfileStore.reset(context)
                    rawResults = emptyMap()
                    liveResult = null
                },
                onOpenProbe = { screen = Screen.Probe },
                onOpenRaw = { screen = Screen.Raw },
                onOpenLive = { screen = Screen.LiveTest },
                onBack = { screen = Screen.Camera },
            )
            Screen.Probe -> ProbeScreen(
                cameras = cameras,
                onBack = { screen = Screen.Settings },
                onSaved = { limits = it },
            )
            Screen.Raw -> RawScreen(
                cameras = cameras,
                onBack = { screen = Screen.Settings },
                onSaved = { rawResults = it },
            )
            Screen.LiveTest -> LiveTestScreen(
                limits = limitsForControls,
                onBack = { screen = Screen.Settings },
                onSaved = { liveResult = it },
            )
        }
    }
}
