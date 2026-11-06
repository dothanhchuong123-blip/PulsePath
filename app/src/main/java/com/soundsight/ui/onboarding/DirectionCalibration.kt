package com.soundsight.ui.onboarding

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.audio.CaptureMode
import com.soundsight.audio.ListenerService
import com.soundsight.audio.StereoClip
import com.soundsight.audio.dsp.DirectionWindow
import com.soundsight.audio.dsp.Weighting
import com.soundsight.audio.dsp.bandRms
import com.soundsight.audio.dsp.gcc
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.domain.DirectionGate
import com.soundsight.domain.Side
import com.soundsight.ui.settings.illustration.MicCalibrationIllustration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private const val TAG = "DirectionCalibration"

/**
 * The exact display rotation RIGHT NOW - the value stored with a successful calibration so
 * analysis can refuse any other mounting (A6). A 180-degree remount silently reverses
 * bike-relative LEFT/RIGHT; binding polarity to the rotation is what makes that detectable.
 */
internal fun currentDisplayRotation(context: Context): Int {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            context.display?.rotation ?: Surface.ROTATION_0
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    } else {
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }
}

/** The two rotations StereoAnalyzer will consent to analyse at. Everything else abstains. */
internal fun isLandscapeRotation(rotation: Int): Boolean =
    rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

/**
 * The live display rotation, updated the moment the system reports one.
 *
 * A 180-degree turn WITHIN landscape produces no Configuration change: same orientation, same
 * width and height, so the activity is not recreated and nothing in the composition recomposes.
 * It is also the single movement that inverts the polarity being measured here. DisplayManager
 * is the only source that reports it, which is why this screen listens rather than reading
 * [LocalConfiguration].
 */
@Composable
internal fun rememberDisplayRotation(context: Context): State<Int> {
    val rotation = remember(context) { mutableIntStateOf(currentDisplayRotation(context)) }
    DisposableEffect(context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                rotation.intValue = currentDisplayRotation(context)
            }
        }
        // Null handler = callbacks on this (main) thread, so an abort is one recomposition away.
        displayManager?.registerDisplayListener(listener, null)
        rotation.intValue = currentDisplayRotation(context)
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }
    return rotation
}

/**
 * How long one step waits for a capture before giving up.
 *
 * Generous on purpose: the detector enforces [ListenerService.SPIKE_COOLDOWN_MS] between
 * captures and a capture only lands one post-roll (1.2 s) after the shout that triggered it.
 * The Listen button is disabled for the cooldown, so this timer only has to cover a rider
 * taking their time.
 */
private const val LISTEN_TIMEOUT_MS = 12_000L

/**
 * Correlation floor for accepting ONE calibration capture. Deliberately stricter than
 * [DirectionGate.CORR_MEDIUM] (0.3): a polarity decision is written once and then trusted by
 * every later ride, so it is worth refusing a capture the live path would have shown.
 */
private const val CALIBRATION_MIN_CORRELATION = 0.6

private data class CalibrationCaptureResult(
    val lagSamples: Double,
    val normalizedCorrelation: Double,
    val snrDb: Double
)

internal enum class CalibrationStepState {
    STEP_1_LEFT,
    STEP_2_RIGHT,
    FINISHED_SUCCESS,
    FINISHED_UNSUPPORTED
}

/**
 * Which side of the SCREEN the rider must stand on for [step], or null when [rotation] is not
 * one of the two landscape rotations - in portrait there is no answer and this screen must not
 * invent one.
 *
 * THE ANSWER IS THE SAME AT BOTH LANDSCAPE ROTATIONS, AND THAT IS DELIBERATE.
 *
 * Follow what step 1 actually decides. Its lag goes through [calibrationChannelsSwapped] into
 * `channelsSwapped`; [DirectionGate.evaluate] applies that boolean to the raw sign; and
 * DirectionIndicator draws the resulting [Side.LEFT] as a left-pointing chevron in the HUD's
 * left slot - the left of the same screen the rider is reading right now. So whichever side the
 * rider stood on during step 1 is the side every later arrow will call LEFT. Point the step-1
 * arrow anywhere but screen-left and every arrow this app ever draws is mirrored.
 *
 * The physical microphone feeding the stereo stream's left channel DOES sit on the opposite
 * screen edge at ROTATION_270 from where it sits at ROTATION_90 - the mics are bolted to the
 * chassis and the chassis turned over. That flip is real, and it is absorbed exactly once, by
 * `channelsSwapped`: the same shout from screen-left yields opposite lag signs at the two
 * rotations, so the two rotations record opposite polarities and both end up correct. Mirroring
 * the ARROW as well would apply the same flip a second time and hand back a calibration that is
 * confidently backwards - which is worse than none. [rotation] is taken here so the landscape
 * precondition lives in one place and so that reasoning is pinned by a test table instead of
 * being left to the next reader to rediscover.
 *
 * `DirectionCalibrationArrowTest` asserts the whole round trip, arrow -> polarity -> gate.
 */
internal fun calibrationStandSide(step: CalibrationStepState, rotation: Int): Side? {
    if (!isLandscapeRotation(rotation)) return null
    return when (step) {
        CalibrationStepState.STEP_1_LEFT -> Side.LEFT
        CalibrationStepState.STEP_2_RIGHT -> Side.RIGHT
        CalibrationStepState.FINISHED_SUCCESS,
        CalibrationStepState.FINISHED_UNSUPPORTED -> null
    }
}

/**
 * The `channelsSwapped` value implied by step 1's measured lag.
 *
 * Step 1 was shouted from the side [calibrationStandSide] pointed at, i.e. screen-left. The gate
 * maps a positive lag to [Side.LEFT] unswapped, so a negative step-1 lag means this handset's
 * channels are the other way round and the mapping has to be inverted.
 */
internal fun calibrationChannelsSwapped(step1LagSamples: Double): Boolean = step1LagSamples < 0.0

/**
 * What a display-rotation change during calibration must do: [restartFromStep1] discards the take
 * in flight and both stored readings, [explainToRider] says whether the rider lost something and
 * is owed a reason.
 *
 * IT RESTARTS THE WHOLE PAIR, NOT JUST THE STEP. Both readings have to describe the same
 * mounting - the polarity is stored against ONE rotation - so a step-1 lag measured at
 * ROTATION_90 paired with a step-2 lag measured at ROTATION_270 describes neither. Aborting only
 * the current take would leave exactly that pair reachable by turning the phone between steps.
 *
 * A rider still lining up a fresh step 1 is not scolded for turning the phone into position:
 * nothing has been captured, so there is nothing to explain.
 */
internal data class RotationChangeAction(
    val restartFromStep1: Boolean,
    val explainToRider: Boolean
) {
    companion object {
        val NONE = RotationChangeAction(restartFromStep1 = false, explainToRider = false)
    }
}

internal fun rotationChangeAction(
    previousRotation: Int,
    newRotation: Int,
    step: CalibrationStepState,
    isCapturing: Boolean
): RotationChangeAction {
    if (previousRotation == newRotation) return RotationChangeAction.NONE
    val midCalibration = step == CalibrationStepState.STEP_1_LEFT ||
        step == CalibrationStepState.STEP_2_RIGHT
    if (!midCalibration) return RotationChangeAction.NONE
    return RotationChangeAction(
        restartFromStep1 = true,
        explainToRider = isCapturing || step == CalibrationStepState.STEP_2_RIGHT
    )
}


@Composable
fun DirectionCalibrationScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit = onBack,
    onReturnHome: () -> Unit = onBack
) {
    val context = LocalContext.current
    val strings = SoundSightStrings.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val isRunning by ListenerService.isRunning.collectAsStateWithLifecycle()
    val captureMode by ListenerService.captureMode.collectAsStateWithLifecycle()

    val isStereoMode = (captureMode == CaptureMode.STEREO_48K_UNPROCESSED || captureMode == CaptureMode.STEREO_48K_PROCESSED)
    val isReady = isRunning && isStereoMode

    // THE LANDSCAPE GATE, UP FRONT.
    //
    // Both halves have to hold. The configuration says whether the UI is being laid out
    // landscape, which is what makes the instructions legible in a mount; the display rotation
    // says whether it is one of the two rotations StereoAnalyzer will ever analyse at. A capture
    // taken outside them ended in FINISHED_UNSUPPORTED - correct, but only after the rider had
    // already shouted twice for nothing.
    val configuration = LocalConfiguration.current
    val displayRotation by rememberDisplayRotation(context)
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        isLandscapeRotation(displayRotation)

    var stepState by remember { mutableStateOf(CalibrationStepState.STEP_1_LEFT) }
    var isListening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var leftLag by remember { mutableDoubleStateOf(0.0) }
    var rightLag by remember { mutableDoubleStateOf(0.0) }
    var leftCorr by remember { mutableDoubleStateOf(0.0) }
    var rightCorr by remember { mutableDoubleStateOf(0.0) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    var unsupportedMessage by remember { mutableStateOf<String?>(null) }

    // THE DETECTOR IS DEAF FOR A FEW SECONDS AFTER EVERY CAPTURE.
    //
    // ListenerService enforces SPIKE_COOLDOWN_MS between spike captures. Step 1's shout starts
    // that window; its clip arrives one post-roll (1.2 s) later, which is the moment this screen
    // advances to step 2 - so the rider was being shown "shout now" while roughly 2.8 s of
    // cooldown was still running, and a shout in that window produced no capture, no feedback and
    // eventually "did not hear sound". Step 1 never hit this because it follows an idle screen,
    // which is exactly why the second step looked unreliable and the first one never did.
    val timeSinceLastSpikeMs by ListenerService.timeSinceLastSpikeMs.collectAsStateWithLifecycle()
    val cooldownRemainingMs = if (timeSinceLastSpikeMs < 0L) {
        0L
    } else {
        (ListenerService.SPIKE_COOLDOWN_MS - timeSinceLastSpikeMs).coerceAtLeast(0L)
    }
    val cooldownSeconds = ((cooldownRemainingMs + 999L) / 1000L).toInt()
    val canListen = !isListening && cooldownRemainingMs == 0L && isLandscape

    fun listenButtonLabel(): String = when {
        cooldownRemainingMs > 0L ->
            String.format(java.util.Locale.US, strings.directionCalibrationCooldown, cooldownSeconds)
        errorMessage != null -> strings.directionCalibrationRetryBtn
        else -> strings.directionCalibrationListenBtn
    }

    fun analyzeStereoClip(clip: StereoClip): CalibrationCaptureResult? {
        // Same window contract as the live analyser (A7): the clip must carry a full pre-roll
        // and an honest trigger index, or it is not calibration input at all. A capture that
        // cannot state its alignment must not have a polarity measured through it.
        val frameCount = minOf(clip.left.size, clip.right.size)
        val window = DirectionWindow.resolve(
            frameCount = frameCount,
            preRollFrameCount = clip.preRollFrameCount,
            triggerFrameIndex = clip.triggerFrameIndex
        ) ?: return null

        val leftNoiseRef = DoubleArray(window.noiseRefEnd) { clip.left[it] / 32768.0 }
        val rightNoiseRef = DoubleArray(window.noiseRefEnd) { clip.right[it] / 32768.0 }
        val analysisLen = window.analysisEnd - window.analysisStart
        val leftAnalysis = DoubleArray(analysisLen) {
            clip.left[window.analysisStart + it] / 32768.0
        }
        val rightAnalysis = DoubleArray(analysisLen) {
            clip.right[window.analysisStart + it] / 32768.0
        }

        // SNR ON THE WORSE CHANNEL, exactly as StereoAnalyzer does it.
        //
        // This used to read the `left` array in BOTH steps. That is not a symmetric measurement:
        // in whichever step the source stands on the far side of the phone, `left` is the
        // shadowed microphone, so that step reported a systematically lower SNR and fell to
        // COHERENCE weighting far more often than the other one. The two sides were therefore
        // being measured with DIFFERENT estimators, and the second step - always the same one on
        // a given handset - failed intermittently for a reason that had nothing to do with the
        // rider. Taking min(L, R) makes the choice depend on the capture's quality instead of on
        // which side the sound came from.
        val leftAnalysisRms = bandRms(leftAnalysis, clip.sampleRate, 150.0, 1000.0)
        val rightAnalysisRms = bandRms(rightAnalysis, clip.sampleRate, 150.0, 1000.0)
        val leftNoiseRms = bandRms(leftNoiseRef, clip.sampleRate, 150.0, 1000.0)
        val rightNoiseRms = bandRms(rightNoiseRef, clip.sampleRate, 150.0, 1000.0)
        val leftSnrDb = 20.0 * log10(leftAnalysisRms / max(leftNoiseRms, 1e-12))
        val rightSnrDb = 20.0 * log10(rightAnalysisRms / max(rightNoiseRms, 1e-12))
        val snrDb = min(leftSnrDb, rightSnrDb)

        // Calibration keeps its own weighting selector deliberately (A10): this screen accepts
        // on a stricter correlation rule and is not governed by DirectionGate's display path.
        // The selector itself is unchanged; what changed is that its input is now side-independent.
        val weighting = if (snrDb >= 6.0) Weighting.PHAT else Weighting.COHERENCE

        val result = gcc(
            left = leftAnalysis,
            right = rightAnalysis,
            sampleRate = clip.sampleRate,
            lowHz = 150.0,
            highHz = 1000.0,
            maxLagSamples = 32,
            weighting = weighting
        )
        return CalibrationCaptureResult(
            lagSamples = result.lagSamples,
            normalizedCorrelation = result.normalizedCorrelation,
            snrDb = snrDb
        )
    }

    /**
     * Why ONE capture is not usable, or null when it is.
     *
     * Applied identically to both steps, which is the whole point. Step 1 used to accept any
     * lag at all as long as the correlation cleared the floor, so a physically impossible or a
     * near-zero left reading sailed through and the WHOLE decision then landed on step 2 - which
     * reported it as "this phone cannot do direction", blamed the right-hand shout, and offered
     * no retry. Worse, a step-1 lag beyond [DirectionGate.LAG_PHYSICAL_MAX] made step 2
     * impossible to pass no matter how good the second capture was, while the UI only ever let
     * the rider redo the second one.
     *
     * The two limits are DirectionGate's existing unchanged constants, used here for the same
     * reason the live path uses them: past LAG_PHYSICAL_MAX the reading cannot be a wavefront
     * across this chassis, and inside LAG_AMBIGUOUS_MIN it is too close to the perpendicular
     * plane to carry a side at all.
     */
    fun rejectionReasonFor(result: CalibrationCaptureResult): String? = when {
        result.normalizedCorrelation < CALIBRATION_MIN_CORRELATION ->
            strings.directionCalibrationLowConfidence
        abs(result.lagSamples) > DirectionGate.LAG_PHYSICAL_MAX ->
            strings.directionCalibrationLagImplausible
        abs(result.lagSamples) <= DirectionGate.LAG_AMBIGUOUS_MIN ->
            strings.directionCalibrationLagAmbiguous
        else -> null
    }

    fun restartCalibration() {
        listenJob?.cancel()
        listenJob = null
        isListening = false
        errorMessage = null
        leftLag = 0.0
        rightLag = 0.0
        leftCorr = 0.0
        rightCorr = 0.0
        stepState = CalibrationStepState.STEP_1_LEFT
    }

    fun startListeningForStep(isLeftStep: Boolean) {
        listenJob?.cancel()
        isListening = true
        errorMessage = null

        listenJob = scope.launch {
            val clip = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                ListenerService.stereoClips.first()
            }

            isListening = false

            if (clip == null) {
                errorMessage = strings.directionCalibrationTimeout
                return@launch
            }

            val result = analyzeStereoClip(clip)
            if (result == null) {
                // The clip existed but its window could not be resolved - almost always a
                // partial stereo pre-roll because the recorder had only just started. Saying
                // "did not hear sound" for this was a lie that sent riders off to shout louder.
                Log.i(TAG, "Calibration capture rejected: unusable window")
                errorMessage = strings.directionCalibrationUnusableCapture
                return@launch
            }

            Log.i(
                TAG,
                "Calibration capture for ${if (isLeftStep) "LEFT" else "RIGHT"}: " +
                    "lag=${result.lagSamples} normCorr=${result.normalizedCorrelation} snrDb=${result.snrDb}"
            )

            val rejection = rejectionReasonFor(result)
            if (rejection != null) {
                // Retry THIS step. Every per-capture problem is now reported on the step that
                // produced it, so the rider fixes the capture that was actually bad.
                errorMessage = rejection
                return@launch
            }

            if (isLeftStep) {
                leftLag = result.lagSamples
                leftCorr = result.normalizedCorrelation
                errorMessage = null
                stepState = CalibrationStepState.STEP_2_RIGHT
                return@launch
            }

            rightLag = result.lagSamples
            rightCorr = result.normalizedCorrelation
            errorMessage = null

            // Both captures already cleared the physical and ambiguity limits above, so the only
            // question left is whether they disagree about the side - which is the one thing this
            // screen exists to measure.
            val oppositeSign = (leftLag > 0 && rightLag < 0) || (leftLag < 0 && rightLag > 0)
            if (!oppositeSign) {
                Log.i(TAG, "Calibration refused: same sign (left=$leftLag right=$rightLag)")
                OnboardingStore.setDirectionDisabled(context, true)
                unsupportedMessage = strings.directionCalibrationSameSide
                stepState = CalibrationStepState.FINISHED_UNSUPPORTED
                return@launch
            }

            val rotation = currentDisplayRotation(context)
            if (!isLandscapeRotation(rotation)) {
                // A portrait (or unknown-rotation) mount can never satisfy the analyser's
                // landscape requirement - recording it would leave a "usable-looking" pair that
                // matches nothing. Fail unsupported WITHOUT leaving a rotation/polarity pair.
                Log.i(TAG, "Calibration refused: not in landscape (rotation=$rotation)")
                OnboardingStore.setDirectionDisabled(context, true)
                unsupportedMessage = strings.directionCalibrationNeedLandscape
                stepState = CalibrationStepState.FINISHED_UNSUPPORTED
                return@launch
            }

            // Polarity and the rotation it was measured under are stored in ONE edit (A6): a
            // reader must never see one without the other. The live analyser will analyse only
            // at exactly this rotation; any other mounting abstains until the rider recalibrates
            // there.
            val swapped = calibrationChannelsSwapped(leftLag)
            OnboardingStore.setChannelCalibration(context, swapped, rotation)
            OnboardingStore.setDirectionDisabled(context, false)
            unsupportedMessage = null
            stepState = CalibrationStepState.FINISHED_SUCCESS
        }
    }

    // A TURN DURING CALIBRATION VOIDS THE WHOLE PAIR, NOT JUST THE TAKE IN FLIGHT.
    //
    // Both readings have to describe the same mounting: the polarity is stored against ONE
    // rotation, and a step-1 lag measured at ROTATION_90 paired with a step-2 lag measured at
    // ROTATION_270 describes neither. So any change aborts the take, drops the buffer and goes
    // back to step 1 rather than merely re-running the current step. The listener fires on the
    // main thread, so the take dies one recomposition after the system reports the turn - the
    // clip collector is cancelled before it can hand a half-rotated capture to the correlator.
    //
    // A turn all the way into portrait never reaches here: no `configChanges` is declared, so
    // the activity is recreated and this whole composition - captures included - is discarded,
    // landing on the rotate prompt below.
    var lastRotation by remember { mutableIntStateOf(displayRotation) }
    LaunchedEffect(displayRotation) {
        val previous = lastRotation
        lastRotation = displayRotation
        val action = rotationChangeAction(
            previousRotation = previous,
            newRotation = displayRotation,
            step = stepState,
            isCapturing = isListening
        )
        if (!action.restartFromStep1) return@LaunchedEffect

        Log.i(
            TAG,
            "Rotation changed mid-calibration ($previous -> $displayRotation), " +
                "listening=$isListening step=$stepState; discarding capture"
        )
        restartCalibration()
        if (action.explainToRider) errorMessage = strings.directionCalibrationRotatedAway
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("direction_calibration_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("btn_calibration_back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = scheme.onSurface
                    )
                }

                Text(
                    text = strings.directionCalibrationTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.testTag("calibration_header_title")
                )

                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.testTag("btn_calibration_skip")
                ) {
                    Text(
                        text = strings.directionCalibrationSkipBtn,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            // Body. Everything below shares one weighted box so that a short landscape window
            // hands its whole remaining height to the illustration and the instruction, and
            // neither can push the other off screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (!isReady) {
                    NeedListenerBody(
                        message = strings.directionCalibrationNeedListener,
                        buttonLabel = strings.directionCalibrationReturnHome,
                        onReturnHome = onReturnHome
                    )
                } else if (!isLandscape) {
                    // Fine-tuning cannot start in portrait, and the rider learns that here
                    // rather than after two wasted shouts. The illustration shows the pose the
                    // rotation is for.
                    RotatePromptBody(
                        mirrored = stepState == CalibrationStepState.STEP_2_RIGHT,
                        title = strings.directionCalibrationRotateTitle,
                        body = strings.directionCalibrationRotateBody,
                        errorMessage = errorMessage,
                        disabledButtonLabel = strings.directionCalibrationListenBtn,
                        illustrationDescription = if (stepState == CalibrationStepState.STEP_2_RIGHT) {
                            strings.directionCalibrationStandRight
                        } else {
                            strings.directionCalibrationStandLeft
                        }
                    )
                } else {
                    when (stepState) {
                        CalibrationStepState.STEP_1_LEFT,
                        CalibrationStepState.STEP_2_RIGHT -> {
                            val isLeftStep = stepState == CalibrationStepState.STEP_1_LEFT
                            CaptureStepBody(
                                standSide = calibrationStandSide(stepState, displayRotation),
                                stepLabel = if (isLeftStep) {
                                    strings.directionCalibrationStep1Label
                                } else {
                                    strings.directionCalibrationStep2Label
                                },
                                instruction = if (isLeftStep) {
                                    strings.directionCalibrationStep1
                                } else {
                                    strings.directionCalibrationStep2
                                },
                                arrowDescription = if (isLeftStep) {
                                    strings.directionCalibrationStandLeft
                                } else {
                                    strings.directionCalibrationStandRight
                                },
                                isListening = isListening,
                                listeningLabel = strings.directionCalibrationListening,
                                errorMessage = errorMessage,
                                buttonLabel = listenButtonLabel(),
                                buttonEnabled = canListen,
                                buttonTestTag = if (isLeftStep) {
                                    "btn_calibration_listen_step1"
                                } else {
                                    "btn_calibration_listen_step2"
                                },
                                instructionTestTag = if (isLeftStep) {
                                    "calibration_step1_instruction"
                                } else {
                                    "calibration_step2_instruction"
                                },
                                onListen = { startListeningForStep(isLeftStep = isLeftStep) }
                            )
                        }

                        CalibrationStepState.FINISHED_SUCCESS -> {
                            OutcomeBody(
                                icon = Icons.Default.CheckCircle,
                                iconTint = scheme.primary,
                                message = strings.directionCalibrationSuccess,
                                messageTestTag = "calibration_success_text"
                            ) {
                                Button(
                                    onClick = onComplete,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = scheme.primary,
                                        contentColor = scheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("btn_calibration_done")
                                ) {
                                    Text(
                                        text = strings.directionCalibrationDoneBtn,
                                    )
                                }
                            }
                        }

                        CalibrationStepState.FINISHED_UNSUPPORTED -> {
                            OutcomeBody(
                                icon = Icons.Default.Warning,
                                iconTint = scheme.error,
                                // Says WHY, not just that it failed. The three ways to land here
                                // - both shouts read as the same side, a portrait mount, or a
                                // rider who stood on the wrong side - need three different things
                                // done about them, and only one of them is "your phone cannot do
                                // this".
                                message = unsupportedMessage ?: strings.directionCalibrationUnsupportedMsg,
                                messageTestTag = "calibration_unsupported_text"
                            ) {
                                // A dead end here was the most damaging part of the old flow: one
                                // bad capture wrote directionDisabled = true and left the rider
                                // with a single "Done" button, so the feature was off until they
                                // found their way back through Settings. Retrying is the common
                                // case, so it is the primary action.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { restartCalibration() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = scheme.primary,
                                            contentColor = scheme.onPrimary
                                        ),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(56.dp)
                                            .testTag("btn_calibration_retry_unsupported")
                                    ) {
                                        Text(
                                            text = strings.directionCalibrationRecalibrateBtn,
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onComplete,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(56.dp)
                                            .testTag("btn_calibration_done_unsupported")
                                    ) {
                                        Text(
                                            text = strings.directionCalibrationDoneBtn,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One capture step: the illustration of the pose beside the instruction it illustrates, with the
 * arrow on the side of the text the rider has to move to, so direction reads before the words do.
 *
 * The illustration is sized by HEIGHT and kept square, so on a short landscape window it shrinks
 * to whatever is left rather than pushing the instruction or the button out of view.
 */
@Composable
private fun CaptureStepBody(
    standSide: Side?,
    stepLabel: String,
    instruction: String,
    arrowDescription: String,
    isListening: Boolean,
    listeningLabel: String,
    errorMessage: String?,
    buttonLabel: String,
    buttonEnabled: Boolean,
    buttonTestTag: String,
    instructionTestTag: String,
    onListen: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MicCalibrationIllustration(
            mirrored = standSide == Side.RIGHT,
            contentDescription = arrowDescription,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
        )

        Spacer(modifier = Modifier.width(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (standSide == Side.LEFT) StandArrow(Side.LEFT, arrowDescription)
                Text(
                    text = instruction,
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(instructionTestTag)
                )
                if (standSide == Side.RIGHT) StandArrow(Side.RIGHT, arrowDescription)
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isListening) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = scheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = listeningLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.primary
                    )
                }
            } else if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.error,
                    modifier = Modifier.testTag("calibration_timeout_text")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onListen,
                enabled = buttonEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag(buttonTestTag)
            ) {
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/**
 * The arrow. Theme-coloured, not painted from the illustration's palette: this is a control the
 * rider acts on, and the drawing beside it is an asset. Its test tag names the SCREEN side it
 * points at - see [calibrationStandSide].
 */
@Composable
private fun StandArrow(side: Side, description: String) {
    Icon(
        imageVector = if (side == Side.LEFT) {
            Icons.Rounded.KeyboardDoubleArrowLeft
        } else {
            Icons.Rounded.KeyboardDoubleArrowRight
        },
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(44.dp)
            .testTag(if (side == Side.LEFT) "calibration_arrow_left" else "calibration_arrow_right")
    )
}

/** Portrait: the pose, why the phone has to turn, and a Listen button that will not start. */
@Composable
private fun RotatePromptBody(
    mirrored: Boolean,
    title: String,
    body: String,
    errorMessage: String?,
    disabledButtonLabel: String,
    illustrationDescription: String
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MicCalibrationIllustration(
            mirrored = mirrored,
            contentDescription = illustrationDescription,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ScreenRotation,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                modifier = Modifier.testTag("calibration_rotate_title")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("calibration_rotate_body")
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("calibration_timeout_text")
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {},
            enabled = false,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .testTag("btn_calibration_listen_disabled")
        ) {
            Text(
                text = disabledButtonLabel,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun NeedListenerBody(
    message: String,
    buttonLabel: String,
    onReturnHome: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("calibration_need_listener_text")
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onReturnHome,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("btn_return_home_from_calib")
        ) {
            Text(
                text = buttonLabel
            )
        }
    }
}

/** Success and unsupported share a shape: one mark, one sentence, the actions underneath. */
@Composable
private fun OutcomeBody(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    message: String,
    messageTestTag: String,
    actions: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(messageTestTag)
        )

        Spacer(modifier = Modifier.height(20.dp))

        actions()
    }
}
