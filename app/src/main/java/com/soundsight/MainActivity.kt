package com.soundsight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.soundsight.core.ContrastBoostMode
import com.soundsight.light.LightSensor
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.soundsight.audio.ListenerService
import com.soundsight.audio.motion.MotionSensor
import com.soundsight.audio.MicSource
import com.soundsight.audio.Sensitivity
import com.soundsight.core.AlertConfig
import com.soundsight.core.AppLanguage
import com.soundsight.core.DailyQuotaGuard
import com.soundsight.core.EnStrings
import com.soundsight.core.LocalStrings
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.core.Telemetry
import com.soundsight.core.ViStrings
import com.soundsight.data.ClassificationDebugResult
import com.soundsight.data.ClassificationResult
import com.soundsight.data.GeminiConfig
import com.soundsight.data.live.LiveSession
import com.soundsight.data.SoundClassifier
import com.soundsight.ui.Effect
import com.soundsight.ui.Haptics
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.debug.StereoProbeScreen
import androidx.compose.runtime.produceState
import com.soundsight.core.EmergencyProfile
import com.soundsight.core.EmergencyStore
import com.soundsight.ui.emergency.CrashResponseScreen
import com.soundsight.ui.emergency.EmergencyProfileScreen
import com.soundsight.ui.hud.HudScreen
import com.soundsight.ui.onboarding.DirectionCalibrationScreen
import com.soundsight.ui.onboarding.OnboardingRoutes
import com.soundsight.ui.onboarding.OnboardingViewModel
import com.soundsight.ui.onboarding.onboardingNavGraph
import com.soundsight.ui.settings.CollisionDetectionScreen
import com.soundsight.ui.settings.DirectionArrowScreen
import com.soundsight.ui.settings.SettingsScreen
import com.soundsight.ui.splash.SplashScreen
import com.soundsight.audio.dsp.StereoAnalyzer
import com.soundsight.ui.theme.AccentLive
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import com.soundsight.ui.theme.AlertLabel
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.PulsePathTheme
import com.soundsight.ui.theme.ThemeMode
import com.soundsight.ui.theme.WarningBg
import java.util.Locale

private const val TAG = "MainActivity"

/**
 * Upper bound on how long the splash may gate routing while the onboarding flag is being read.
 * The read itself takes single-digit milliseconds on a warm store; this exists only so that a
 * store which never answers cannot leave the rider on a splash forever.
 */
private const val SPLASH_GATE_TIMEOUT_MS = 4_000L

class MainActivity : ComponentActivity() {
    private val viewModel: HudViewModel by viewModels()
    private val onboardingViewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) {
            dumpDynamicPalettes()
        }
        setContent {
            val isRunning by ListenerService.isRunning.collectAsStateWithLifecycle()
            LaunchedEffect(isRunning) {
                if (isRunning) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val context = LocalContext.current
            val haptics = remember(context) { Haptics(context) }

            // Resolves the start destination (and preloads the two AlertConfig values that used
            // to be read with runBlocking in onCreate) asynchronously. While the preference read
            // is in flight nobody is routed anywhere: a rider must never flash through onboarding,
            // or land on the HUD, merely because persistence had not answered yet.
            // rememberSaveable, not remember. This activity declares no android:configChanges
            // and is resizeableActivity, so a rotation, a multi-window resize or "Don't keep
            // activities" destroys and recreates it. Under plain remember both flags reset,
            // showSplash recomputed true, and the rider watched the splash replay on every
            // return - and the HUD route then composed two seconds later, by which time they
            // may well have left the app again. See the "hud" route for why that was fatal.
            var hasCompletedOnboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
            var isSplashFinished by rememberSaveable { mutableStateOf(false) }

            // The routing read gets an effect to itself. It used to run third, behind two
            // AlertConfig preloads, so one slow or hung preference read was enough to leave
            // hasCompletedOnboarding null - and a null pins showSplash true with nothing to
            // release it.
            LaunchedEffect(Unit) {
                if (hasCompletedOnboarding != null) return@LaunchedEffect
                hasCompletedOnboarding = try {
                    OnboardingStore.hasCompletedOnboardingFlow(context).first()
                } catch (e: Exception) {
                    // Logged, not swallowed: a store that cannot answer is exactly the state
                    // the old blanket catch hid, and it has to be visible in a bug report.
                    Log.w(TAG, "Onboarding flag unreadable; routing to onboarding", e)
                    false
                }
            }

            // Hard timeout on the splash gate. Whatever the reads above do, showSplash cannot
            // latch: a rider who opens the app always reaches a screen.
            LaunchedEffect(Unit) {
                delay(SPLASH_GATE_TIMEOUT_MS)
                if (hasCompletedOnboarding == null) {
                    Log.w(
                        TAG,
                        "Onboarding flag still unread after ${SPLASH_GATE_TIMEOUT_MS}ms; " +
                            "releasing the splash gate"
                    )
                    hasCompletedOnboarding = false
                }
                isSplashFinished = true
            }

            // Presentation preloads, kept separate from routing on purpose: neither a failure
            // nor a hang in here may gate the UI.
            LaunchedEffect(Unit) {
                try {
                    AlertConfig.t2Enabled = OnboardingStore.t2EnabledFlow(context).first()
                } catch (e: Exception) {
                    Log.w(TAG, "t2Enabled unreadable; keeping default", e)
                }
                try {
                    AlertConfig.clockStyle = OnboardingStore.clockStyleFlow(context).first()
                } catch (e: Exception) {
                    Log.w(TAG, "clockStyle unreadable; keeping default", e)
                }
            }

            LaunchedEffect(viewModel) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is Effect.Vibrate -> haptics.play(effect.pattern)
                        is Effect.PreAlertFlash -> {
                            // Flash is handled visually by HudScreen reacting to phase == PRE_ALERT
                        }
                    }
                }
            }

            var currentLanguage by rememberSaveable(
                stateSaver = Saver(
                    save = { it.name },
                    restore = { runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.VI) }
                )
            ) { mutableStateOf(AppLanguage.VI) }

            LaunchedEffect(Unit) {
                try {
                    val savedLang = OnboardingStore.languageFlow(context).first()
                    currentLanguage = savedLang
                } catch (e: Exception) {
                    Log.w(TAG, "Language unreadable; keeping ${currentLanguage.name}", e)
                }
            }

            val strings = when (currentLanguage) {
                AppLanguage.VI -> ViStrings
                AppLanguage.EN -> EnStrings
            }

            CompositionLocalProvider(LocalStrings provides strings) {
                // Held in composition state, not read straight off AlertConfig: AlertConfig is a
                // plain mutable object with no change notification, so a theme switch made in
                // Settings would not repaint anything until the next recomposition for some
                // unrelated reason.
                var themeModeName by rememberSaveable { mutableStateOf(AlertConfig.themeMode) }
                var dynamicColorEnabled by rememberSaveable { mutableStateOf(false) }
                var contrastBoostModeName by rememberSaveable { mutableStateOf(AlertConfig.contrastBoostMode.name) }
                val sensorBoostLevel by LightSensor.boostLevelFlow.collectAsState()

                val contrastBoostMode = when (contrastBoostModeName) {
                    ContrastBoostMode.ALWAYS_ON.name -> ContrastBoostMode.ALWAYS_ON
                    ContrastBoostMode.OFF.name -> ContrastBoostMode.OFF
                    else -> ContrastBoostMode.AUTO
                }

                // AUTO carries the sensor's proportional strength rather than a flag, so the
                // treatment scales with how harsh the light actually is. The manual modes are
                // genuinely binary - the rider has asked for it or has not.
                val contrastBoostLevel = when (contrastBoostMode) {
                    ContrastBoostMode.ALWAYS_ON -> 1f
                    ContrastBoostMode.OFF -> 0f
                    ContrastBoostMode.AUTO -> sensorBoostLevel
                }
                // Screen brightness has no partial state worth expressing: the panel is either
                // being driven hard for sunlight or left to the system.
                val isContrastBoosted = contrastBoostLevel > 0f

                DisposableEffect(isContrastBoosted) {
                    setScreenBrightnessOverride(isContrastBoosted)
                    onDispose {
                        setScreenBrightnessOverride(false)
                    }
                }

                val themeMode = when (themeModeName) {
                    "LIGHT" -> ThemeMode.LIGHT
                    "DARK" -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                LaunchedEffect(Unit) {
                    themeModeName = try {
                        OnboardingStore.themeModeFlow(context).first()
                    } catch (e: Exception) {
                        Log.w(TAG, "Theme mode unreadable; using SYSTEM", e)
                        "SYSTEM"
                    }
                }
                LaunchedEffect(Unit) {
                    OnboardingStore.dynamicColorEnabledFlow(context).collect {
                        dynamicColorEnabled = it
                    }
                }
                LaunchedEffect(Unit) {
                    OnboardingStore.contrastBoostModeFlow(context).collect {
                        contrastBoostModeName = it.name
                        AlertConfig.contrastBoostMode = it
                    }
                }
                PulsePathTheme(
                    themeMode = themeMode,
                    dynamicColor = dynamicColorEnabled,
                    contrastBoostLevel = contrastBoostLevel
                ) {
                    val showSplash = (hasCompletedOnboarding == null) || !isSplashFinished
                    Crossfade(
                        targetState = showSplash,
                        label = "splash_crossfade"
                    ) { isDisplayingSplash ->
                        if (isDisplayingSplash) {
                            SplashScreen(
                                durationMillis = 2000L,
                                onSplashFinished = { isSplashFinished = true }
                            )
                        } else {
                            val onboardingComplete = hasCompletedOnboarding ?: false
                            val navController = rememberNavController()
                            NavHost(
                                navController = navController,
                                startDestination = if (onboardingComplete) "hud" else OnboardingRoutes.LANGUAGE
                            ) {
                                composable("home") {
                                    PlaceholderScreen(
                                        viewModel = viewModel,
                                        currentLanguage = currentLanguage,
                                        onToggleLanguage = {
                                            currentLanguage = if (currentLanguage == AppLanguage.VI) {
                                                AppLanguage.EN
                                            } else {
                                                AppLanguage.VI
                                            }
                                        },
                                        onNavigateToStereoProbe = {
                                            navController.navigate("stereo_probe")
                                        }
                                    )
                                }
                                composable("stereo_probe") {
                                    StereoProbeScreen(
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("hud") {
                                    val hudContext = LocalContext.current
                                    val lifecycleOwner = LocalLifecycleOwner.current
                                    // Reaching the HUD used to be possible only by finishing onboarding,
                                    // which starts the service on its way past. Now that a returning rider
                                    // lands here directly, the HUD itself has to ensure monitoring is
                                    // actually running - otherwise the app would look like it is listening
                                    // while the service was never started.
                                    //
                                    // withResumed, not a bare LaunchedEffect: the splash gate can expire
                                    // while the app is in the background, which composed this route - and
                                    // therefore called ListenerService.start() - with the app not visible.
                                    // On Android 14+ a microphone-typed foreground service started from the
                                    // background throws SecurityException straight out of onStartCommand
                                    // (RECORD_AUDIO is while-in-use, so it is not accessible in that state)
                                    // and the framework kills the process. The rider came back to a dead
                                    // task painting nothing but its window background - the blank screen on
                                    // resume. This defers the one-shot start to the moment they are
                                    // actually looking at the HUD; it still fires exactly once per
                                    // composition, so a deliberate Stop is not undone by an app switch.
                                    DisposableEffect(lifecycleOwner) {
                                        val observer = LifecycleEventObserver { _, event ->
                                            when (event) {
                                                Lifecycle.Event.ON_RESUME -> {
                                                    ListenerService.isHudVisible.value = true
                                                    com.soundsight.audio.BackgroundSirenPlayer.stop(hudContext)
                                                }
                                                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                                                    ListenerService.isHudVisible.value = false
                                                }
                                                else -> Unit
                                            }
                                        }
                                        lifecycleOwner.lifecycle.addObserver(observer)
                                        onDispose {
                                            ListenerService.isHudVisible.value = false
                                            lifecycleOwner.lifecycle.removeObserver(observer)
                                        }
                                    }

                                    LaunchedEffect(lifecycleOwner) {
                                        lifecycleOwner.lifecycle.withResumed {
                                            val granted = ContextCompat.checkSelfPermission(
                                                hudContext,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (granted && !ListenerService.isRunning.value) {
                                                ListenerService.start(hudContext)
                                            }
                                        }
                                    }
                                    Box(modifier = Modifier.fillMaxSize().testTag("route_hud")) {
                                        HudScreen(
                                            viewModel = viewModel,
                                            language = currentLanguage,
                                            onSettings = { navController.navigate("settings") },
                                            onStop = {
                                                wasRunningBeforeBackground = false
                                                ListenerService.stop(hudContext)
                                            },
                                            onStart = { ListenerService.start(hudContext) },
                                            onOpenDevConsole = { navController.navigate("home") }
                                        )
                                    }
                                }
                                composable("settings") {
                                    SettingsScreen(
                                        language = currentLanguage,
                                        onLanguageChange = { currentLanguage = it },
                                        viewModel = viewModel,
                                        onNavigateToDirectionArrow = { navController.navigate("direction_arrow") },
                                        onNavigateToCollision = { navController.navigate("collision_detection") },
                                        onThemeModeChange = { themeModeName = it },
                                        onTestAlert = {
                                            // Pop to the HUD first, fire the alert second: the alert renders
                                            // inside HudScreen, so firing it while Settings is on top would
                                            // play the takeover out behind the screen the rider is looking at.
                                            navController.popBackStack("hud", inclusive = false)
                                            viewModel.fireTestAlert()
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("direction_arrow") {
                                    DirectionArrowScreen(
                                        onNavigateToCalibration = { navController.navigate("direction_calibration") },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("collision_detection") {
                                    CollisionDetectionScreen(
                                        onNavigateToEmergency = { navController.navigate("emergency_profile") },
                                        onPreviewCrashResponse = {
                                            navController.navigate("crash_preview")
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("crash_preview") {
                                    val previewContext = LocalContext.current
                                    val profileState by produceState<EmergencyProfile?>(initialValue = null) {
                                        EmergencyStore.profileFlow(previewContext).collect { value = it }
                                    }
                                    val currentProfile = profileState
                                    if (currentProfile != null) {
                                        CrashResponseScreen(
                                            profile = currentProfile.forPreview(strings),
                                            strings = strings,
                                            onDismiss = { navController.popBackStack() },
                                            isPreview = true
                                        )
                                    }
                                }
                                composable("emergency_profile") {
                                    EmergencyProfileScreen(
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("direction_calibration") {
                                    DirectionCalibrationScreen(
                                        onBack = { navController.popBackStack() },
                                        onComplete = { navController.popBackStack() },
                                        onReturnHome = { navController.popBackStack("hud", inclusive = false) }
                                    )
                                }
                                onboardingNavGraph(
                                    navController = navController,
                                    viewModel = onboardingViewModel,
                                    currentLanguage = currentLanguage,
                                    onLanguageChange = { currentLanguage = it },
                                    onOnboardingComplete = {
                                        navController.navigate("hud") {
                                            popUpTo(OnboardingRoutes.LANGUAGE) { inclusive = true }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private var wasRunningBeforeBackground = false

    private fun setScreenBrightnessOverride(full: Boolean) {
        val lp = window.attributes
        val target = if (full) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target
            window.attributes = lp
        }
    }

    override fun onStop() {
        super.onStop()
        setScreenBrightnessOverride(false)
        if (!isChangingConfigurations) {
            ListenerService.isHudVisible.value = false
            if (!AlertConfig.keepMonitoringInBackground) {
                if (ListenerService.isRunning.value) {
                    wasRunningBeforeBackground = true
                    ListenerService.stop(this)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (wasRunningBeforeBackground && !AlertConfig.keepMonitoringInBackground) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted && !ListenerService.isRunning.value) {
                wasRunningBeforeBackground = false
                ListenerService.start(this)
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun PlaceholderScreen(
    viewModel: HudViewModel,
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    onNavigateToStereoProbe: () -> Unit
) {
    val context = LocalContext.current
    val strings = SoundSightStrings.current

    val hudState by viewModel.uiState.collectAsStateWithLifecycle()
    val micLevel by remember(viewModel) { viewModel.micLevel.sample(100L) }
        .collectAsStateWithLifecycle(initialValue = 0f)

    val isRunning by ListenerService.isRunning.collectAsStateWithLifecycle()
    val liveLevel by ListenerService.level.collectAsStateWithLifecycle()
    val noiseFloorVal by ListenerService.noiseFloorValue.collectAsStateWithLifecycle()
    val thresholdVal by ListenerService.thresholdValue.collectAsStateWithLifecycle()
    val freezeCount by ListenerService.freezeEventCount.collectAsStateWithLifecycle()
    val unfreezeCount by ListenerService.unfreezeEventCount.collectAsStateWithLifecycle()
    val timeSinceLastSpike by ListenerService.timeSinceLastSpikeMs.collectAsStateWithLifecycle()
    val lastClip by ListenerService.lastCapturedClip.collectAsStateWithLifecycle()

    var preAlertCount by remember { mutableIntStateOf(0) }
    var selectedMicSource by remember { mutableStateOf(ListenerService.micSource) }
    var selectedSensitivity by remember { mutableStateOf(ListenerService.sensitivity) }
    var nsEnabled by remember { mutableStateOf(ListenerService.enableNoiseSuppressor) }
    var agcEnabled by remember { mutableStateOf(ListenerService.enableAutomaticGainControl) }
    var disableThinkingState by remember { mutableStateOf(GeminiConfig.disableThinking) }
    var t2EnabledState by remember { mutableStateOf(AlertConfig.t2Enabled) }
    var motionSensorsEnabledState by remember { mutableStateOf(AlertConfig.motionSensorsEnabled) }

    val coroutineScope = rememberCoroutineScope()
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiTestResult by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            ListenerService.resetDebugAudioStatics(context)
        }
    }

    LaunchedEffect(Unit) {
        try {
            val savedT2 = OnboardingStore.t2EnabledFlow(context).first()
            t2EnabledState = savedT2
            AlertConfig.t2Enabled = savedT2
            val savedMotion = OnboardingStore.motionSensorsEnabledFlow(context).first()
            motionSensorsEnabledState = savedMotion
            AlertConfig.motionSensorsEnabled = savedMotion
        } catch (e: Exception) {
            Log.w(TAG, "Dev-console preferences unreadable; keeping defaults", e)
        }
        ListenerService.preAlerts.collect {
            preAlertCount++
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            ListenerService.start(context)
        }
    }

    Scaffold(
        containerColor = BgIdle,
        contentColor = FgIdle
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.appName,
                    style = AlertLabel,
                    color = AccentLive,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("app_name_text")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = strings.tagline,
                    style = AlertSub,
                    color = FgIdle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("tagline_text")
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Status & Diagnostics Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF161E27)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isRunning) strings.statusListening else strings.statusOffline,
                                style = IdleStatus,
                                color = if (isRunning) AccentLive else Degraded,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = if (isRunning) "ACTIVE" else "IDLE",
                                style = HistoryRow,
                                color = if (isRunning) AccentLive else Degraded
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Mic Level (dBFS mapped):",
                                    style = HistoryRow,
                                    color = Degraded
                                )
                                Text(
                                    text = String.format(Locale.US, "%.3f", liveLevel),
                                    style = HistoryRow,
                                    color = FgIdle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("mic_level_text")
                                )
                            }

                            LinearProgressIndicator(
                                progress = { liveLevel },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = if (liveLevel > 0.6f) WarningBg else AccentLive,
                                trackColor = BgIdle,
                                strokeCap = StrokeCap.Round
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Pre-alerts (Tier 1 fired):",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Text(
                                text = "$preAlertCount",
                                style = IdleStatus,
                                color = if (preAlertCount > 0) WarningBg else FgIdle,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("pre_alert_count_text")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Floor / Threshold (RMS):",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Text(
                                text = "${noiseFloorVal.toInt()} / ${thresholdVal.toInt()}",
                                style = HistoryRow,
                                color = FgIdle,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("noise_floor_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Debug Row (Temporary)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF101720)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Debug: Audio Source & FX (A/B Test)",
                            style = HistoryRow,
                            color = WarningBg,
                            fontWeight = FontWeight.Bold
                        )

                        // MicSource selector chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MicSource.values().forEach { source ->
                                FilterChip(
                                    selected = (selectedMicSource == source),
                                    onClick = {
                                        selectedMicSource = source
                                        ListenerService.micSource = source
                                        ListenerService.restartRecording(context)
                                    },
                                    label = { Text(source.name, style = HistoryRow) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentLive,
                                        selectedLabelColor = BgIdle,
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = FgIdle
                                    )
                                )
                            }
                        }

                        // Sensitivity selector chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Sensitivity.values().forEach { sens ->
                                FilterChip(
                                    selected = (selectedSensitivity == sens),
                                    onClick = {
                                        selectedSensitivity = sens
                                        ListenerService.sensitivity = sens
                                    },
                                    label = { Text(sens.name, style = HistoryRow) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentLive,
                                        selectedLabelColor = BgIdle,
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = FgIdle
                                    )
                                )
                            }
                        }

                        // NS & AGC toggles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("NoiseSuppressor", style = HistoryRow, color = FgIdle)
                                Switch(
                                    checked = nsEnabled,
                                    onCheckedChange = {
                                        nsEnabled = it
                                        ListenerService.enableNoiseSuppressor = it
                                        ListenerService.restartRecording(context)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentLive,
                                        checkedTrackColor = AccentLive.copy(alpha = 0.3f)
                                    )
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("AGC", style = HistoryRow, color = FgIdle)
                                Switch(
                                    checked = agcEnabled,
                                    onCheckedChange = {
                                        agcEnabled = it
                                        ListenerService.enableAutomaticGainControl = it
                                        ListenerService.restartRecording(context)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentLive,
                                        checkedTrackColor = AccentLive.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }

                        // Event counters & Spike timer readouts
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Freeze / Unfreeze Events:",
                                    style = HistoryRow,
                                    color = Degraded
                                )
                                Text(
                                    text = "$freezeCount / $unfreezeCount",
                                    style = HistoryRow,
                                    color = FgIdle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("freeze_unfreeze_count_text")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Time since last spike:",
                                    style = HistoryRow,
                                    color = Degraded
                                )
                                Text(
                                    text = if (timeSinceLastSpike < 0) "N/A" else "$timeSinceLastSpike ms",
                                    style = HistoryRow,
                                    color = if (timeSinceLastSpike in 0..4000) WarningBg else FgIdle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("time_since_spike_text")
                                )
                            }
                        }

                        // Enable Tier-2 (Gemini) toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enable Tier-2 (Gemini)",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Switch(
                                checked = t2EnabledState,
                                onCheckedChange = { checked ->
                                    t2EnabledState = checked
                                    AlertConfig.t2Enabled = checked
                                    coroutineScope.launch {
                                        OnboardingStore.setT2Enabled(context, checked)
                                    }
                                    // Open/close exactly one Live session in lockstep with the
                                    // toggle while monitoring is running; local classifier is
                                    // untouched either way.
                                    if (checked) {
                                        if (isRunning) LiveSession.start(context)
                                    } else {
                                        LiveSession.stop()
                                    }
                                },
                                modifier = Modifier.testTag("enable_t2_switch")
                            )
                        }

                        // Enable Motion Sensors (Accelerometer + Gyroscope) toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Enable Motion Sensors (Accel + Gyro)",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Switch(
                                checked = motionSensorsEnabledState,
                                onCheckedChange = { checked ->
                                    motionSensorsEnabledState = checked
                                    AlertConfig.motionSensorsEnabled = checked
                                    coroutineScope.launch {
                                        OnboardingStore.setMotionSensorsEnabled(context, checked)
                                    }
                                    // Same lockstep pattern as the Tier-2 toggle above: only
                                    // register/unregister the sensors if monitoring is already
                                    // running: MotionSensor.start/stop are idempotent, and
                                    // HudViewModel's isRunning collector independently keeps this
                                    // in sync across the actual start/stop of monitoring.
                                    if (checked) {
                                        if (isRunning) MotionSensor.start(context)
                                    } else {
                                        MotionSensor.stop()
                                    }
                                },
                                modifier = Modifier.testTag("enable_motion_sensors_switch")
                            )
                        }

                        // Disable thinking toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Disable thinking",
                                style = HistoryRow,
                                color = Degraded
                            )
                            Switch(
                                checked = disableThinkingState,
                                onCheckedChange = { checked ->
                                    disableThinkingState = checked
                                    GeminiConfig.disableThinking = checked
                                },
                                modifier = Modifier.testTag("disable_thinking_switch")
                            )
                        }

                        // Gemini Test Trigger
                        Button(
                            onClick = {
                                if (!AlertConfig.t2Enabled) {
                                    geminiTestResult = "Tier-2 disabled"
                                    return@Button
                                }
                                val clipToTest = lastClip
                                if (clipToTest == null) {
                                    geminiTestResult = "Error: No AudioClip captured yet. Start listener and trigger a spike first."
                                    return@Button
                                }
                                isTestingGemini = true
                                geminiTestResult = "Calling Gemini API..."
                                coroutineScope.launch {
                                    val start = System.currentTimeMillis()
                                    val result = SoundClassifier.classifyDebug(context, clipToTest)
                                    val latencyMs = System.currentTimeMillis() - start
                                    isTestingGemini = false
                                    geminiTestResult = result.fold(
                                        onSuccess = { dbg ->
                                            val r = dbg.result
                                            val t = dbg.trace
                                            val u = dbg.usage
                                            val kb = String.format(Locale.US, "%.2f", t.bodyBytesSent / 1024.0)
                                            val attemptTimes = t.attemptMs.joinToString(", ") { "${it}ms" }
                                            "Sound: ${r.soundId}\n" +
                                            "Tier: ${r.tier}\n" +
                                            "Confidence: ${r.confidence}\n" +
                                            "Motion: ${r.motion}\n" +
                                            "Caption (VI): ${r.captionVi}\n" +
                                            "Caption (EN): ${r.captionEn}\n" +
                                            "Latency: ${latencyMs} ms\n" +
                                            "Attempts: ${t.attempts}\n" +
                                            "Per-attempt: $attemptTimes\n" +
                                            "Request body: $kb KB\n" +
                                            "Thoughts tokens: ${u.thoughtsTokenCount}\n" +
                                            "Total tokens: ${u.totalTokenCount}"
                                        },
                                        onFailure = { err ->
                                            "Classification Failed (${latencyMs} ms):\n${err.message ?: err.toString()}"
                                        }
                                    )
                                }
                            },
                            enabled = !isTestingGemini,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF334155),
                                contentColor = FgIdle
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("test_gemini_button")
                        ) {
                            Text(
                                text = if (isTestingGemini) "Testing Gemini..." else "Test Gemini call",
                                style = HistoryRow,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        geminiTestResult?.let { res ->
                            Text(
                                text = res,
                                style = HistoryRow,
                                color = if (res.startsWith("Sound:")) AccentLive else WarningBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("gemini_test_result_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Start / Stop Controls
                if (!isRunning) {
                    Button(
                        onClick = {
                            val audioGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            if (audioGranted) {
                                ListenerService.start(context)
                            } else {
                                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentLive,
                            contentColor = BgIdle
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .testTag("start_stop_button")
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = strings.btnStart,
                            style = IdleStatus,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            ListenerService.stop(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CriticalBg,
                            contentColor = CriticalFg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .testTag("start_stop_button")
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            text = strings.btnStop,
                            style = IdleStatus,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Language Toggle
                OutlinedButton(
                    onClick = onToggleLanguage,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .testTag("language_toggle_button")
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (currentLanguage == AppLanguage.VI) "Chuyển sang English (EN)" else "Switch to Tiếng Việt (VI)",
                        style = HistoryRow,
                        color = FgIdle
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    onClick = onNavigateToStereoProbe,
                    modifier = Modifier
                        .testTag("btn_debug_stereo_probe")
                ) {
                    Text(
                        text = "Stereo Probe (DSP Diagnostics)",
                        style = HistoryRow,
                        color = AccentLive
                    )
                }
            }
        }
    }
}

/**
 * Debug-only transcription aid for the brand palette (round 2, Step 2): logs every role of the
 * framework's dynamicLight/DarkColorScheme on this device, so BrandLightScheme/BrandDarkScheme
 * can be transcribed from measurement rather than hand-picked hex. On a device that does no
 * wallpaper extraction (no `android.theme.customization.system_palette`), this logs the AOSP
 * default dynamic palette - which is exactly the identity the brand scheme is being aligned to.
 * No-op outside debug builds.
 */
private fun MainActivity.dumpDynamicPalettes() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Log.i(
            "BrandPaletteDump",
            "Dynamic colour is unavailable before Android 12; using the fixed PulsePath palette."
        )
        return
    }
    val roles = listOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "error", "onError", "errorContainer", "onErrorContainer",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant",
        "onSurfaceVariant", "surfaceTint", "inverseSurface", "inverseOnSurface",
        "outline", "outlineVariant", "scrim", "surfaceBright", "surfaceDim",
        "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
        "surfaceContainerHigh", "surfaceContainerHighest"
    )
    fun dump(name: String, scheme: androidx.compose.material3.ColorScheme) {
        val values = mapOf(
            "primary" to scheme.primary, "onPrimary" to scheme.onPrimary,
            "primaryContainer" to scheme.primaryContainer,
            "onPrimaryContainer" to scheme.onPrimaryContainer,
            "inversePrimary" to scheme.inversePrimary,
            "secondary" to scheme.secondary, "onSecondary" to scheme.onSecondary,
            "secondaryContainer" to scheme.secondaryContainer,
            "onSecondaryContainer" to scheme.onSecondaryContainer,
            "tertiary" to scheme.tertiary, "onTertiary" to scheme.onTertiary,
            "tertiaryContainer" to scheme.tertiaryContainer,
            "onTertiaryContainer" to scheme.onTertiaryContainer,
            "error" to scheme.error, "onError" to scheme.onError,
            "errorContainer" to scheme.errorContainer,
            "onErrorContainer" to scheme.onErrorContainer,
            "background" to scheme.background, "onBackground" to scheme.onBackground,
            "surface" to scheme.surface, "onSurface" to scheme.onSurface,
            "surfaceVariant" to scheme.surfaceVariant,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "surfaceTint" to scheme.surfaceTint,
            "inverseSurface" to scheme.inverseSurface,
            "inverseOnSurface" to scheme.inverseOnSurface,
            "outline" to scheme.outline, "outlineVariant" to scheme.outlineVariant,
            "scrim" to scheme.scrim, "surfaceBright" to scheme.surfaceBright,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest
        )
        val body = roles.joinToString(", ") { role ->
            val c = values.getValue(role)
            "$role=#%08X".format(c.toArgb())
        }
        android.util.Log.i("BrandPaletteDump", "$name {$body}")
    }
    dump("dynamicLight", androidx.compose.material3.dynamicLightColorScheme(this))
    dump("dynamicDark", androidx.compose.material3.dynamicDarkColorScheme(this))
    android.util.Log.i(
        "BrandPaletteDump",
        "systemPalette=" +
            android.provider.Settings.Secure.getString(
                contentResolver,
                "theme_customization_overlay_packages"
            )
    )
}
