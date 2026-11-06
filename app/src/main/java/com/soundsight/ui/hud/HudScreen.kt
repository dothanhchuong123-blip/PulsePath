package com.soundsight.ui.hud

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.audio.ListenerService
import com.soundsight.audio.liveness.LivenessState
import com.soundsight.core.AlertConfig
import com.soundsight.core.AppLanguage
import com.soundsight.core.ClockStyle
import com.soundsight.core.EmergencyProfile
import com.soundsight.core.EmergencyStore
import com.soundsight.core.LocationMode
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.core.SpeedProvider
import com.soundsight.core.Strings
import com.soundsight.data.live.LiveState
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.Tier
import com.soundsight.domain.AlertPresentation
import com.soundsight.ui.HudViewModel
import com.soundsight.ui.Phase
import com.soundsight.ui.demo.DemoPanel
import com.soundsight.ui.demo.planDynamicColorReset
import com.soundsight.ui.emergency.CrashResponseScreen
import com.soundsight.ui.history.HistorySheet
import com.soundsight.ui.theme.ActionButtonShape
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.Motion
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class HudPresentationState(
    val chipLabel: String,
    val chipColor: Color,
    val statusText: String,
    val isListening: Boolean,
    val reconnecting: Boolean,
    val hasCriticalBanner: Boolean
)

fun resolveHudPresentation(
    livenessState: LivenessState,
    isRunning: Boolean,
    reconnecting: Boolean,
    strings: Strings,
    primaryColor: Color,
    hazardCritical: Color,
    hazardDegraded: Color,
    micUnavailable: Boolean = false,
    setupErrorSuspended: Boolean = false,
    dailyQuotaExhausted: Boolean = false,
    rateLimited: Boolean = false,
    consecutiveFailures: Int = 0,
    /**
     * NOT_PROTECTING because the rider stopped or paused it (or never started). Presented as the
     * calm paused state, never as the red not-protecting alarm - that alarm is for protection
     * being lost, and the rider tapping Stop is the opposite of that.
     */
    riderIdle: Boolean = false
): HudPresentationState {
    val isListening = livenessState == LivenessState.PROTECTED && isRunning
    val protectionLost = livenessState == LivenessState.NOT_PROTECTING && !riderIdle
    val paused = !isRunning || (livenessState == LivenessState.NOT_PROTECTING && riderIdle)

    val chipLabel = when {
        protectionLost -> strings.hudNotProtectingChip
        paused -> strings.hudPausedChip
        livenessState == LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL -> when {
            micUnavailable -> strings.micInUseChip
            setupErrorSuspended -> strings.setupErrorChip
            dailyQuotaExhausted -> strings.dailyQuotaChip
            else -> strings.hudOfflineChip
        }
        livenessState == LivenessState.DEGRADED_BUT_LOCAL -> when {
            reconnecting -> strings.hudReconnectingChip
            rateLimited -> strings.rateLimitedChip
            consecutiveFailures >= 6 -> strings.buzzOnlyChip
            else -> strings.hudReconnectingChip
        }
        else -> strings.hudListeningChip
    }

    val chipColor = when {
        protectionLost -> hazardCritical
        paused -> hazardDegraded
        livenessState == LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL ||
            livenessState == LivenessState.DEGRADED_BUT_LOCAL -> hazardDegraded
        reconnecting -> hazardDegraded
        isListening -> primaryColor
        else -> hazardDegraded
    }

    val statusText = when {
        protectionLost -> strings.statusNotProtecting
        paused -> strings.statusPaused
        livenessState == LivenessState.CLOUD_UNAVAILABLE_BUT_LOCAL ||
            livenessState == LivenessState.DEGRADED_BUT_LOCAL -> strings.statusLocalProtectionOnly
        else -> strings.statusProtectionActive
    }

    val hasCriticalBanner = protectionLost || chipColor == hazardCritical

    return HudPresentationState(
        chipLabel = chipLabel,
        chipColor = chipColor,
        statusText = statusText,
        isListening = isListening,
        reconnecting = reconnecting,
        hasCriticalBanner = hasCriticalBanner
    )
}

@Composable
fun HudScreen(
    viewModel: HudViewModel,
    language: AppLanguage,
    onSettings: () -> Unit,
    onStop: () -> Unit,
    onStart: () -> Unit = {},
    onOpenDevConsole: () -> Unit = {}
) {
    val hudState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = SoundSightStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hazard = LocalHazardColors.current
    val colors = MaterialTheme.colorScheme
    // The HUD never read this before, which is why pressing Stop looked like nothing happened:
    // the service really did stop, but the chip still said "Listening" and the wave kept moving.
    val isRunning by ListenerService.isRunning.collectAsStateWithLifecycle()
    val emergencyProfile by produceState(initialValue = EmergencyProfile()) {
        EmergencyStore.flow(context).collect { value = it }
    }

    // Ticks once a second rather than once a minute: a clock that jumps a whole minute late looks
    // stopped, and this one is the rider's only clock while the phone is mounted.
    //
    // Round 3: the format follows the rider's persisted 24/12-hour preference. The AM/PM mark is
    // computed SEPARATELY from the hour value and never concatenated into clockText - it must not
    // corrupt the clock's stacking-width measurement or colon-split (see HudContent.amPmLabel).
    // Its text comes from the app's own VI/EN Strings, not SimpleDateFormat's "a" token, staying
    // consistent with every other piece of copy in the app (the clock has always ignored the
    // device locale).
    // Round 3 / H5: 24-hour format is the fixed default; rider toggle removed per H5 IA reorder.
    val use24HourClock = true
    var clockText by remember { mutableStateOf(currentClockText(use24HourClock)) }
    val amPmLabel: String? = null
    LaunchedEffect(Unit) {
        while (true) {
            clockText = currentClockText(use24HourClock)
            delay(1000L)
        }
    }
    var showHistory by remember { mutableStateOf(false) }
    var showDemoPanel by remember { mutableStateOf(false) }
    var showDemoDisclaimerDialog by remember { mutableStateOf(false) }

    fun requestOpenDemo() {
        if (viewModel.hasAcknowledgedDemoDisclaimer()) {
            viewModel.markDemoPanelVisited()
            scope.launch { OnboardingStore.setDemoPanelVisited(context) }
            showDemoPanel = true
        } else {
            showDemoDisclaimerDialog = true
        }
    }

    // "Always on" is the only mode with a live speed readout. The mode is persisted rider state;
    // produceState (not collectAsState) keeps this a one-flow subscription that starts before
    // first composition renders.
    val locationMode by produceState(initialValue = LocationMode.OFF) {
        OnboardingStore.locationModeFlow(context).collect { value = it }
    }
    val speedSampleFlow = if (locationMode == LocationMode.ALWAYS) SpeedProvider.sample else null

    // The speed NUMBER's visibility is its own preference, independent of the mode above: a
    // rider may want Always-on GNSS accuracy (collision messages, capture speed columns) without
    // a number competing on the HUD. Hiding it never stops the provider.
    val speedReadoutVisible by produceState(initialValue = true) {
        OnboardingStore.speedReadoutVisibleFlow(context).collect { value = it }
    }

    // AlertConfig is a plain mutable object with no change notification (see the theme-mode comment
    // in MainActivity), so a tap that only wrote AlertConfig.clockStyle would never repaint the
    // clock. Mirrored into composition state here, the same way MainActivity mirrors themeMode.
    var clockStyle by remember { mutableStateOf(AlertConfig.clockStyle) }
    // Rider-initiated only. The onboarding PermissionScreen is the one surface that asks
    // unprompted (and, unlike this dialog before its "Not now" exit existed, it has a decline
    // path). This dialog stays available as the re-request when the rider tries to start
    // listening without permission - it must never fire on its own on cold start.
    var showPermissionPrompt by remember { mutableStateOf(false) }

    if (hudState.permissionRevoked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .systemBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = strings.permissionRevokedTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = hazard.Critical,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = strings.permissionRevokedBody,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .testTag("btn_open_app_settings"),
                    shape = ActionButtonShape
                ) {
                    Text(
                        text = strings.onboardingOpenSettingsButton,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        return
    }

    val livenessState = hudState.livenessState

    // Two sources, because they answer different questions and can legitimately disagree for a
    // moment: isOnline is the system's view of the radio, LiveState.OFFLINE is the coordinator's
    // debounced verdict that the outage is real. Either one is enough to stop claiming a cloud
    // tier that is not there.
    val offline = !hudState.isOnline || hudState.liveState == LiveState.OFFLINE

    // The cloud tier re-establishing itself, latched so the label, the colour and the ring around
    // the pill all last exactly as long as each other - and long enough that the ring is seen
    // closing, rather than a fast reconnect reading as a flicker.
    val liveConnecting = isRunning && AlertConfig.t2Enabled && !offline &&
        (hudState.liveState == LiveState.CONNECTING || hudState.liveState == LiveState.RECONNECTING)
    val reconnecting = rememberReconnectHold(liveConnecting)

    val presentation = resolveHudPresentation(
        livenessState = livenessState,
        isRunning = isRunning,
        reconnecting = reconnecting,
        strings = strings,
        primaryColor = colors.primary,
        hazardCritical = hazard.Critical,
        hazardDegraded = hazard.Degraded,
        micUnavailable = hudState.micUnavailable,
        setupErrorSuspended = hudState.setupErrorSuspended,
        dailyQuotaExhausted = hudState.dailyQuotaExhausted,
        rateLimited = hudState.rateLimited,
        consecutiveFailures = hudState.consecutiveFailures,
        riderIdle = hudState.livenessRiderIdle
    )
    val isListening = presentation.isListening
    val chipLabel = presentation.chipLabel
    val chipColor = presentation.chipColor
    val statusText = presentation.statusText

    // The wave morphs into whatever glyph the current alert would show. Sourced from
    // AlertPresentation so the wave and the takeover can never disagree about which icon a sound
    // gets. Null whenever no alert is up, which is what animates the wave back to a line.
    val alertEvent = hudState.current.takeIf { hudState.phase == Phase.ALERT }
    val alertIcon = alertEvent?.let { glyphIcon(AlertPresentation.getGlyph(it.soundId)) }
    val alertColor = when {
        alertEvent == null -> chipColor
        alertEvent.tier == Tier.CRITICAL -> hazard.Critical
        else -> hazard.Warning
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        HudContent(
            clockText = clockText,
            amPmLabel = amPmLabel,
            statusText = statusText,
            chipLabel = chipLabel,
            chipColor = chipColor,
            isListening = isListening,
            isRunning = isRunning,
            historyCount = hudState.history.size,
            micLevel = viewModel.micLevel,
            alertIcon = alertIcon,
            alertColor = alertColor,
            waveStyle = WaveStyle.LINE,
            // The toggle is read here rather than inside the indicator so that HudContent stays a
            // pure function of its parameters and can be rendered in the screenshot harness. A
            // rider who switched the arrow off gets UNKNOWN, which draws nothing at all.
            direction = if (AlertConfig.directionArrowEnabled) {
                hudState.direction
            } else {
                DirectionEstimate.UNKNOWN
            },
            reconnecting = reconnecting,
            reconnectActive = liveConnecting,
            clockStyle = clockStyle,
            speedSampleFlow = speedSampleFlow,
            speedUnit = strings.speedUnitKmh,
            speedNoFixLabel = strings.speedNoFix,
            speedHeldLabel = strings.speedHeld,
            speedReadoutVisible = speedReadoutVisible,
            onSettings = onSettings,
            onToggleRunning = {
                if (isRunning) {
                    onStop()
                } else if (hasMicPermission(context)) {
                    onStart()
                } else {
                    showPermissionPrompt = true
                }
            },
            onHistory = { showHistory = true },
            onWordmarkLongPress = { requestOpenDemo() },
            onClockTap = {
                val next = ClockStyle.entries[(clockStyle.ordinal + 1) % ClockStyle.entries.size]
                clockStyle = next
                AlertConfig.clockStyle = next
                scope.launch {
                    OnboardingStore.setClockStyle(context, next)
                }
            }
        )

        PreAlertOverlay(
            visible = hudState.phase == Phase.PRE_ALERT,
            color = hazard.Warning
        )

        if (hudState.phase == Phase.ALERT && hudState.current != null) {
            val event = hudState.current!!
            AlertTakeover(
                event = event,
                presentation = AlertPresentation.forEvent(event),
                language = language,
                strings = strings,
                direction = if (AlertConfig.directionArrowEnabled) {
                    hudState.direction
                } else {
                    DirectionEstimate.UNKNOWN
                },
                onDismiss = { viewModel.dismissAlert() }
            )
        }

        if (showHistory) {
            HistorySheet(
                history = hudState.history,
                language = language,
                onDismiss = { showHistory = false }
            )
        }

        if (showDemoPanel) {
            DemoPanel(
                viewModel = viewModel,
                onClose = {
                    ListenerService.resetDebugAudioStatics(context)
                    scope.launch {
                        OnboardingStore.setDynamicColorEnabled(context, planDynamicColorReset().targetState)
                    }
                    showDemoPanel = false
                },
                onOpenDevConsole = {
                    scope.launch {
                        OnboardingStore.setDynamicColorEnabled(context, planDynamicColorReset().targetState)
                    }
                    showDemoPanel = false
                    onOpenDevConsole()
                }
            )
        }

        if (showDemoDisclaimerDialog) {
            AlertDialog(
                onDismissRequest = { showDemoDisclaimerDialog = false },
                title = {
                    Text(
                        text = strings.demoDisclaimerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = strings.demoDisclaimerBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.acknowledgeDemoDisclaimer()
                            viewModel.markDemoPanelVisited()
                            scope.launch { OnboardingStore.setDemoPanelVisited(context) }
                            showDemoDisclaimerDialog = false
                            showDemoPanel = true
                        },
                        modifier = Modifier.testTag("btn_confirm_demo_disclaimer")
                    ) {
                        Text(strings.demoDisclaimerConfirm)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDemoDisclaimerDialog = false },
                        modifier = Modifier.testTag("btn_dismiss_demo_disclaimer")
                    ) {
                        Text(strings.demoDisclaimerDismiss)
                    }
                },
                modifier = Modifier.testTag("dialog_demo_disclaimer")
            )
        }

        if (showPermissionPrompt) {
            MicPermissionPrompt(
                onGranted = {
                    showPermissionPrompt = false
                    // Through the onStart callback, not ListenerService directly - HudScreen takes
                    // onStart as an injected parameter precisely so callers can control what
                    // "start" means; bypassing it here would silently stop honoring that for this
                    // one path.
                    onStart()
                },
                onDismiss = { showPermissionPrompt = false }
            )
        }

        // Last in the stack, so it covers everything including a demo panel left open.
        //
        // Three mutually exclusive cases, exhaustive over (event.isDemo, profile.isUsable):
        //  - demo/preview event -> EmergencyProfile.demoPlaceholder, ALWAYS with isPreview=true,
        //    so sample identity/medical data can never be mistaken for the rider's own;
        //  - real collision + usable profile -> the saved profile, isPreview=false;
        //  - real collision + unusable profile -> nothing at all. Substituting placeholder data
        //    here used to present a fabricated rider name, sample medical notes and a hardcoded
        //    blood type to whoever was standing over the phone after a REAL crash, unmarked,
        //    because isPreview computed as isDemo && !isUsable was false in exactly the one
        //    dangerous case. With no usable profile there is nothing the post-collision screen
        //    can usefully do; the ordinary collision alert/history behaviour still stands.
        val crashEvent = hudState.crashResponseFor
        if (crashEvent != null) {
            when (
                crashResponseRender(
                    isDemo = crashEvent.isDemo,
                    profileUsable = emergencyProfile.isUsable
                )
            ) {
                CrashResponseRender.DEMO_PREVIEW ->
                    CrashResponseScreen(
                        profile = EmergencyProfile.demoPlaceholder(strings),
                        strings = strings,
                        onDismiss = { viewModel.dismissCrashResponse() },
                        isPreview = true
                    )
                CrashResponseRender.SAVED_PROFILE ->
                    CrashResponseScreen(
                        profile = emergencyProfile,
                        strings = strings,
                        onDismiss = { viewModel.dismissCrashResponse() },
                        isPreview = false
                    )
                CrashResponseRender.NONE -> Unit
            }
        }
    }
}

/**
 * Which crash-response surface, if any, a pending [com.soundsight.domain.SoundEvent] may render.
 *
 * Extracted as a pure function so the safety-critical mapping is unit-testable without Compose:
 * no real (non-demo) event can ever select [CrashResponseRender.DEMO_PREVIEW], which is what
 * stops fabricated emergency data from reaching a first responder.
 */
enum class CrashResponseRender {
    /** Explicit demo/preview event: render the placeholder profile, always marked isPreview. */
    DEMO_PREVIEW,

    /** Real collision with a saved, usable profile: render it for real. */
    SAVED_PROFILE,

    /** Real collision with no usable profile: render no post-collision screen at all. */
    NONE
}

fun crashResponseRender(isDemo: Boolean, profileUsable: Boolean): CrashResponseRender = when {
    isDemo -> CrashResponseRender.DEMO_PREVIEW
    profileUsable -> CrashResponseRender.SAVED_PROFILE
    else -> CrashResponseRender.NONE
}

/** Device-local wall clock. Both modes keep a two-digit hour so changing clock preference does
 * not make the HUD width jump or alter the clock's geometry-driven stacking decision. */
private fun currentClockText(use24Hour: Boolean): String =
    formatClockText(java.util.Date(), use24Hour)

/** Extracted for deterministic coverage of the single-digit-hour case. */
internal fun formatClockText(date: java.util.Date, use24Hour: Boolean): String =
    java.text.SimpleDateFormat(
        if (use24Hour) "HH:mm" else "hh:mm",
        java.util.Locale.US
    ).format(date)

/**
 * Pure hour→meridiem mapping so the boundary (AM below 12, PM from 12 up) is unit-testable
 * without Compose or a formatted date string. The labels come from the app's own Strings.
 */
internal fun amPmLabelFor(hourOfDay: Int, amLabel: String, pmLabel: String): String =
    if (hourOfDay < 12) amLabel else pmLabel

internal fun currentAmPmLabel(strings: Strings): String =
    amPmLabelFor(
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
        strings.clockAmLabel,
        strings.clockPmLabel
    )
