package com.soundsight.ui.demo

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.soundsight.core.LocalStrings
import com.soundsight.core.OnboardingStore
import com.soundsight.ui.theme.WallpaperPaletteSupport
import com.soundsight.ui.theme.wallpaperPaletteSupport
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.audio.AudioClip
import com.soundsight.audio.ListenerService
import com.soundsight.audio.motion.MotionSensor
import com.soundsight.core.AlertConfig
import com.soundsight.core.DailyQuotaGuard
import com.soundsight.core.EncryptedCredentialStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.core.ViStrings
import com.soundsight.core.Telemetry
import com.soundsight.data.live.GeminiCredentialProvider
import com.soundsight.data.live.LiveSession
import com.soundsight.data.live.LiveState
import com.soundsight.data.live.UserKeyCredentialProvider
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.SoundId
import com.soundsight.domain.Tier
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.WarningBg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The diagnostics console keeps its own fixed dark palette instead of reading
 * [androidx.compose.material3.MaterialTheme.colorScheme].
 *
 * That is deliberate, not an oversight. Every accent in here is [AccentLive] (#22D3A6), which
 * carries roughly 10:1 against these near-black surfaces and about 1.9:1 against a light theme
 * background - following the theme would make the panel unreadable the moment a rider picked
 * light mode. The panel is also read in the dark beside a running HUD, so a white sheet is the
 * wrong object regardless of contrast. Named here rather than inlined so the console reads as one
 * chosen surface set instead of a dozen unexplained hex literals.
 */
private object DemoConsole {
    /** Panel background, and the sunken track behind every bar drawn on a [Card] surface. */
    val Bg = Color(0xFF0F172A)
    /** Raised block: every section body sits on this. */
    val Card = Color(0xFF1E293B)
    /** Hairline between sections, and the neutral fill of low-emphasis buttons. */
    val Line = Color(0xFF334155)
    /** Ink on an [AccentLive] fill. */
    val OnAccent = Bg
}

@Composable
fun DemoPanel(
    viewModel: HudViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDevConsole: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hudState by viewModel.uiState.collectAsStateWithLifecycle()
    // Follow the language the rest of the app is drawn in. HudState.language is a fixed VI
    // default that nothing updates, so reading it painted this panel Vietnamese in an English
    // app.
    val isVi = LocalStrings.current === ViStrings

    // Local per-button cooldown states (disabled for 2200ms)
    val buttonInFlight = remember { mutableStateMapOf<SoundId, Boolean>() }

    // Telemetry state collected directly (out of HudState)
    val lastLatency by Telemetry.lastLatencyMs.collectAsState()
    val lastLiveLatency by Telemetry.lastLiveLatencyMs.collectAsState()
    val totalClassifications by Telemetry.totalClassifications.collectAsState()
    val totalLiveClassifications by Telemetry.totalLiveClassifications.collectAsState()
    val droppedClips by Telemetry.droppedClips.collectAsState()
    val staleDiscardCount by Telemetry.staleDiscardCount.collectAsState()
    val failureCount by Telemetry.failureCount.collectAsState()
    val quotaBlockedCount by Telemetry.quotaBlockedCount.collectAsState()
    val failuresByCause by Telemetry.failuresByCause.collectAsState()
    val eventsBySoundId by Telemetry.eventsBySoundId.collectAsState()
    val motionCorroborationCount by Telemetry.motionCorroborationCount.collectAsState()
    val collisionSuppressedCount by Telemetry.collisionSuppressedCount.collectAsState()
    val strayAudioCount by Telemetry.strayAudioCount.collectAsState()
    val strayAudioBytesTotal by Telemetry.strayAudioBytesTotal.collectAsState()
    val localInferenceCount by Telemetry.localInferenceCount.collectAsState()
    val localOfflineAlertsCount by Telemetry.localOfflineAlertsCount.collectAsState()
    val localMeanInferenceTimeMs by Telemetry.localMeanInferenceTimeMs.collectAsState()
    val spikesDetected by ListenerService.freezeEventCount.collectAsState()
    val lastCloseDiag by LiveSession.lastCloseDiagnostic.collectAsState()
    val openSocketCount by LiveSession.openSocketCount.collectAsState()
    val handoverInProgress by LiveSession.handoverInProgress.collectAsState()
    val sessionRecycleCount by LiveSession.sessionRecycleCount.collectAsState()
    val activeConnectionId by LiveSession.activeConnectionId.collectAsState()
    val reconnectAttempt by LiveSession.reconnectAttempt.collectAsState()
    val reconnectDelayMs by LiveSession.reconnectDelayMs.collectAsState()
    val lastFailureKind by LiveSession.lastFailureKind.collectAsState()
    val goAwayTimeLeftMs by LiveSession.goAwayTimeLeftMs.collectAsState()
    val resumable by LiveSession.resumable.collectAsState()
    val resumptionHandleSetAtMs by LiveSession.resumptionHandleSetAtMs.collectAsState()
    val flightTimeoutCount by LiveSession.flightTimeoutCount.collectAsState()
    val disconnectFallbackCount by LiveSession.disconnectFallbackCount.collectAsState()
    val lateResponseCount by LiveSession.lateResponseCount.collectAsState()
    val standaloneTestBlocked by LiveSession.standaloneTestBlocked.collectAsState()
    val audioBytesSentTotal by LiveSession.audioBytesSentTotal.collectAsState()
    val audioMsSentTotal by LiveSession.audioMsSentTotal.collectAsState()
    val lastTurnUsage by LiveSession.lastTurnUsage.collectAsState()
    val promptSlopePerTurn by LiveSession.promptSlopePerTurn.collectAsState()
    val turnsThisConnection by LiveSession.turnsThisConnection.collectAsState()
    val promptTokensThisConnection by LiveSession.promptTokensThisConnection.collectAsState()
    val turnUsageHistory by LiveSession.turnUsageHistory.collectAsState()
    val lastCloseKind by LiveSession.lastCloseKind.collectAsState()
    val emptyUsageReports by LiveSession.emptyUsageReports.collectAsState()
    val liveCloseKindCounts by Telemetry.liveCloseKindCounts.collectAsState()
    val promptTokensTotal by LiveSession.promptTokensTotal.collectAsState()
    val responseTokensTotal by LiveSession.responseTokensTotal.collectAsState()
    val totalTokensRunning by LiveSession.totalTokensRunning.collectAsState()

    // MotionSensor exposes plain vars (not StateFlow), so poll them on a short interval rather
    // than relying on incidental recomposition - otherwise a screenshot taken right after a real
    // motion could catch a stale value from before this panel last happened to recompose.
    var liveAccelMagnitude by remember { mutableStateOf(0f) }
    var liveGyroMagnitude by remember { mutableStateOf(0f) }
    var peakAccelDelta by remember { mutableStateOf(0f) }
    var peakGyroMagnitude by remember { mutableStateOf(0f) }
    var fallSnapshot by remember { mutableStateOf(MotionSensor.fallSnapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            liveAccelMagnitude = MotionSensor.lastAccelMagnitude
            liveGyroMagnitude = MotionSensor.lastGyroMagnitude
            peakAccelDelta = MotionSensor.peakAccelDelta
            peakGyroMagnitude = MotionSensor.peakGyroMagnitude
            fallSnapshot = MotionSensor.fallSnapshot()
            delay(150L)
        }
    }
    val fallDetectedCount by Telemetry.fallDetectedCount.collectAsState()

    val p50 = remember(lastLatency, totalClassifications) { Telemetry.percentile(0.5) }
    val p90 = remember(lastLatency, totalClassifications) { Telemetry.percentile(0.9) }
    val latencyList = remember(lastLatency, totalClassifications) { Telemetry.latencyHistory() }

    val liveP50 = remember(lastLiveLatency, totalLiveClassifications) { Telemetry.livePercentile(0.5) }
    val liveP90 = remember(lastLiveLatency, totalLiveClassifications) { Telemetry.livePercentile(0.9) }
    val liveLatencyList = remember(lastLiveLatency, totalLiveClassifications) { Telemetry.liveLatencyHistory() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.replayFromFile(context, it) }
    }

    Surface(
        color = DemoConsole.Bg,
        contentColor = FgIdle,
        modifier = modifier
            .fillMaxSize()
            .testTag("demo_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Demo & Diagnostics",
                        style = AlertSub,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Test sound triggers, replay files, & view live telemetry",
                        style = HistoryRow,
                        color = Degraded,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = {
                        ListenerService.resetDebugAudioStatics(context)
                        scope.launch {
                            OnboardingStore.setDynamicColorEnabled(context, planDynamicColorReset().targetState)
                        }
                        onClose()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("btn_close_demo_panel")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = FgIdle,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = DemoConsole.Line)
            Spacer(modifier = Modifier.height(16.dp))

            // Four numbers above the fold. Everything else in this panel is ~900dp of scroll, so
            // opening it used to tell you nothing until you had scrolled past eleven inject
            // buttons - these are the values you actually came to read.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("demo_stat_strip"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill(
                    label = "LIVE SESSION",
                    value = hudState.liveState.toString(),
                    // The only qualitative cell in the strip, and RECONNECTING is twelve
                    // characters - it gets the extra width and a smaller face so it never
                    // truncates into a state you cannot identify.
                    accent = when (hudState.liveState) {
                        LiveState.READY, LiveState.SENDING -> AccentLive
                        LiveState.CONNECTING, LiveState.RECONNECTING -> WarningBg
                        // Offline is degraded, not broken: the on-device tier is still running, so
                        // it reads as a warning rather than sharing FAILED's critical red.
                        LiveState.OFFLINE -> WarningBg
                        LiveState.FAILED -> CriticalBg
                    },
                    valueFontSize = 13.sp,
                    modifier = Modifier.weight(1.8f)
                )
                StatPill(
                    label = "SPIKES",
                    value = "$spikesDetected",
                    accent = FgIdle,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    label = "LOCAL",
                    value = "$localInferenceCount",
                    accent = FgIdle,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    label = "LIVE p50",
                    value = if (liveP50 > 0) "${liveP50}ms" else "--",
                    // Amber past 6s because that is where a cloud label starts arriving after the
                    // rider has already passed the thing that made the sound.
                    accent = if (liveP50 in 1..6000) AccentLive else if (liveP50 > 6000) WarningBg else Degraded,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (onOpenDevConsole != null) {
                Button(
                    onClick = onOpenDevConsole,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .testTag("btn_open_dev_console"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DemoConsole.Card,
                        contentColor = AccentLive
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    // Title and scope on two lines. As one 48-character string it wrapped and
                    // then clipped inside the button's fixed height.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Open Developer Console",
                            style = HistoryRow.copy(lineHeight = 18.sp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "audio A/B, raw controls",
                            style = HistoryRow.copy(lineHeight = 14.sp),
                            color = Degraded,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = AccentLive,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Section 1: Inject Demo Events (All 11 SoundId values)
                SectionHeader("INJECT SOUND EVENTS")

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SoundId.values().forEach { soundId ->
                        val label = if (isVi) AlertPresentation.getLabelVi(soundId) else AlertPresentation.getLabelEn(soundId)
                        val inFlight = buttonInFlight[soundId] == true
                        val tier = SoundEvent.canonicalTier(soundId)
                        val tierColor = when (tier) {
                            Tier.CRITICAL -> CriticalBg
                            Tier.WARNING -> WarningBg
                            Tier.SAFE -> Degraded
                        }

                        Button(
                            onClick = {
                                buttonInFlight[soundId] = true
                                viewModel.injectDemoEvent(soundId)
                                scope.launch {
                                    delay(2200L)
                                    buttonInFlight[soundId] = false
                                }
                            },
                            enabled = !inFlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_demo_sound_${soundId.name}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DemoConsole.Card,
                                contentColor = FgIdle,
                                disabledContainerColor = DemoConsole.Card.copy(alpha = 0.5f),
                                disabledContentColor = Degraded
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(tierColor, CircleShape)
                                    )
                                    Text(
                                        text = label,
                                        style = HistoryRow.copy(lineHeight = 18.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // The enum name is what you type into a log filter, so it stays
                                    // - but demoted out of the rider-facing label it used to sit
                                    // inside brackets after.
                                    Text(
                                        text = soundId.name,
                                        style = HistoryRow.copy(lineHeight = 14.sp),
                                        color = Degraded,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                    if (inFlight) {
                                        Text(
                                            text = "2.2s",
                                            style = HistoryRow.copy(lineHeight = 14.sp),
                                            color = AccentLive,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            tint = AccentLive,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section: Crash Response Screen
                SectionHeader("POST-COLLISION CRASH RESPONSE")

                // No longer gated on a saved emergency profile. It used to be, mirroring the real
                // trigger - but the preview now renders a placeholder profile when none is saved
                // (EmergencyProfile.demoPlaceholder), so the gate only disabled the button for the
                // riders who most needed to see what they were being asked to fill in. It also
                // read the profile out of DataStore purely to decide whether to grey a button out,
                // which is one more place personal data flowed for no reason.
                Button(
                    onClick = {
                        viewModel.triggerDemoCrashResponse()
                        onClose()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_demo_crash_response"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CriticalBg,
                        contentColor = FgIdle
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Preview Post-Collision Screen",
                        style = HistoryRow,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section: Force Capture From Mic
                SectionHeader("FORCE CAPTURE FROM MIC")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        var isCapturing by remember { mutableStateOf(false) }
                        val isListenerRunning by ListenerService.isRunning.collectAsState()

                        Button(
                            onClick = {
                                ListenerService.forceCapture()
                                isCapturing = true
                                scope.launch {
                                    delay(1500L)
                                    isCapturing = false
                                }
                            },
                            enabled = isListenerRunning && !isCapturing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_force_capture"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLive,
                                contentColor = DemoConsole.OnAccent
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = when {
                                    !isListenerRunning -> "Start monitoring first"
                                    isCapturing -> "Capturing..."
                                    else -> "Force Capture & Classify Now"
                                },
                                style = IdleStatus,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Grabs the current mic buffer immediately, bypassing the spike/loudness gate. The clip flows through the normal pipeline (local classifier + Live/REST) exactly like a real detected sound.",
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section: Gemini API Credentials (BYO API Key)
                SectionHeader("GEMINI API CREDENTIALS")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val credentialStore = remember { EncryptedCredentialStore.getInstance(context) }
                        var hasUserKey by remember { mutableStateOf(credentialStore.hasUserKey()) }
                        var maskedKey by remember {
                            mutableStateOf(UserKeyCredentialProvider.getInstance(context).getMaskedKey())
                        }
                        var quotaLimitInput by remember {
                            mutableStateOf(DailyQuotaGuard.getDailyLimit(context).toString())
                        }
                        var candidateKeyInput by remember { mutableStateOf("") }
                        var isValidating by remember { mutableStateOf(false) }
                        var statusMessage by remember { mutableStateOf<String?>(null) }
                        var isErrorStatus by remember { mutableStateOf(false) }

                        // Current status row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Key",
                                style = HistoryRow,
                                color = FgIdle,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (hasUserKey) (maskedKey ?: "Custom Key") else "Built-in (BuildConfig)",
                                style = HistoryRow,
                                color = if (hasUserKey) AccentLive else FgIdle,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.testTag("text_masked_api_key")
                            )
                        }

                        TelemetryRow(
                            "Daily Quota Budget",
                            "${DailyQuotaGuard.getDailyLimit(context)} calls/day"
                        )

                        if (hasUserKey) {
                            // User budget configuration
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = quotaLimitInput,
                                    onValueChange = { quotaLimitInput = it.filter { c -> c.isDigit() } },
                                    label = { Text("User Quota Limit", fontSize = 11.sp, color = Degraded) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = FgIdle, fontSize = 13.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentLive,
                                        unfocusedBorderColor = DemoConsole.Line,
                                        focusedContainerColor = DemoConsole.Bg,
                                        unfocusedContainerColor = DemoConsole.Bg
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("input_user_quota_limit")
                                )
                                Button(
                                    onClick = {
                                        val parsed = quotaLimitInput.toIntOrNull()
                                        if (parsed != null && parsed > 0) {
                                            DailyQuotaGuard.setUserDailyLimit(context, parsed)
                                            statusMessage = "Quota limit set to $parsed calls/day"
                                            isErrorStatus = false
                                        } else {
                                            statusMessage = "Invalid number: enter a positive integer"
                                            isErrorStatus = true
                                        }
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("btn_save_quota_limit"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DemoConsole.Line,
                                        contentColor = FgIdle
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Set Limit", fontSize = 12.sp)
                                }
                            }

                            // Clear button
                            Button(
                                onClick = {
                                    credentialStore.clearAll()
                                    DailyQuotaGuard.resetTodayCount(context)
                                    DailyQuotaGuard.dailyLimit = DailyQuotaGuard.DEFAULT_BUILTIN_LIMIT
                                    hasUserKey = false
                                    maskedKey = null
                                    quotaLimitInput = DailyQuotaGuard.DEFAULT_BUILTIN_LIMIT.toString()
                                    candidateKeyInput = ""
                                    statusMessage = "Cleared custom key. Reverted to built-in APK key (480 calls/day)."
                                    isErrorStatus = false
                                    LiveSession.reconnect(context)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag("btn_clear_api_key"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WarningBg.copy(alpha = 0.3f),
                                    contentColor = WarningBg
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Clear / Reset to Built-in Key", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        HorizontalDivider(color = DemoConsole.Line)

                        Text(
                            text = if (hasUserKey) "Replace API Key" else "Enter Your Gemini API Key",
                            style = HistoryRow,
                            fontWeight = FontWeight.SemiBold,
                            color = FgIdle,
                            fontSize = 12.sp
                        )

                        OutlinedTextField(
                            value = candidateKeyInput,
                            onValueChange = { candidateKeyInput = it.trim() },
                            placeholder = { Text("Paste AIzaSy... key here", fontSize = 12.sp, color = Degraded) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = FgIdle, fontSize = 13.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentLive,
                                unfocusedBorderColor = DemoConsole.Line,
                                focusedContainerColor = DemoConsole.Bg,
                                unfocusedContainerColor = DemoConsole.Bg
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_gemini_api_key")
                        )

                        Button(
                            onClick = {
                                if (candidateKeyInput.isBlank()) {
                                    statusMessage = "API key cannot be empty"
                                    isErrorStatus = true
                                    return@Button
                                }
                                val candidate = candidateKeyInput
                                scope.launch {
                                    isValidating = true
                                    statusMessage = "Validating key with Gemini API..."
                                    isErrorStatus = false
                                    val result = UserKeyCredentialProvider.validateCandidateKey(candidate)
                                    isValidating = false
                                    result.fold(
                                        onSuccess = {
                                            credentialStore.setUserApiKey(candidate)
                                            val parsedLimit = quotaLimitInput.toIntOrNull()?.coerceAtLeast(1) ?: DailyQuotaGuard.DEFAULT_USER_LIMIT
                                            credentialStore.setUserDailyLimit(parsedLimit)
                                            DailyQuotaGuard.resetTodayCount(context)
                                            hasUserKey = true
                                            maskedKey = UserKeyCredentialProvider.maskApiKey(candidate)
                                            quotaLimitInput = parsedLimit.toString()
                                            candidateKeyInput = ""
                                            statusMessage = "Validated and saved! Session reconnected with user quota ($parsedLimit calls/day)."
                                            isErrorStatus = false
                                            LiveSession.reconnect(context)
                                        },
                                        onFailure = { err ->
                                            statusMessage = "Validation failed: ${err.message ?: "Invalid key"}"
                                            isErrorStatus = true
                                        }
                                    )
                                }
                            },
                            enabled = !isValidating && candidateKeyInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("btn_validate_save_api_key"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLive,
                                contentColor = DemoConsole.OnAccent,
                                disabledContainerColor = DemoConsole.Line,
                                disabledContentColor = Degraded
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isValidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = DemoConsole.OnAccent
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Validating...", fontSize = 12.sp)
                            } else {
                                Text("Validate & Save API Key", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (statusMessage != null) {
                            Text(
                                text = statusMessage!!,
                                style = HistoryRow,
                                color = if (isErrorStatus) CriticalBg else AccentLive,
                                fontSize = 11.sp,
                                modifier = Modifier.testTag("text_credential_status")
                            )
                        }
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section 2: Test Live WebSocket Session
                SectionHeader("TEST LIVE WEBSOCKET SESSION")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        var isTestingLive by remember { mutableStateOf(false) }
                        var liveTestOutput by remember { mutableStateOf<String?>(null) }
                        val lastClip by ListenerService.lastCapturedClip.collectAsState()

                        Button(
                            onClick = {
                                val clipToTest = lastClip ?: run {
                                    val fakePcm = ByteArray(16000 * 2) { 0 }
                                    val wav = com.soundsight.audio.WavEncoder.encode(fakePcm, 16000, 1)
                                    AudioClip(wav, System.currentTimeMillis())
                                }
                                isTestingLive = true
                                liveTestOutput = "Connecting & sending 2s clip over Live WebSocket..."
                                scope.launch {
                                    val result = LiveSession.testLiveCall(context, clipToTest)
                                    isTestingLive = false
                                    liveTestOutput = result.fold(
                                        onSuccess = { event ->
                                            "Status: SUCCESS\n" +
                                            "Sound: ${event.result.soundId}\n" +
                                            "Tier: ${event.result.tier}\n" +
                                            "Confidence: ${event.result.confidence}\n" +
                                            "Motion: ${event.result.motion}\n" +
                                            "Caption (VI): ${event.result.captionVi}\n" +
                                            "Caption (EN): ${event.result.captionEn}\n" +
                                            "Latency (T2): ${event.latencyMs} ms\n" +
                                            "Raw JSON args:\n${event.rawJsonArgs}"
                                        },
                                        onFailure = { err ->
                                            "Live Session Error:\n${err.message ?: err.toString()}"
                                        }
                                    )
                                }
                            },
                            enabled = !isTestingLive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_test_live_session"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLive,
                                contentColor = DemoConsole.OnAccent
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (isTestingLive) "Testing Live API..." else "Test Live session",
                                style = IdleStatus,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        liveTestOutput?.let { out ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = out,
                                style = HistoryRow,
                                color = if (out.startsWith("Status: SUCCESS")) AccentLive else WarningBg,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("live_test_result_text")
                            )
                        }
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section 3: Replay From File
                SectionHeader("REPLAY AUDIO CLIP FROM FILE")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch("audio/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_demo_replay_file"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLive,
                                contentColor = DemoConsole.OnAccent
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Select Audio File to Replay",
                                    style = IdleStatus,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Note: Selecting a file sends a real network classification request and consumes daily Gemini quota.",
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section: Wallpaper Palette Test Control
                SectionHeader("WALLPAPER PALETTE TEST CONTROL")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        val strings = LocalStrings.current
                        val dynamicColorOn by OnboardingStore.dynamicColorEnabledFlow(context)
                            .collectAsState(initial = false)
                        val support = remember {
                            wallpaperPaletteSupport(
                                apiLevel = Build.VERSION.SDK_INT,
                                systemPaletteRaw = try {
                                    android.provider.Settings.Secure.getString(
                                        context.contentResolver,
                                        "theme_customization_overlay_packages"
                                    )
                                } catch (_: Exception) {
                                    null
                                },
                                dynamicLightPrimary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    try {
                                        androidx.compose.material3.dynamicLightColorScheme(context).primary
                                    } catch (_: Exception) {
                                        null
                                    }
                                } else {
                                    null
                                }
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 16.dp)
                            ) {
                                Text(
                                    text = "${strings.wallpaperColorsLabel} (Test Control)",
                                    style = HistoryRow,
                                    fontWeight = FontWeight.Bold,
                                    color = FgIdle
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = strings.wallpaperColorsSub,
                                    style = HistoryRow,
                                    color = Degraded,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = dynamicColorOn,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        OnboardingStore.setDynamicColorEnabled(context, checked)
                                    }
                                },
                                modifier = Modifier.testTag("switch_demo_wallpaper_colors")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Diagnostic probe readout
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DemoConsole.Bg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Device Extraction Probe",
                                    style = HistoryRow,
                                    color = FgIdle,
                                    fontSize = 12.sp
                                )
                                val probeTag = when (support) {
                                    WallpaperPaletteSupport.AVAILABLE -> "AVAILABLE"
                                    WallpaperPaletteSupport.NO_EXTRACTION -> "NO_EXTRACTION (Framework default #475D92)"
                                    WallpaperPaletteSupport.UNSUPPORTED_API -> "UNSUPPORTED_API (< Android 12)"
                                }
                                val probeColor = when (support) {
                                    WallpaperPaletteSupport.AVAILABLE -> AccentLive
                                    WallpaperPaletteSupport.NO_EXTRACTION -> Degraded
                                    WallpaperPaletteSupport.UNSUPPORTED_API -> WarningBg
                                }
                                Text(
                                    text = probeTag,
                                    style = HistoryRow,
                                    fontWeight = FontWeight.Bold,
                                    color = probeColor,
                                    fontSize = 11.sp,
                                    modifier = Modifier.testTag("text_wallpaper_probe_status")
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section: Statistics (detections by sound type + session summary). Sourced from
                // Telemetry.eventsBySoundId, which HudViewModel.handleDecision() updates for every
                // real, replayed, or demo-injected classification that reaches a decision - so this
                // reflects the whole session, not just what was injected from this panel.
                SectionHeader("STATISTICS")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SubHeader("Detections by Sound Type")

                        if (eventsBySoundId.isEmpty()) {
                            Text(
                                text = "No detections yet this session.",
                                style = HistoryRow,
                                color = Degraded,
                                fontSize = 12.sp
                            )
                        } else {
                            val sortedEvents = remember(eventsBySoundId) {
                                eventsBySoundId.entries.sortedByDescending { it.value }
                            }
                            val maxEventCount = remember(eventsBySoundId) {
                                maxOf(1, eventsBySoundId.values.max())
                            }

                            sortedEvents.forEach { (soundIdName, count) ->
                                val soundId = SoundId.values().firstOrNull { it.name == soundIdName }
                                val label = soundId?.let {
                                    if (isVi) AlertPresentation.getLabelVi(it) else AlertPresentation.getLabelEn(it)
                                } ?: soundIdName
                                val tierColor = when (soundId?.let { SoundEvent.canonicalTier(it) }) {
                                    Tier.CRITICAL -> CriticalBg
                                    Tier.WARNING -> WarningBg
                                    else -> Degraded
                                }

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "$label ($soundIdName)",
                                            style = HistoryRow,
                                            color = FgIdle,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "$count",
                                            style = HistoryRow,
                                            color = FgIdle,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(DemoConsole.Bg)
                                    ) {
                                        val fraction = count.toFloat() / maxEventCount.toFloat()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(tierColor)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = DemoConsole.Line)

                        SubHeader("Session Summary")
                        TelemetryRow("Total Classifications (REST)", "$totalClassifications")
                        TelemetryRow("Total Classifications (Live)", "$totalLiveClassifications")
                        TelemetryRow("Local (On-Device) Inferences", "$localInferenceCount")
                        TelemetryRow("Offline Alerts Fired", "$localOfflineAlertsCount")
                        TelemetryRow("Discarded as Stale", "$staleDiscardCount")
                        TelemetryRow("Motion-Corroborated Collisions", "$motionCorroborationCount")
                        TelemetryRow("Collisions Suppressed (detection off)", "$collisionSuppressedCount")
                        TelemetryRow("Dropped Clips (In-flight)", "$droppedClips")
                        TelemetryRow("Failures", "$failureCount")
                        TelemetryRow("Quota Blocked", "$quotaBlockedCount")
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section 3: Telemetry Readout
                SectionHeader("LIVE TELEMETRY READOUT")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SubHeader("Live Session")

                        TelemetryRow("Live Session State", "${hudState.liveState}")
                        TelemetryRow("Live Connection ID", activeConnectionId?.toString() ?: "None")
                        TelemetryRow("Open Socket Count", "$openSocketCount")
                        TelemetryRow("Handover In Progress", "$handoverInProgress")
                        TelemetryRow("Fresh Sessions (per-turn recycle)", "$sessionRecycleCount")
                        TelemetryRow("Live Last Close Code", lastCloseDiag ?: "None yet")
                        TelemetryRow("Last Failure Kind", lastFailureKind ?: "None")
                        TelemetryRow(
                            "Reconnect Attempt / Delay",
                            if (reconnectAttempt > 0) "#$reconnectAttempt (${reconnectDelayMs ?: 0}ms)" else "None"
                        )
                        TelemetryRow("goAway timeLeft", goAwayTimeLeftMs?.let { "${it}ms" } ?: "None")
                        TelemetryRow(
                            "Resumption Handle",
                            when {
                                resumable == false -> "not resumable"
                                resumptionHandleSetAtMs == null -> "none"
                                else -> "held (age ${System.currentTimeMillis() - (resumptionHandleSetAtMs ?: 0L)}ms)"
                            }
                        )
                        TelemetryRow("Standalone Test Blocked", "$standaloneTestBlocked")
                        TelemetryRow("Live API Last T2 Latency", if (lastLiveLatency > 0) "${lastLiveLatency}ms" else "None")
                        TelemetryRow("Live API p50 Latency", if (liveP50 > 0) "${liveP50}ms" else "N/A")
                        TelemetryRow("Live API p90 Latency", if (liveP90 > 0) "${liveP90}ms" else "N/A")
                        TelemetryRow("Live Total Events", "$totalLiveClassifications")
                        TelemetryRow("Audio Sent (Live)", "$audioBytesSentTotal bytes / ${audioMsSentTotal}ms")
                        TelemetryRow("Prompt / Response / Total Tokens", "$promptTokensTotal / $responseTokensTotal / $totalTokensRunning")

                        // Per-turn, not cumulative. A running total that climbs says nothing about
                        // whether the per-turn cost is flat or growing, and only the growing case
                        // explains a session that runs fine and then hits a quota it should never
                        // reach. The slope is the number that answers it.
                        TelemetryRow(
                            "Prompt Tokens / Turn (last, delta)",
                            lastTurnUsage?.let { "${it.promptTokens} (${if (it.promptDelta >= 0) "+" else ""}${it.promptDelta})" } ?: "N/A"
                        )
                        TelemetryRow(
                            "Prompt Token Slope",
                            if (turnUsageHistory.size >= 2) String.format("%.1f tokens/turn (last %d)", promptSlopePerTurn, minOf(turnUsageHistory.size, 20)) else "N/A"
                        )
                        TelemetryRow(
                            "Prompt Modality Split (last turn)",
                            lastTurnUsage?.let { "audio ${it.promptAudioTokens} / text ${it.promptTextTokens}" } ?: "N/A"
                        )
                        TelemetryRow(
                            "Response Tokens / Turn (last, audio)",
                            lastTurnUsage?.let { "${it.responseTokens} (audio ${it.responseAudioTokens})" } ?: "N/A"
                        )
                        TelemetryRow("Turns / Prompt Tokens This Connection", "$turnsThisConnection / $promptTokensThisConnection")
                        // A high count here next to a low turn count means the server has stopped
                        // reporting usage, not that the session is free.
                        TelemetryRow("Empty Usage Reports", "$emptyUsageReports")
                        TelemetryRow("Last Close Kind", lastCloseKind?.name ?: "None")
                        TelemetryRow(
                            "Live Closes By Kind",
                            if (liveCloseKindCounts.isEmpty()) "None" else liveCloseKindCounts.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }
                        )
                        TelemetryRow("Flight Timeouts", "$flightTimeoutCount")
                        TelemetryRow("Disconnect Fallbacks", "$disconnectFallbackCount")
                        TelemetryRow("Late/Unmatched Responses", "$lateResponseCount")
                        TelemetryRow("Stray Spoken Audio Fallbacks", "$strayAudioCount events (${strayAudioBytesTotal} bytes)")

                        HorizontalDivider(color = DemoConsole.Line)
                        SubHeader("REST Fallback & Motion")

                        TelemetryRow("REST Fallback Last Latency", if (lastLatency > 0) "${lastLatency}ms" else "None")
                        TelemetryRow("REST Fallback p50 Latency", if (p50 > 0) "${p50}ms" else "N/A")
                        TelemetryRow("REST Total Classifications", "$totalClassifications")
                        TelemetryRow("Dropped Clips (In-flight)", "$droppedClips")
                        TelemetryRow("Discarded as Stale (no alert shown)", "$staleDiscardCount")
                        TelemetryRow(
                            "Motion Sensors (Accel + Gyro)",
                            if (AlertConfig.motionSensorsEnabled) "enabled" else "disabled"
                        )
                        // Directly under the switch it reports on: with detection off this is the
                        // only place a suppressed collision shows up at all, and reading the two
                        // rows together is what tells you the silence was deliberate.
                        TelemetryRow(
                            "Collisions Suppressed (detection off)",
                            "$collisionSuppressedCount"
                        )
                        TelemetryRow(
                            "Live Accel / Gyro Magnitude",
                            "%.1f m/s² / %.2f rad/s".format(liveAccelMagnitude, liveGyroMagnitude)
                        )
                        TelemetryRow(
                            "Peak Accel Δ / Gyro Since Reset",
                            "%.1f m/s² / %.2f rad/s".format(peakAccelDelta, peakGyroMagnitude)
                        )
                        // The fall rule's own view: which stage it is in, the episode's peak, the
                        // tilt it last measured against the pre-impact orientation, how long the
                        // phone has been still, and how many falls it has reported this session.
                        TelemetryRow(
                            "Fall Detector (state / peak / tilt / still)",
                            "%s / %.1f m/s² / %.0f° / %.1f s".format(
                                fallSnapshot.state.name,
                                fallSnapshot.peakAccelDeltaMs2,
                                fallSnapshot.lastTiltDegrees,
                                fallSnapshot.stillMs / 1000f
                            )
                        )
                        TelemetryRow("Falls Detected (motion rule)", "$fallDetectedCount")
                        Button(
                            onClick = {
                                MotionSensor.resetPeaks()
                                peakAccelDelta = 0f
                                peakGyroMagnitude = 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("btn_reset_motion_peaks"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DemoConsole.Line,
                                contentColor = FgIdle
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Reset Motion Peaks (calibration)",
                                style = HistoryRow,
                                fontSize = 12.sp
                            )
                        }
                        TelemetryRow("Motion-Corroborated Collisions", "$motionCorroborationCount")
                        TelemetryRow("Quota Blocked Count", "$quotaBlockedCount")

                        // The "Daily quota" chip the rider sees comes from THIS counter and nothing
                        // else: DailyQuotaGuard is an app-side tally of REST fallback calls, tripped
                        // at dailyLimit, unrelated to the Live session's server-side limits. It had
                        // no callers outside SoundClassifier, so the one number that explains the
                        // chip was the one number nobody could read. Every clip the Live session
                        // does not accept - busy in flight, reconnecting, offline - spends one.
                        val activeDailyLimit = DailyQuotaGuard.getDailyLimit(context)
                        TelemetryRow(
                            "REST Daily Quota Used",
                            "${activeDailyLimit - DailyQuotaGuard.remainingToday(context)} / $activeDailyLimit"
                        )
                        TelemetryRow(
                            "Cloud Split (Live / REST)",
                            "$totalLiveClassifications / $totalClassifications"
                        )
                        TelemetryRow("Spikes Detected (Freeze)", "$spikesDetected")

                        HorizontalDivider(color = DemoConsole.Line)
                        SubHeader("On-Device Classifier")

                        TelemetryRow("Local Inferences (MediaPipe)", "$localInferenceCount")
                        TelemetryRow("Local Mean Latency", if (localInferenceCount > 0) String.format("%.1f ms", localMeanInferenceTimeMs) else "N/A")
                        TelemetryRow("Offline Alerts Fired", "$localOfflineAlertsCount")

                        if (failuresByCause.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "FAILURES BY CAUSE",
                                style = HistoryRow.copy(lineHeight = 16.sp),
                                color = CriticalBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.0.sp
                            )
                            failuresByCause.forEach { (cause, count) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = cause,
                                        style = HistoryRow,
                                        color = Degraded,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "$count",
                                        style = HistoryRow,
                                        color = FgIdle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = DemoConsole.Line)

                // Section 4: Latency Histogram
                SectionHeader("LATENCY HISTOGRAM (RECENT 100)")

                Card(
                    colors = CardDefaults.cardColors(containerColor = DemoConsole.Card),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val buckets = remember(latencyList) {
                            val b0_2 = latencyList.count { it in 0L..2000L }
                            val b2_4 = latencyList.count { it in 2001L..4000L }
                            val b4_6 = latencyList.count { it in 4001L..6000L }
                            val b6_8 = latencyList.count { it in 6001L..8000L }
                            val b8_plus = latencyList.count { it > 8000L }
                            listOf(
                                "0 - 2s" to b0_2,
                                "2 - 4s" to b2_4,
                                "4 - 6s" to b4_6,
                                "6 - 8s" to b6_8,
                                "8s+" to b8_plus
                            )
                        }

                        val maxBucketCount = maxOf(1, buckets.maxOf { it.second })

                        buckets.forEach { (label, count) ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = label,
                                        style = HistoryRow,
                                        color = Degraded,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "$count",
                                        style = HistoryRow,
                                        color = FgIdle,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(DemoConsole.Bg)
                                ) {
                                    val fraction = count.toFloat() / maxBucketCount.toFloat()
                                    if (fraction > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(AccentLive)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Section heading: an uppercase accent label followed by a rule out to the right edge.
 *
 * Replaces eight identical six-line `Text(...)` blocks. The rule is the part doing the work -
 * eight same-sized teal labels inside one ~900dp scroll read as a single undifferentiated wall,
 * and the line gives the eye somewhere to stop when it is skimming for a section rather than
 * reading one.
 */
@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = HistoryRow.copy(lineHeight = 16.sp),
            color = AccentLive,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp
        )
        HorizontalDivider(
            color = DemoConsole.Line,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Heading for a block *inside* a card - one level below [SectionHeader], which owns the panel's
 * top-level sections. The telemetry card holds four unrelated subsystems separated only by bare
 * hairlines; a line alone says "these are different" without ever saying what either one is.
 */
@Composable
private fun SubHeader(title: String) {
    Text(
        text = title,
        style = HistoryRow.copy(lineHeight = 16.sp),
        color = FgIdle.copy(alpha = 0.75f),
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.0.sp
    )
}

/** One above-the-fold number: big monospace value over a small dim caption. */
@Composable
private fun StatPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    valueFontSize: androidx.compose.ui.unit.TextUnit = 16.sp
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DemoConsole.Card)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            text = value,
            style = HistoryRow.copy(lineHeight = 22.sp),
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = valueFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = HistoryRow.copy(lineHeight = 13.sp),
            color = Degraded,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One label/value line.
 *
 * Three changes that matter at this row count - the telemetry card alone holds about forty-five
 * of these. The label takes the slack and truncates instead of colliding with the value; the
 * value is monospaced so the numbers stack into a readable column down the right edge instead of
 * jittering with glyph width; and the line height is pinned, because [HistoryRow] carries a 20sp
 * line height that left every 13sp row spaced like prose rather than like a readout.
 */
@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = HistoryRow.copy(lineHeight = 18.sp),
            color = Degraded,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.6f)
        )
        Text(
            text = value,
            style = HistoryRow.copy(lineHeight = 18.sp),
            color = FgIdle,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
