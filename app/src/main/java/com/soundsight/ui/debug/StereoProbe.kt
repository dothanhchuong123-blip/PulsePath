package com.soundsight.ui.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface as DisplaySurface
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.soundsight.audio.MicGeometry
import com.soundsight.audio.MicGeometryProbe
import com.soundsight.audio.CaptureMode
import com.soundsight.audio.ListenerService
import com.soundsight.audio.StereoClip
import com.soundsight.audio.dsp.DirectionWindow
import com.soundsight.audio.dsp.Weighting
import com.soundsight.audio.dsp.bandRms
import com.soundsight.audio.dsp.gcc
import com.soundsight.core.OnboardingStore
import com.soundsight.core.SpeedSnapshot
import com.soundsight.data.live.LiveSession
import com.soundsight.domain.DirectionConfidence
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.DirectionGate
import com.soundsight.domain.Side
import com.soundsight.ui.onboarding.currentDisplayRotation
import com.soundsight.ui.theme.AccentLive
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.BgIdle
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.Degraded
import com.soundsight.ui.theme.FgIdle
import com.soundsight.ui.theme.HistoryRow
import com.soundsight.ui.theme.IdleStatus
import com.soundsight.ui.theme.WarningBg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class SourceProbeResult(
    val name: String,
    val sourceId: Int,
    val constructed: Boolean,
    val actualSource: Int = -1,
    val actualChannelCount: Int = -1,
    val actualSampleRate: Int = -1,
    val error: String? = null
)

data class BandTrendResult(
    val seriesDbfs: List<Double>,
    val riseDb: Double,
    val verdict: String
)

data class RecordingProbeResult(
    val selectedSource: String,
    val sampleRate: Int,
    val totalSamplesPerChannel: Int,
    val byteIdentical: Boolean,
    val normCorrLag0: Double,
    val bestBroadbandLag: Int,
    val maxBroadbandCorr: Double,
    val rmsLdBFS: Double,
    val rmsRdBFS: Double,
    val rmsLLowDb: Double,
    val rmsRLowDb: Double,
    val ildLowDb: Double,
    val rmsLHighDb: Double,
    val rmsRHighDb: Double,
    val ildHighDb: Double,
    val bestLowBandLag: Int,
    val maxLowBandCorr: Double,
    val lowBandTrend: BandTrendResult,
    val highBandTrend: BandTrendResult
)

internal val PART_B_SPEED_BUCKETS = listOf(0, 10, 15, 20, 25, 30, 40)
private const val PART_B_REQUIRED_REPEATS = 3
private const val PART_B_CAPTURE_TIMEOUT_MS = 8_000L
private const val PART_B_LOW_HZ = 150.0
private const val PART_B_HIGH_HZ = 1000.0
private const val PART_B_GCC_MAX_LAG = 32

internal enum class ProbeSignVerdict {
    CORRECT,
    INCORRECT,
    ZERO,
    UNAVAILABLE
}

internal enum class SequenceScenario {
    REPEAT_LEFT,
    REPEAT_RIGHT,
    ALTERNATING_SIDES,
    SOURCE_CROSSING
}

internal enum class AssociationLabel {
    SAME_SOURCE,
    DIFFERENT_SOURCE,
    AMBIGUOUS,
    UNLABELED
}

internal data class PartBCalibrationMapping(
    val channelsSwapped: Boolean,
    val rotation: Int
)

internal data class PartBCalibrationCheck(
    val mapping: PartBCalibrationMapping?,
    val failure: String?
)

internal fun resolvePartBCalibration(
    directionDisabled: Boolean,
    channelsSwappedPresent: Boolean,
    channelsSwapped: Boolean,
    savedRotation: Int?,
    currentRotation: Int
): PartBCalibrationCheck = when {
    directionDisabled -> PartBCalibrationCheck(
        mapping = null,
        failure = "Direction is disabled; complete calibration before Part B."
    )
    !channelsSwappedPresent || savedRotation == null -> PartBCalibrationCheck(
        mapping = null,
        failure = "Direction calibration is missing or legacy; recalibrate first."
    )
    currentRotation != DisplaySurface.ROTATION_90 &&
        currentRotation != DisplaySurface.ROTATION_270 -> PartBCalibrationCheck(
        mapping = null,
        failure = "Landscape mount required for direction measurements."
    )
    savedRotation != currentRotation -> PartBCalibrationCheck(
        mapping = null,
        failure = "Mount rotation differs from calibration; return to the calibrated rotation."
    )
    else -> PartBCalibrationCheck(
        mapping = PartBCalibrationMapping(channelsSwapped, savedRotation),
        failure = null
    )
}

internal data class ProbeGccMetrics(
    val lagSamples: Double,
    val normalizedCorrelation: Double,
    val peakRatio: Double,
    val physicalPass: Boolean,
    val signVerdict: ProbeSignVerdict,
    val reachesMedium: Boolean
)

internal data class CurrentGateChecks(
    val windPass: Boolean,
    val physicalPass: Boolean,
    val ambiguityPass: Boolean,
    val snrPass: Boolean,
    val signAgreementPass: Boolean,
    val correlationPass: Boolean
) {
    val allPass: Boolean
        get() = windPass && physicalPass && ambiguityPass && snrPass &&
            signAgreementPass && correlationPass
}

internal data class PartBClipAnalysis(
    val leftLowBandRmsDbFs: Double,
    val rightLowBandRmsDbFs: Double,
    val leftSnrDb: Double,
    val rightSnrDb: Double,
    val productionSnrDb: Double,
    val phat: ProbeGccMetrics,
    val coherence: ProbeGccMetrics,
    val signAgreement: Int,
    val gateChecks: CurrentGateChecks,
    val estimate: DirectionEstimate
)

internal data class B1B2CaptureRow(
    val captureId: String,
    val capturedAtMs: Long,
    val captureMode: CaptureMode,
    val speedKmh: Int,
    val knownSide: Side,
    /**
     * Measured GNSS speed at the capture instant, with its accuracy and fix age, plus the IMU
     * moving vote. Null speed means no usable fix at capture time - recorded as missing and
     * excluded from bucket analysis (a capture whose speed is unknown is not evidence about a
     * speed bucket), never back-filled from the operator's bucket selection.
     */
    val speedKmhMeasured: Float? = null,
    val gnssAccuracyM: Float? = null,
    val gnssFixAgeMs: Long? = null,
    val imuMovingVote: Boolean? = null,
    val leftLowBandRmsDbFs: Double,
    val rightLowBandRmsDbFs: Double,
    val leftSnrDb: Double,
    val rightSnrDb: Double,
    val productionSnrDb: Double,
    val phat: ProbeGccMetrics,
    val coherence: ProbeGccMetrics,
    val signAgreement: Int,
    val gateChecks: CurrentGateChecks,
    val estimate: DirectionEstimate
)

internal data class B3SequenceRow(
    val captureId: String,
    val capturedAtMs: Long,
    val scenario: SequenceScenario,
    val classificationFamily: String = "UNAVAILABLE",
    val singleShotVerdict: String,
    val associationLabel: AssociationLabel = AssociationLabel.UNLABELED,
    val sourceGroupId: String = "",
    val notes: String = ""
)

internal data class PartBProbeState(
    val b1b2Rows: List<B1B2CaptureRow> = emptyList(),
    val b3Rows: List<B3SequenceRow> = emptyList(),
    val captureInProgress: Boolean = false,
    val lastError: String? = null
)

/**
 * In-memory ride-session result store. The process singleton below owns one instance, so rows do
 * not disappear when the diagnostics route leaves composition and is opened again. Raw PCM never
 * enters this store: only the immutable measurements derived from a capture do.
 */
internal class PartBProbeStore {
    private val lock = Any()
    private val _state = MutableStateFlow(PartBProbeState())
    val state: StateFlow<PartBProbeState> = _state.asStateFlow()

    private val cachedClassifications = LinkedHashMap<Long, String>()
    private val captureSequence = AtomicLong(0L)

    fun nextCaptureId(prefix: String): String =
        "$prefix-${String.format(Locale.US, "%04d", captureSequence.incrementAndGet())}"

    fun beginCapture(): Boolean = synchronized(lock) {
        if (_state.value.captureInProgress) return@synchronized false
        _state.value = _state.value.copy(captureInProgress = true, lastError = null)
        true
    }

    fun finishCapture(error: String? = null) = synchronized(lock) {
        _state.value = _state.value.copy(captureInProgress = false, lastError = error)
    }

    fun appendB1B2(row: B1B2CaptureRow) = synchronized(lock) {
        _state.value = _state.value.copy(b1b2Rows = _state.value.b1b2Rows + row)
    }

    fun appendB3(row: B3SequenceRow) = synchronized(lock) {
        val family = cachedClassifications.remove(row.capturedAtMs) ?: row.classificationFamily
        _state.value = _state.value.copy(
            b3Rows = (_state.value.b3Rows + row.copy(classificationFamily = family))
                .sortedWith(compareBy<B3SequenceRow> { it.capturedAtMs }.thenBy { it.captureId })
        )
    }

    fun recordClassification(capturedAtMs: Long, family: String) = synchronized(lock) {
        var matched = false
        val updated = _state.value.b3Rows.map { row ->
            if (row.capturedAtMs == capturedAtMs) {
                matched = true
                row.copy(classificationFamily = family)
            } else {
                row
            }
        }
        if (matched) {
            _state.value = _state.value.copy(b3Rows = updated)
        } else {
            cachedClassifications[capturedAtMs] = family
            while (cachedClassifications.size > 32) {
                val oldest = cachedClassifications.keys.firstOrNull() ?: break
                cachedClassifications.remove(oldest)
            }
        }
    }

    fun setAssociation(captureId: String, association: AssociationLabel) = synchronized(lock) {
        _state.value = _state.value.copy(
            b3Rows = _state.value.b3Rows.map { row ->
                if (row.captureId == captureId) row.copy(associationLabel = association) else row
            }
        )
    }

    fun setSourceGroupId(captureId: String, sourceGroupId: String) = synchronized(lock) {
        val normalized = normalizeSourceGroupId(sourceGroupId)
        _state.value = _state.value.copy(
            b3Rows = _state.value.b3Rows.map { row ->
                if (row.captureId == captureId) row.copy(sourceGroupId = normalized) else row
            }
        )
    }

    fun clearB1B2() = synchronized(lock) {
        _state.value = _state.value.copy(b1b2Rows = emptyList())
    }

    fun clearB3() = synchronized(lock) {
        _state.value = _state.value.copy(b3Rows = emptyList())
    }
}

internal fun normalizeSourceGroupId(raw: String): String =
    raw.trim().uppercase(Locale.US).take(40)

/** Process-lifetime coordinator for pending captures and late classification results. */
internal object PartBProbeSession {
    val store = PartBProbeStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val classificationCollectorStarted = AtomicBoolean(false)

    fun ensureClassificationCollector() {
        if (!classificationCollectorStarted.compareAndSet(false, true)) return
        scope.launch {
            LiveSession.events.collect { event ->
                store.recordClassification(event.capturedAtMs, event.result.soundId)
            }
        }
    }

    fun launchCapture(block: suspend () -> Unit) {
        if (!store.beginCapture()) return
        scope.launch {
            try {
                block()
                store.finishCapture()
            } catch (t: Throwable) {
                store.finishCapture(t.message ?: t::class.java.simpleName)
            }
        }
    }
}

private suspend fun readCurrentPartBCalibration(context: Context): PartBCalibrationCheck {
    val directionDisabled = OnboardingStore.directionDisabledFlow(context).first()
    val calibration = OnboardingStore.directionCalibrationStateFlow(context).first()
    return resolvePartBCalibration(
        directionDisabled = directionDisabled,
        channelsSwappedPresent = calibration.channelsSwappedPresent,
        channelsSwapped = calibration.channelsSwapped,
        savedRotation = calibration.calibrationRotation,
        currentRotation = currentDisplayRotation(context)
    )
}

internal fun hasForcedCaptureProvenance(clip: StereoClip): Boolean =
    clip.preRollFrameCount == DirectionWindow.EXPECTED_PRE_ROLL_FRAMES &&
        clip.triggerFrameIndex == clip.preRollFrameCount

internal fun isRequestedForcedClip(clip: StereoClip, requestAtMs: Long): Boolean =
    clip.capturedAtMs >= requestAtMs && hasForcedCaptureProvenance(clip)

internal fun isProductionAlignedCaptureContext(
    clip: StereoClip,
    requestedMapping: PartBCalibrationMapping,
    finalMapping: PartBCalibrationMapping?,
    requestedMode: CaptureMode,
    finalMode: CaptureMode,
    serviceRunning: Boolean
): Boolean = hasForcedCaptureProvenance(clip) &&
    serviceRunning &&
    requestedMode != CaptureMode.MONO_16K_FALLBACK &&
    finalMode == requestedMode &&
    finalMapping == requestedMapping

internal fun productionAlignedEstimate(
    candidate: DirectionEstimate,
    clip: StereoClip,
    requestedMapping: PartBCalibrationMapping,
    finalMapping: PartBCalibrationMapping?,
    requestedMode: CaptureMode,
    finalMode: CaptureMode,
    serviceRunning: Boolean
): DirectionEstimate = if (
    isProductionAlignedCaptureContext(
        clip = clip,
        requestedMapping = requestedMapping,
        finalMapping = finalMapping,
        requestedMode = requestedMode,
        finalMode = finalMode,
        serviceRunning = serviceRunning
    )
) {
    candidate
} else {
    DirectionEstimate.UNKNOWN
}

private suspend fun forceAndAwaitProductionStereoClip(): StereoClip {
    val requestAtMs = System.currentTimeMillis()
    return withTimeout(PART_B_CAPTURE_TIMEOUT_MS) {
        coroutineScope {
            // SharedFlow has no replay. Start the collector synchronously before forceCapture(),
            // otherwise a fast finalisation can emit between the call and subscription.
            val awaited = async(start = CoroutineStart.UNDISPATCHED) {
                ListenerService.stereoClips.first { isRequestedForcedClip(it, requestAtMs) }
            }
            ListenerService.forceCapture()
            awaited.await()
        }
    }
}

internal fun analyzePartBClip(
    clip: StereoClip,
    channelsSwapped: Boolean,
    knownSide: Side?
): PartBClipAnalysis? {
    val frameCount = min(clip.left.size, clip.right.size)
    val window = DirectionWindow.resolve(
        frameCount = frameCount,
        preRollFrameCount = clip.preRollFrameCount,
        triggerFrameIndex = clip.triggerFrameIndex
    ) ?: return null

    val leftNoiseRef = DoubleArray(window.noiseRefEnd) { clip.left[it] / 32768.0 }
    val rightNoiseRef = DoubleArray(window.noiseRefEnd) { clip.right[it] / 32768.0 }
    val analysisLength = window.analysisEnd - window.analysisStart
    val leftAnalysis = DoubleArray(analysisLength) {
        clip.left[window.analysisStart + it] / 32768.0
    }
    val rightAnalysis = DoubleArray(analysisLength) {
        clip.right[window.analysisStart + it] / 32768.0
    }

    val leftAnalysisRms = bandRms(leftAnalysis, clip.sampleRate, PART_B_LOW_HZ, PART_B_HIGH_HZ)
    val rightAnalysisRms = bandRms(rightAnalysis, clip.sampleRate, PART_B_LOW_HZ, PART_B_HIGH_HZ)
    val leftNoiseRms = bandRms(leftNoiseRef, clip.sampleRate, PART_B_LOW_HZ, PART_B_HIGH_HZ)
    val rightNoiseRms = bandRms(rightNoiseRef, clip.sampleRate, PART_B_LOW_HZ, PART_B_HIGH_HZ)
    val leftRmsDbFs = com.soundsight.audio.dsp.toDbFs(leftAnalysisRms)
    val rightRmsDbFs = com.soundsight.audio.dsp.toDbFs(rightAnalysisRms)
    val bandRmsDbFs = max(leftRmsDbFs, rightRmsDbFs)
    val leftSnrDb = 20.0 * log10(leftAnalysisRms / max(leftNoiseRms, 1e-12))
    val rightSnrDb = 20.0 * log10(rightAnalysisRms / max(rightNoiseRms, 1e-12))
    val productionSnrDb = min(leftSnrDb, rightSnrDb)

    val phatResult = gcc(
        left = leftAnalysis,
        right = rightAnalysis,
        sampleRate = clip.sampleRate,
        lowHz = PART_B_LOW_HZ,
        highHz = PART_B_HIGH_HZ,
        maxLagSamples = PART_B_GCC_MAX_LAG,
        weighting = Weighting.PHAT
    )

    val subLength = analysisLength / 4
    var signAgreement = 0
    for (index in 0 until 4) {
        val start = index * subLength
        val end = (index + 1) * subLength
        val subLeft = leftAnalysis.copyOfRange(start, end)
        val subRight = rightAnalysis.copyOfRange(start, end)
        val subGcc = gcc(
            left = subLeft,
            right = subRight,
            sampleRate = clip.sampleRate,
            lowHz = PART_B_LOW_HZ,
            highHz = PART_B_HIGH_HZ,
            maxLagSamples = PART_B_GCC_MAX_LAG,
            weighting = Weighting.PHAT
        )
        val sameSign = (phatResult.lagSamples > 0.0 && subGcc.lagSamples > 0.0) ||
            (phatResult.lagSamples < 0.0 && subGcc.lagSamples < 0.0) ||
            (phatResult.lagSamples == 0.0 && subGcc.lagSamples == 0.0)
        if (!sameSign) continue

        val subLeftRms = bandRms(subLeft, clip.sampleRate, PART_B_LOW_HZ, PART_B_HIGH_HZ)
        val subRightRms = bandRms(subRight, clip.sampleRate, PART_B_LOW_HZ, PART_B_HIGH_HZ)
        val subRmsDbFs = max(
            com.soundsight.audio.dsp.toDbFs(subLeftRms),
            com.soundsight.audio.dsp.toDbFs(subRightRms)
        )
        val subLeftSnr = 20.0 * log10(subLeftRms / max(leftNoiseRms, 1e-12))
        val subRightSnr = 20.0 * log10(subRightRms / max(rightNoiseRms, 1e-12))
        if (
            DirectionWindow.isQualifiedVote(
                lagSamples = subGcc.lagSamples,
                normalizedCorrelation = subGcc.normalizedCorrelation,
                bandSnrDb = min(subLeftSnr, subRightSnr),
                bandRmsDbFs = subRmsDbFs,
                windowFrames = subLeft.size
            )
        ) {
            signAgreement++
        }
    }

    // This is the bounded B2 experiment only. StereoAnalyzer remains PHAT-only.
    val coherenceResult = gcc(
        left = leftAnalysis,
        right = rightAnalysis,
        sampleRate = clip.sampleRate,
        lowHz = PART_B_LOW_HZ,
        highHz = PART_B_HIGH_HZ,
        maxLagSamples = PART_B_GCC_MAX_LAG,
        weighting = Weighting.COHERENCE
    )

    val phatMetrics = phatResult.toProbeMetrics(channelsSwapped, knownSide)
    val coherenceMetrics = coherenceResult.toProbeMetrics(channelsSwapped, knownSide)
    val checks = CurrentGateChecks(
        windPass = bandRmsDbFs <= DirectionGate.WIND_RMS_DBFS_MAX,
        physicalPass = phatMetrics.physicalPass,
        ambiguityPass = abs(phatResult.lagSamples) > DirectionGate.LAG_AMBIGUOUS_MIN,
        snrPass = productionSnrDb >= DirectionGate.BAND_SNR_DB_MIN,
        signAgreementPass = signAgreement >= DirectionGate.SIGN_AGREEMENT_MIN,
        correlationPass = phatResult.normalizedCorrelation >= DirectionGate.CORR_MEDIUM
    )
    val estimate = DirectionGate.evaluate(
        itdLagSamples = phatResult.lagSamples,
        itdPeakRatio = phatResult.peakRatio,
        itdPeakCorrelation = phatResult.normalizedCorrelation,
        itdBandSnrDb = productionSnrDb,
        itdBandRmsDbFs = bandRmsDbFs,
        itdSignAgreement = signAgreement,
        channelsSwapped = channelsSwapped
    )

    return PartBClipAnalysis(
        leftLowBandRmsDbFs = leftRmsDbFs,
        rightLowBandRmsDbFs = rightRmsDbFs,
        leftSnrDb = leftSnrDb,
        rightSnrDb = rightSnrDb,
        productionSnrDb = productionSnrDb,
        phat = phatMetrics,
        coherence = coherenceMetrics,
        signAgreement = signAgreement,
        gateChecks = checks,
        estimate = estimate
    )
}

private fun com.soundsight.audio.dsp.GccResult.toProbeMetrics(
    channelsSwapped: Boolean,
    knownSide: Side?
): ProbeGccMetrics {
    val mappedSide = mappedSideForLag(lagSamples, channelsSwapped)
    val sign = when {
        knownSide == null -> ProbeSignVerdict.UNAVAILABLE
        mappedSide == null -> ProbeSignVerdict.ZERO
        mappedSide == knownSide -> ProbeSignVerdict.CORRECT
        else -> ProbeSignVerdict.INCORRECT
    }
    return ProbeGccMetrics(
        lagSamples = lagSamples,
        normalizedCorrelation = normalizedCorrelation,
        peakRatio = peakRatio,
        physicalPass = abs(lagSamples) <= DirectionGate.LAG_PHYSICAL_MAX,
        signVerdict = sign,
        reachesMedium = normalizedCorrelation >= DirectionGate.CORR_MEDIUM
    )
}

private fun mappedSideForLag(lagSamples: Double, channelsSwapped: Boolean): Side? {
    val raw = when {
        lagSamples > 0.0 -> Side.LEFT
        lagSamples < 0.0 -> Side.RIGHT
        else -> return null
    }
    return if (!channelsSwapped) raw else if (raw == Side.LEFT) Side.RIGHT else Side.LEFT
}

internal fun directionVerdict(estimate: DirectionEstimate): String =
    if (estimate.confidence == DirectionConfidence.UNKNOWN || estimate.side == null) {
        "UNKNOWN"
    } else {
        "${estimate.side.name}_${estimate.confidence.name}"
    }

internal fun highestObservedUsableSpeed(rows: List<B1B2CaptureRow>): String {
    val highest = PART_B_SPEED_BUCKETS.filter { speed ->
        Side.entries.all { side ->
            val sideRows = rows.filter { it.speedKmh == speed && it.knownSide == side }
            sideRows.size >= PART_B_REQUIRED_REPEATS &&
                sideRows.any { it.isCorrectGateEligible() } &&
                sideRows.none { it.isShownWrongSide() }
        }
    }.maxOrNull()
    return "highestObservedUsableSpeed=${highest ?: "NONE"}"
}

private fun B1B2CaptureRow.isCorrectGateEligible(): Boolean =
    gateChecks.allPass && estimate.confidence != DirectionConfidence.UNKNOWN &&
        estimate.side == knownSide && phat.signVerdict == ProbeSignVerdict.CORRECT

private fun B1B2CaptureRow.isShownWrongSide(): Boolean =
    estimate.confidence != DirectionConfidence.UNKNOWN && estimate.side != knownSide

internal const val COHERENCE_CLEARS = "COHERENCE_CLEARS_CURRENT_FLOOR_AT_RIDING_SPEED"
internal const val COHERENCE_DOES_NOT_CLEAR =
    "COHERENCE_DOES_NOT_CLEAR_CURRENT_FLOOR_AT_RIDING_SPEED"

internal fun coherenceAggregateVerdict(rows: List<B1B2CaptureRow>): String {
    val clears = rows.asSequence().map { it.speedKmh }.filter { it > 0 }.distinct().any { speed ->
        Side.entries.all { side ->
            rows.filter { it.speedKmh == speed && it.knownSide == side }.any { row ->
                !row.phat.reachesMedium && row.coherence.reachesMedium &&
                    row.coherence.physicalPass &&
                    row.coherence.signVerdict == ProbeSignVerdict.CORRECT
            }
        }
    }
    return if (clears) COHERENCE_CLEARS else COHERENCE_DOES_NOT_CLEAR
}

internal data class SequenceCounts(
    val sameSourceEligibleRepeatCount: Int,
    val ambiguousAssociationCount: Int
)

internal fun sequenceCounts(
    rows: List<B3SequenceRow>,
    scenario: SequenceScenario
): SequenceCounts {
    val scenarioRows = rows.filter { it.scenario == scenario }
    val eligibleSameSourceGroups = scenarioRows
        .filter {
            it.associationLabel == AssociationLabel.SAME_SOURCE &&
                it.sourceGroupId.isNotBlank() &&
                it.singleShotVerdict != "UNKNOWN"
        }
        .groupBy { normalizeSourceGroupId(it.sourceGroupId) }
        .values
        .filter { group -> group.size >= 2 }
    return SequenceCounts(
        sameSourceEligibleRepeatCount = eligibleSameSourceGroups.sumOf { it.size },
        ambiguousAssociationCount = scenarioRows.count {
            it.associationLabel == AssociationLabel.AMBIGUOUS
        }
    )
}

private fun csvEscape(value: Any?): String {
    val raw = (value?.toString() ?: "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "\r\n")
    return if (raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${raw.replace("\"", "\"\"")}\""
    } else {
        raw
    }
}

private fun csvLine(values: List<Any?>): String = values.joinToString(",", transform = ::csvEscape)
private fun csvDouble(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun passFail(pass: Boolean): String = if (pass) "PASS" else "FAIL"
private fun StringBuilder.appendCsvRecord(values: List<Any?>) {
    append(csvLine(values))
    append("\r\n")
}

internal fun b1Csv(rows: List<B1B2CaptureRow>): String {
    val verdict = highestObservedUsableSpeed(rows)
    val header = listOf(
        "captureId", "capturedAtMs", "captureMode", "speedKmh", "knownSide",
        "speedKmhMeasured", "gnssAccuracyM", "gnssFixAgeMs", "imuMovingVote",
        "leftLowBandRmsDbFs", "rightLowBandRmsDbFs", "leftSnrDb", "rightSnrDb",
        "productionSnrDb", "phatLagSamples", "phatNormalizedCorrelation",
        "physicalLagPass", "signCorrect", "windGatePass", "ambiguityGatePass",
        "snrGatePass", "signAgreement", "signAgreementGatePass", "correlationGatePass",
        "singleShotGateVerdict", "showOrAbstain", "aggregateVerdict"
    )
    return buildString {
        appendCsvRecord(header)
        rows.sortedWith(compareBy<B1B2CaptureRow> { it.capturedAtMs }.thenBy { it.captureId })
            .forEach { row ->
                appendCsvRecord(
                    listOf(
                        row.captureId, row.capturedAtMs, row.captureMode.name, row.speedKmh,
                        row.knownSide.name,
                        row.speedKmhMeasured?.let { csvDouble(it.toDouble()) } ?: "",
                        row.gnssAccuracyM?.let { csvDouble(it.toDouble()) } ?: "",
                        row.gnssFixAgeMs ?: "",
                        row.imuMovingVote?.toString() ?: "",
                        csvDouble(row.leftLowBandRmsDbFs),
                        csvDouble(row.rightLowBandRmsDbFs), csvDouble(row.leftSnrDb),
                        csvDouble(row.rightSnrDb), csvDouble(row.productionSnrDb),
                        csvDouble(row.phat.lagSamples), csvDouble(row.phat.normalizedCorrelation),
                        passFail(row.phat.physicalPass), row.phat.signVerdict.name,
                        passFail(row.gateChecks.windPass), passFail(row.gateChecks.ambiguityPass),
                        passFail(row.gateChecks.snrPass), row.signAgreement,
                        passFail(row.gateChecks.signAgreementPass),
                        passFail(row.gateChecks.correlationPass), directionVerdict(row.estimate),
                        if (row.estimate.confidence == DirectionConfidence.UNKNOWN) "ABSTAIN" else "SHOW",
                        verdict
                    )
                )
            }
    }
}

internal fun b2Csv(rows: List<B1B2CaptureRow>): String {
    val verdict = coherenceAggregateVerdict(rows)
    val header = listOf(
        "captureId", "capturedAtMs", "speedKmh", "knownSide",
        "phatLagSamples", "phatNormalizedCorrelation", "phatSign", "phatPhysicalPass",
        "phatReachesMedium", "coherenceLagSamples", "coherenceNormalizedCorrelation",
        "coherenceSign", "coherencePhysicalPass", "coherenceReachesMedium", "aggregateVerdict"
    )
    return buildString {
        appendCsvRecord(header)
        rows.sortedWith(compareBy<B1B2CaptureRow> { it.capturedAtMs }.thenBy { it.captureId })
            .forEach { row ->
                appendCsvRecord(
                    listOf(
                        row.captureId, row.capturedAtMs, row.speedKmh, row.knownSide.name,
                        csvDouble(row.phat.lagSamples), csvDouble(row.phat.normalizedCorrelation),
                        row.phat.signVerdict.name, passFail(row.phat.physicalPass),
                        passFail(row.phat.reachesMedium), csvDouble(row.coherence.lagSamples),
                        csvDouble(row.coherence.normalizedCorrelation),
                        row.coherence.signVerdict.name, passFail(row.coherence.physicalPass),
                        passFail(row.coherence.reachesMedium), verdict
                    )
                )
            }
    }
}

internal fun b3Csv(rows: List<B3SequenceRow>): String {
    val header = listOf(
        "captureId", "capturedAtMs", "scenario", "classificationFamily",
        "singleShotGateVerdict", "associationLabel", "sourceGroupId", "notes",
        "sameSourceEligibleRepeatCount", "ambiguousAssociationCount"
    )
    return buildString {
        appendCsvRecord(header)
        rows.sortedWith(compareBy<B3SequenceRow> { it.capturedAtMs }.thenBy { it.captureId })
            .forEach { row ->
                val counts = sequenceCounts(rows, row.scenario)
                appendCsvRecord(
                    listOf(
                        row.captureId, row.capturedAtMs, row.scenario.name,
                        row.classificationFamily, row.singleShotVerdict,
                        row.associationLabel.name, row.sourceGroupId, row.notes,
                        counts.sameSourceEligibleRepeatCount, counts.ambiguousAssociationCount
                    )
                )
            }
    }
}

internal fun allPartBCsv(state: PartBProbeState): String {
    val header = listOf(
        "track", "captureId", "capturedAtMs", "captureMode", "speedKmh", "knownSide",
        "speedKmhMeasured", "gnssAccuracyM", "gnssFixAgeMs", "imuMovingVote",
        "scenario", "classificationFamily", "associationLabel", "sourceGroupId",
        "leftLowBandRmsDbFs",
        "rightLowBandRmsDbFs", "leftSnrDb", "rightSnrDb", "productionSnrDb",
        "phatLagSamples", "phatNormalizedCorrelation", "phatPeakRatio", "phatPhysicalPass",
        "phatSign", "phatReachesMedium", "coherenceLagSamples",
        "coherenceNormalizedCorrelation", "coherencePeakRatio", "coherencePhysicalPass",
        "coherenceSign", "coherenceReachesMedium", "windGatePass", "physicalGatePass",
        "ambiguityGatePass", "snrGatePass", "signAgreement", "signAgreementGatePass",
        "correlationGatePass", "singleShotGateVerdict", "showOrAbstain",
        "aggregateVerdict", "sameSourceEligibleRepeatCount", "ambiguousAssociationCount", "notes"
    )
    val b1Verdict = highestObservedUsableSpeed(state.b1b2Rows)
    val b2Verdict = coherenceAggregateVerdict(state.b1b2Rows)
    return buildString {
        appendCsvRecord(header)
        state.b1b2Rows.sortedWith(compareBy<B1B2CaptureRow> { it.capturedAtMs }.thenBy { it.captureId })
            .forEach { row ->
                appendCsvRecord(
                    listOf(
                        "B1_B2", row.captureId, row.capturedAtMs, row.captureMode.name,
                        row.speedKmh, row.knownSide.name,
                        row.speedKmhMeasured?.let { csvDouble(it.toDouble()) } ?: "",
                        row.gnssAccuracyM?.let { csvDouble(it.toDouble()) } ?: "",
                        row.gnssFixAgeMs ?: "",
                        row.imuMovingVote?.toString() ?: "",
                        "", "", "", "",
                        csvDouble(row.leftLowBandRmsDbFs), csvDouble(row.rightLowBandRmsDbFs),
                        csvDouble(row.leftSnrDb), csvDouble(row.rightSnrDb),
                        csvDouble(row.productionSnrDb), csvDouble(row.phat.lagSamples),
                        csvDouble(row.phat.normalizedCorrelation), csvDouble(row.phat.peakRatio),
                        passFail(row.phat.physicalPass), row.phat.signVerdict.name,
                        passFail(row.phat.reachesMedium), csvDouble(row.coherence.lagSamples),
                        csvDouble(row.coherence.normalizedCorrelation),
                        csvDouble(row.coherence.peakRatio), passFail(row.coherence.physicalPass),
                        row.coherence.signVerdict.name, passFail(row.coherence.reachesMedium),
                        passFail(row.gateChecks.windPass), passFail(row.gateChecks.physicalPass),
                        passFail(row.gateChecks.ambiguityPass), passFail(row.gateChecks.snrPass),
                        row.signAgreement, passFail(row.gateChecks.signAgreementPass),
                        passFail(row.gateChecks.correlationPass), directionVerdict(row.estimate),
                        if (row.estimate.confidence == DirectionConfidence.UNKNOWN) "ABSTAIN" else "SHOW",
                        "$b1Verdict;$b2Verdict", "", "", ""
                    )
                )
            }
        state.b3Rows.sortedWith(compareBy<B3SequenceRow> { it.capturedAtMs }.thenBy { it.captureId })
            .forEach { row ->
                val counts = sequenceCounts(state.b3Rows, row.scenario)
                val values = MutableList<Any?>(header.size) { "" }
                values[0] = "B3"
                values[1] = row.captureId
                values[2] = row.capturedAtMs
                values[10] = row.scenario.name
                values[11] = row.classificationFamily
                values[12] = row.associationLabel.name
                values[13] = row.sourceGroupId
                values[38] = row.singleShotVerdict
                values[39] = if (row.singleShotVerdict == "UNKNOWN") "ABSTAIN" else "SHOW"
                values[41] = counts.sameSourceEligibleRepeatCount
                values[42] = counts.ambiguousAssociationCount
                values[43] = row.notes
                appendCsvRecord(values)
            }
    }
}

private fun metric(value: Double): String = String.format(Locale.US, "%.3f", value)
private fun gateMark(pass: Boolean): String = if (pass) "PASS" else "FAIL"

@Composable
private fun ProbeSelectionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(48.dp).testTag(testTag),
            colors = ButtonDefaults.buttonColors(containerColor = AccentLive, contentColor = BgIdle),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("✓ $label", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp).testTag(testTag),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(label, color = FgIdle, fontSize = 12.sp)
        }
    }
}

@Composable
private fun B1SpeedSweepCard(
    state: PartBProbeState,
    selectedSpeedKmh: Int?,
    onSelectSpeed: (Int) -> Unit,
    selectedSide: Side?,
    onSelectSide: (Side) -> Unit,
    captureReady: Boolean,
    calibrationStatus: String,
    captureMode: CaptureMode,
    onCapture: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    val rows = state.b1b2Rows
    val latest = rows.maxWithOrNull(compareBy<B1B2CaptureRow> { it.capturedAtMs }.thenBy { it.captureId })
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("part_b1_card")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "B1 — SPEED-SWEEP CARD",
                color = AccentLive,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                "Tag the intended speed and known bike-side before each production-shaped force " +
                    "capture. The GNSS-measured speed, accuracy, fix age and IMU moving vote are " +
                    "recorded on every row; a capture with no usable fix is marked, not guessed.",
                color = Degraded,
                fontSize = 12.sp
            )

            Text("SPEED BUCKET (km/h)", color = FgIdle, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            PART_B_SPEED_BUCKETS.chunked(4).forEach { bucketRow ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bucketRow.forEach { speed ->
                        ProbeSelectionButton(
                            label = speed.toString(),
                            selected = selectedSpeedKmh == speed,
                            onClick = { onSelectSpeed(speed) },
                            modifier = Modifier.weight(1f),
                            testTag = "part_b1_speed_$speed"
                        )
                    }
                    repeat(4 - bucketRow.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Text("KNOWN SIDE", color = FgIdle, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Side.entries.forEach { side ->
                    ProbeSelectionButton(
                        label = side.name,
                        selected = selectedSide == side,
                        onClick = { onSelectSide(side) },
                        modifier = Modifier.weight(1f),
                        testTag = "part_b1_side_${side.name}"
                    )
                }
            }

            Text(calibrationStatus, color = if (captureReady) AccentLive else WarningBg, fontSize = 11.sp)
            Text(
                "Listener: ${if (captureReady) "READY" else "NOT READY"} • mode=${captureMode.name}",
                color = if (captureReady) AccentLive else WarningBg,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )

            Button(
                onClick = onCapture,
                enabled = captureReady && selectedSpeedKmh != null && selectedSide != null,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("part_b1_capture"),
                colors = ButtonDefaults.buttonColors(containerColor = AccentLive, contentColor = BgIdle),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (state.captureInProgress) {
                    CircularProgressIndicator(
                        color = BgIdle,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Capturing / analyzing…", fontWeight = FontWeight.Bold)
                } else {
                    Text("Capture B1 + paired B2", fontWeight = FontWeight.Bold)
                }
            }

            state.lastError?.let { error ->
                Text("Capture error: $error", color = CriticalBg, fontSize = 12.sp)
            }

            Text(
                "Rows: ${rows.size} • ${highestObservedUsableSpeed(rows)}",
                color = FgIdle,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.testTag("part_b1_verdict")
            )
            Text(
                PART_B_SPEED_BUCKETS.joinToString("\n") { speed ->
                    val left = rows.count { it.speedKmh == speed && it.knownSide == Side.LEFT && it.isCorrectGateEligible() }
                    val leftTotal = rows.count { it.speedKmh == speed && it.knownSide == Side.LEFT }
                    val right = rows.count { it.speedKmh == speed && it.knownSide == Side.RIGHT && it.isCorrectGateEligible() }
                    val rightTotal = rows.count { it.speedKmh == speed && it.knownSide == Side.RIGHT }
                    "$speed km/h  LEFT=$left/$leftTotal  RIGHT=$right/$rightTotal"
                },
                color = Degraded,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )

            latest?.let { row ->
                HorizontalDivider(color = Color(0xFF2E3D4F))
                Text(
                    "LATEST ${row.captureId} @ ${row.capturedAtMs}\n" +
                        "speed measured=" +
                        (row.speedKmhMeasured?.let { metric(it.toDouble()) + " km/h" } ?: "NO FIX") +
                        " acc=" + (row.gnssAccuracyM?.let { metric(it.toDouble()) + " m" } ?: "-") +
                        " age=" + (row.gnssFixAgeMs?.toString() ?: "-") + " ms" +
                        " imuMoving=" + (row.imuMovingVote?.toString() ?: "-") + "\n" +
                        "RMS low L/R=${metric(row.leftLowBandRmsDbFs)}/${metric(row.rightLowBandRmsDbFs)} dBFS\n" +
                        "SNR L/R/min=${metric(row.leftSnrDb)}/${metric(row.rightSnrDb)}/${metric(row.productionSnrDb)} dB\n" +
                        "PHAT lag=${metric(row.phat.lagSamples)} corr=${metric(row.phat.normalizedCorrelation)} " +
                        "physical=${gateMark(row.phat.physicalPass)} sign=${row.phat.signVerdict.name}\n" +
                        "GATES wind=${gateMark(row.gateChecks.windPass)} " +
                        "physical=${gateMark(row.gateChecks.physicalPass)} " +
                        "ambiguity=${gateMark(row.gateChecks.ambiguityPass)} " +
                        "snr=${gateMark(row.gateChecks.snrPass)} " +
                        "sign=${row.signAgreement}/4:${gateMark(row.gateChecks.signAgreementPass)} " +
                        "corr=${gateMark(row.gateChecks.correlationPass)}\n" +
                        "VERDICT=${directionVerdict(row.estimate)} " +
                        if (row.estimate.confidence == DirectionConfidence.UNKNOWN) "ABSTAIN" else "SHOW",
                    color = FgIdle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("part_b1_latest_metrics")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("part_b1_copy_csv"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = FgIdle)
                ) { Text("Copy B1 CSV", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onClear,
                    enabled = rows.isNotEmpty() && !state.captureInProgress,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("part_b1_clear")
                ) { Text("Clear B1/B2", color = FgIdle, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun B2DualWeightingCard(
    state: PartBProbeState,
    onCopy: () -> Unit
) {
    val rows = state.b1b2Rows
    val latest = rows.maxWithOrNull(compareBy<B1B2CaptureRow> { it.capturedAtMs }.thenBy { it.captureId })
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("part_b2_card")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "B2 — DUAL-WEIGHTING CARD",
                color = AccentLive,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                "PHAT and COHERENCE run over the exact same B1 arrays and trigger-aligned window. " +
                    "COHERENCE remains probe-only.",
                color = Degraded,
                fontSize = 12.sp
            )
            Text(
                coherenceAggregateVerdict(rows),
                color = FgIdle,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.testTag("part_b2_verdict")
            )
            latest?.let { row ->
                Text(
                    "SAME CAPTURE ${row.captureId}\n" +
                        "PHAT      lag=${metric(row.phat.lagSamples)} " +
                        "corr=${metric(row.phat.normalizedCorrelation)} sign=${row.phat.signVerdict.name} " +
                        "physical=${gateMark(row.phat.physicalPass)} floor=${gateMark(row.phat.reachesMedium)}\n" +
                        "COHERENCE  lag=${metric(row.coherence.lagSamples)} " +
                        "corr=${metric(row.coherence.normalizedCorrelation)} sign=${row.coherence.signVerdict.name} " +
                        "physical=${gateMark(row.coherence.physicalPass)} floor=${gateMark(row.coherence.reachesMedium)}",
                    color = FgIdle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("part_b2_latest_metrics")
                )
            } ?: Text("No paired captures yet.", color = Degraded, fontSize = 12.sp)
            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("part_b2_copy_csv"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = FgIdle)
            ) { Text("Copy B2 CSV", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun B3SequenceCard(
    state: PartBProbeState,
    selectedScenario: SequenceScenario?,
    onSelectScenario: (SequenceScenario) -> Unit,
    captureReady: Boolean,
    onCapture: () -> Unit,
    onAssociation: (String, AssociationLabel) -> Unit,
    onSourceGroupId: (String, String) -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    val rows = state.b3Rows
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("part_b3_card")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "B3 — SEQUENCE CARD",
                color = AccentLive,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                "Select the scripted scenario before forceCapture(). Label source association " +
                    "and an explicit source group afterwards; no production tracker is implemented here.",
                color = Degraded,
                fontSize = 12.sp
            )

            SequenceScenario.entries.forEach { scenario ->
                ProbeSelectionButton(
                    label = scenario.name,
                    selected = selectedScenario == scenario,
                    onClick = { onSelectScenario(scenario) },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "part_b3_scenario_${scenario.name}"
                )
            }

            Button(
                onClick = onCapture,
                enabled = captureReady && selectedScenario != null,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("part_b3_capture"),
                colors = ButtonDefaults.buttonColors(containerColor = AccentLive, contentColor = BgIdle),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (state.captureInProgress) {
                    CircularProgressIndicator(
                        color = BgIdle,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Capturing / analyzing…", fontWeight = FontWeight.Bold)
                } else {
                    Text("Force B3 capture", fontWeight = FontWeight.Bold)
                }
            }

            state.lastError?.let { error ->
                Text("Capture error: $error", color = CriticalBg, fontSize = 12.sp)
            }

            SequenceScenario.entries.forEach { scenario ->
                val counts = sequenceCounts(rows, scenario)
                Text(
                    "${scenario.name}: sameSourceEligibleRepeatCount=${counts.sameSourceEligibleRepeatCount}, " +
                        "ambiguousAssociationCount=${counts.ambiguousAssociationCount}",
                    color = FgIdle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("part_b3_counts_${scenario.name}")
                )
            }

            rows.forEach { row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${row.captureId} • ${row.capturedAtMs}\n" +
                            "${row.scenario.name} • family=${row.classificationFamily}\n" +
                            "singleShot=${row.singleShotVerdict} • association=${row.associationLabel.name}\n" +
                            "sourceGroupId=${row.sourceGroupId.ifBlank { "UNSET" }}",
                        color = FgIdle,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    OutlinedTextField(
                        value = row.sourceGroupId,
                        onValueChange = { onSourceGroupId(row.captureId, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("part_b3_${row.captureId}_source_group"),
                        singleLine = true,
                        label = { Text("Source group ID (for example SOURCE_A)", fontSize = 10.sp) }
                    )
                    if (
                        row.associationLabel == AssociationLabel.SAME_SOURCE &&
                        row.sourceGroupId.isBlank()
                    ) {
                        Text(
                            "GROUP REQUIRED: SAME_SOURCE does not count until a source group ID is set.",
                            color = WarningBg,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    AssociationLabel.entries.chunked(2).forEach { labelRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            labelRow.forEach { association ->
                                ProbeSelectionButton(
                                    label = association.name,
                                    selected = row.associationLabel == association,
                                    onClick = { onAssociation(row.captureId, association) },
                                    modifier = Modifier.weight(1f),
                                    testTag = "part_b3_${row.captureId}_${association.name}"
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("part_b3_copy_csv"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = FgIdle)
                ) { Text("Copy B3 CSV", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onClear,
                    enabled = rows.isNotEmpty() && !state.captureInProgress,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("part_b3_clear")
                ) { Text("Clear B3", color = FgIdle, fontSize = 12.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StereoProbeScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val partBState by PartBProbeSession.store.state.collectAsState()
    val listenerRunning by ListenerService.isRunning.collectAsState()
    val listenerCaptureMode by ListenerService.captureMode.collectAsState()

    var unprocessedProp by remember { mutableStateOf("Checking...") }
    var sourceProbes by remember { mutableStateOf<List<SourceProbeResult>>(emptyList()) }
    var recordingResult by remember { mutableStateOf<RecordingProbeResult?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }
    var micGeometryResult by remember { mutableStateOf<MicGeometry?>(null) }
    var isProbingGeometry by remember { mutableStateOf(false) }
    var geometryError by remember { mutableStateOf<String?>(null) }
    var selectedSpeedKmh by remember { mutableStateOf<Int?>(null) }
    var selectedKnownSide by remember { mutableStateOf<Side?>(null) }
    var selectedScenario by remember { mutableStateOf<SequenceScenario?>(null) }
    var calibratedChannelsSwapped by remember { mutableStateOf<Boolean?>(null) }
    var calibratedRotation by remember { mutableStateOf<Int?>(null) }
    var calibrationStatus by remember { mutableStateOf("Checking direction calibration...") }

    fun copyCsv(label: String, csv: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, csv))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    val partBCaptureReady = listenerRunning &&
        listenerCaptureMode != CaptureMode.MONO_16K_FALLBACK &&
        calibratedChannelsSwapped != null &&
        !partBState.captureInProgress

    fun validatedMappingOrDisable(): PartBCalibrationMapping? {
        val swapped = calibratedChannelsSwapped ?: return null
        val savedRotation = calibratedRotation ?: return null
        val currentRotation = currentDisplayRotation(context)
        if (currentRotation != savedRotation) {
            calibratedChannelsSwapped = null
            calibratedRotation = null
            calibrationStatus =
                "Mount rotation changed; return to the calibrated rotation and reopen the probe."
            return null
        }
        if (!ListenerService.isRunning.value ||
            ListenerService.captureMode.value == CaptureMode.MONO_16K_FALLBACK
        ) {
            return null
        }
        return PartBCalibrationMapping(swapped, savedRotation)
    }

    fun launchB1Capture() {
        val speed = selectedSpeedKmh ?: return
        val side = selectedKnownSide ?: return
        val requestedMapping = validatedMappingOrDisable() ?: return
        val captureId = PartBProbeSession.store.nextCaptureId("B1")
        PartBProbeSession.launchCapture {
            val preCaptureCheck = readCurrentPartBCalibration(context)
            if (preCaptureCheck.mapping != requestedMapping) {
                throw IllegalStateException(
                    preCaptureCheck.failure ?: "Direction calibration changed before capture."
                )
            }
            val requestedMode = ListenerService.captureMode.value
            if (!ListenerService.isRunning.value || requestedMode == CaptureMode.MONO_16K_FALLBACK) {
                throw IllegalStateException("Running stereo listener required for Part B capture.")
            }
            val clip = forceAndAwaitProductionStereoClip()
            val analysis = analyzePartBClip(clip, requestedMapping.channelsSwapped, side)
                ?: throw IllegalStateException(
                    "Unsafe capture window. Keep monitoring running for at least one second, then retry."
                )
            // Re-read every production prerequisite after DSP and immediately before storage.
            // A rotation/calibration/route change during the 1.2 s post-roll invalidates the row.
            val finalCheck = readCurrentPartBCalibration(context)
            val finalMode = ListenerService.captureMode.value
            val finalServiceRunning = ListenerService.isRunning.value
            val alignedEstimate = productionAlignedEstimate(
                candidate = analysis.estimate,
                clip = clip,
                requestedMapping = requestedMapping,
                finalMapping = finalCheck.mapping,
                requestedMode = requestedMode,
                finalMode = finalMode,
                serviceRunning = finalServiceRunning
            )
            if (!isProductionAlignedCaptureContext(
                    clip = clip,
                    requestedMapping = requestedMapping,
                    finalMapping = finalCheck.mapping,
                    requestedMode = requestedMode,
                    finalMode = finalMode,
                    serviceRunning = finalServiceRunning
                )
            ) {
                throw IllegalStateException(
                    finalCheck.failure ?: "Production capture state changed before B1 storage."
                )
            }
            // One snapshot for the whole row: speed, accuracy, age and the IMU vote must all
            // describe the same instant.
            val motionSnapshot = SpeedSnapshot.capture()
            PartBProbeSession.store.appendB1B2(
                B1B2CaptureRow(
                    captureId = captureId,
                    capturedAtMs = clip.capturedAtMs,
                    captureMode = requestedMode,
                    speedKmh = speed,
                    knownSide = side,
                    speedKmhMeasured = motionSnapshot.kmh,
                    gnssAccuracyM = motionSnapshot.accuracyM,
                    gnssFixAgeMs = motionSnapshot.fixAgeMs,
                    imuMovingVote = motionSnapshot.imuMovingVote,
                    leftLowBandRmsDbFs = analysis.leftLowBandRmsDbFs,
                    rightLowBandRmsDbFs = analysis.rightLowBandRmsDbFs,
                    leftSnrDb = analysis.leftSnrDb,
                    rightSnrDb = analysis.rightSnrDb,
                    productionSnrDb = analysis.productionSnrDb,
                    phat = analysis.phat,
                    coherence = analysis.coherence,
                    signAgreement = analysis.signAgreement,
                    gateChecks = analysis.gateChecks,
                    estimate = alignedEstimate
                )
            )
        }
    }

    fun launchB3Capture() {
        val scenario = selectedScenario ?: return
        val requestedMapping = validatedMappingOrDisable() ?: return
        val captureId = PartBProbeSession.store.nextCaptureId("B3")
        PartBProbeSession.launchCapture {
            val preCaptureCheck = readCurrentPartBCalibration(context)
            if (preCaptureCheck.mapping != requestedMapping) {
                throw IllegalStateException(
                    preCaptureCheck.failure ?: "Direction calibration changed before capture."
                )
            }
            val requestedMode = ListenerService.captureMode.value
            if (!ListenerService.isRunning.value || requestedMode == CaptureMode.MONO_16K_FALLBACK) {
                throw IllegalStateException("Running stereo listener required for Part B capture.")
            }
            val clip = forceAndAwaitProductionStereoClip()
            val analysis = analyzePartBClip(
                clip,
                requestedMapping.channelsSwapped,
                knownSide = null
            )
                ?: throw IllegalStateException(
                    "Unsafe capture window. Keep monitoring running for at least one second, then retry."
                )
            val finalCheck = readCurrentPartBCalibration(context)
            val finalMode = ListenerService.captureMode.value
            val finalServiceRunning = ListenerService.isRunning.value
            val alignedEstimate = productionAlignedEstimate(
                candidate = analysis.estimate,
                clip = clip,
                requestedMapping = requestedMapping,
                finalMapping = finalCheck.mapping,
                requestedMode = requestedMode,
                finalMode = finalMode,
                serviceRunning = finalServiceRunning
            )
            if (!isProductionAlignedCaptureContext(
                    clip = clip,
                    requestedMapping = requestedMapping,
                    finalMapping = finalCheck.mapping,
                    requestedMode = requestedMode,
                    finalMode = finalMode,
                    serviceRunning = finalServiceRunning
                )
            ) {
                throw IllegalStateException(
                    finalCheck.failure ?: "Production capture state changed before B3 storage."
                )
            }
            PartBProbeSession.store.appendB3(
                B3SequenceRow(
                    captureId = captureId,
                    capturedAtMs = clip.capturedAtMs,
                    scenario = scenario,
                    singleShotVerdict = directionVerdict(alignedEstimate)
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        PartBProbeSession.ensureClassificationCollector()
        try {
            val check = readCurrentPartBCalibration(context)
            val mapping = check.mapping
            if (mapping == null) {
                calibratedChannelsSwapped = null
                calibratedRotation = null
                calibrationStatus = check.failure ?: "Direction calibration unavailable."
            } else {
                calibratedChannelsSwapped = mapping.channelsSwapped
                calibratedRotation = mapping.rotation
                calibrationStatus =
                    "Calibration ready: rotation=${mapping.rotation}, " +
                        "channelsSwapped=${mapping.channelsSwapped}"
            }
        } catch (t: Exception) {
            calibratedChannelsSwapped = null
            calibratedRotation = null
            calibrationStatus = "Calibration read failed: ${t.message ?: t::class.java.simpleName}"
        }
    }

    // Run capability probes on screen open
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val propVal = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            unprocessedProp = propVal ?: "null (unsupported or undefined)"

            val sources = mutableListOf(
                "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
                "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
                "MIC" to MediaRecorder.AudioSource.MIC,
                "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION
            )

            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val results = sources.map { (name, sourceId) ->
                if (!hasPermission) {
                    SourceProbeResult(
                        name = name,
                        sourceId = sourceId,
                        constructed = false,
                        error = "RECORD_AUDIO permission not granted"
                    )
                } else {
                    probeAudioSource(sourceId, name)
                }
            }
            sourceProbes = results
        }
    }

    // Determine overall PASS / FAIL
    val passStatus = remember(sourceProbes, recordingResult) {
        val grantedStereo = sourceProbes.any { it.constructed && it.actualChannelCount == 2 }
        if (recordingResult != null) {
            val rec = recordingResult!!
            if (rec.byteIdentical) {
                false to "FAIL: Left and Right channels are byte-identical (Mono cloned)"
            } else if (rec.normCorrLag0 >= 0.98) {
                false to "FAIL: Lag-0 correlation is >= 0.98 (${String.format(Locale.US, "%.4f", rec.normCorrLag0)})"
            } else if (!grantedStereo) {
                false to "FAIL: No AudioRecord granted with channelCount == 2"
            } else {
                true to "PASS: True stereo input verified (distinct channels, lag-0 corr < 0.98)"
            }
        } else if (sourceProbes.isNotEmpty()) {
            if (grantedStereo) {
                null to "READY TO TEST: Stereo source available, tap 'Record 3 seconds'"
            } else {
                false to "FAIL: None of the tested sources granted 2 channels at 48kHz"
            }
        } else {
            null to "PROBING HARDWARE CAPABILITIES..."
        }
    }

    fun buildClipboardReport(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val timeStr = sdf.format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== SOUNDSIGHT STEREO CAPABILITY PROBE ===")
        sb.appendLine("Timestamp: $timeStr")
        sb.appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
        sb.appendLine("Summary: ${passStatus.second}")
        sb.appendLine()
        sb.appendLine("--- PART 1: HARDWARE CAPABILITY ---")
        sb.appendLine("PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED: $unprocessedProp")
        sourceProbes.forEach { p ->
            sb.appendLine("Source ${p.name} (id=${p.sourceId}):")
            if (p.constructed) {
                sb.appendLine("  Constructed: SUCCESS")
                sb.appendLine("  Granted Source: ${p.actualSource}")
                sb.appendLine("  Granted Channel Count: ${p.actualChannelCount}")
                sb.appendLine("  Granted Sample Rate: ${p.actualSampleRate} Hz")
            } else {
                sb.appendLine("  Constructed: FAILED (${p.error})")
            }
        }
        sb.appendLine()
        sb.appendLine("--- PART 2: 3-SECOND STEREO CAPTURE DSP ANALYSIS ---")
        if (recordingResult != null) {
            val r = recordingResult!!
            sb.appendLine("Selected Source: ${r.selectedSource}")
            sb.appendLine("Sample Rate: ${r.sampleRate} Hz")
            sb.appendLine("Samples per Channel: ${r.totalSamplesPerChannel}")
            sb.appendLine("Byte-Identical Channels: ${if (r.byteIdentical) "YES (FATAL MONO CLONE)" else "NO (Distinct Channels)"}")
            sb.appendLine("Normalised Cross-Correlation at Lag 0: ${String.format(Locale.US, "%.4f", r.normCorrLag0)}")
            sb.appendLine("Broadband Search [-64..+64]:")
            sb.appendLine("  Best Broadband Lag: ${r.bestBroadbandLag} samples (${String.format(Locale.US, "%.2f", r.bestBroadbandLag * 1000.0 / r.sampleRate)} ms)")
            sb.appendLine("  Max Broadband Correlation: ${String.format(Locale.US, "%.4f", r.maxBroadbandCorr)}")
            sb.appendLine("Broadband RMS:")
            sb.appendLine("  Left:  ${String.format(Locale.US, "%.2f", r.rmsLdBFS)} dBFS")
            sb.appendLine("  Right: ${String.format(Locale.US, "%.2f", r.rmsRdBFS)} dBFS")
            sb.appendLine("Band-limited Analysis:")
            sb.appendLine("  Low Band (150 - 1000 Hz):")
            sb.appendLine("    RMS Left:  ${String.format(Locale.US, "%.2f", r.rmsLLowDb)} dBFS")
            sb.appendLine("    RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRLowDb)} dBFS")
            sb.appendLine("    Low-Band ILD: ${String.format(Locale.US, "%.2f", r.ildLowDb)} dB")
            sb.appendLine("    Best Low-Band Lag: ${r.bestLowBandLag} samples (${String.format(Locale.US, "%.2f", r.bestLowBandLag * 1000.0 / r.sampleRate)} ms)")
            sb.appendLine("    Max Low-Band Correlation: ${String.format(Locale.US, "%.4f", r.maxLowBandCorr)}")
            sb.appendLine("  High Band (2000 - 6000 Hz):")
            sb.appendLine("    RMS Left:  ${String.format(Locale.US, "%.2f", r.rmsLHighDb)} dBFS")
            sb.appendLine("    RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRHighDb)} dBFS")
            sb.appendLine("    High-Band ILD: ${String.format(Locale.US, "%.2f", r.ildHighDb)} dB")
            sb.appendLine("Approach Trend (6 sub-windows over 3s, mono downmix):")
            sb.appendLine("  LOW band: " + r.lowBandTrend.seriesDbfs.joinToString(", ") { String.format(Locale.US, "%.1f", it) } + " dBFS")
            sb.appendLine("    rise: ${String.format(Locale.US, "%.2f", r.lowBandTrend.riseDb)} dB")
            sb.appendLine("    verdict: ${r.lowBandTrend.verdict}")
            sb.appendLine("  HIGH band: " + r.highBandTrend.seriesDbfs.joinToString(", ") { String.format(Locale.US, "%.1f", it) } + " dBFS")
            sb.appendLine("    rise: ${String.format(Locale.US, "%.2f", r.highBandTrend.riseDb)} dB")
            sb.appendLine("    verdict: ${r.highBandTrend.verdict}")
        } else {
            sb.appendLine("No 3-second recording analysis run yet.")
        }
        sb.appendLine()
        sb.appendLine("--- PART 3: MICROPHONE HARDWARE GEOMETRY ---")
        if (micGeometryResult != null) {
            val g = micGeometryResult!!
            sb.appendLine("Active Capture Channels: ${g.captureChannels}")
            sb.appendLine("Sample Rate: ${g.sampleRate} Hz")
            sb.appendLine("Mics Reported (${g.mics.size}):")
            g.mics.forEachIndexed { i, m ->
                sb.appendLine("  [Mic #$i]")
                sb.appendLine("    id: ${m.id}")
                sb.appendLine("    description: ${m.description}")
                sb.appendLine("    address: ${m.address}")
                sb.appendLine("    type: ${m.type}")
                sb.appendLine("    location: ${m.location} (${MicGeometryProbe.locationName(m.location)})")
                sb.appendLine("    directionality: ${m.directionality}")
                sb.appendLine("    positionKnown: ${m.positionKnown}")
                sb.appendLine("    position: (${m.positionX}, ${m.positionY}, ${m.positionZ})")
                sb.appendLine("    orientation: (${m.orientationX}, ${m.orientationY}, ${m.orientationZ})")
                sb.appendLine("    channelMapping: ${m.channelMapping.joinToString { "(ch=${it.first}, type=${it.second})" }}")
            }
            sb.appendLine("  [Derived Geometry]")
            sb.appendLine("    geometryUsable: ${g.geometryUsable}")
            sb.appendLine("    baselineMetres: ${g.baselineMetres}")
            sb.appendLine("    baselineVector: ${g.baselineVector}")
            sb.appendLine("    maxLagSamplesFromGeometry: ${g.maxLagSamplesFromGeometry}")
            sb.appendLine("  [Vietnamese Interpretation]")
            if (g.baselineMetres != null) {
                sb.appendLine(String.format(Locale.US, "    1. Khoảng cách hai micro: %.2f cm", g.baselineMetres * 100.0f))
            } else {
                sb.appendLine("    1. Máy không khai báo vị trí micro")
            }
            if (g.baselineVector != null) {
                val (vx, vy, vz) = g.baselineVector
                val ax = kotlin.math.abs(vx)
                val ay = kotlin.math.abs(vy)
                val az = kotlin.math.abs(vz)
                val axisStr = if (ax >= ay && ax >= az) {
                    "chiều ngang màn hình"
                } else if (ay >= ax && ay >= az) {
                    "chiều dọc màn hình"
                } else {
                    "chiều trước-sau máy"
                }
                sb.appendLine("    2. Trục hai micro nằm chủ yếu theo: $axisStr")
            } else {
                sb.appendLine("    2. Trục hai micro: Không xác định được từ phần cứng")
            }
            if (g.maxLagSamplesFromGeometry != null) {
                sb.appendLine(String.format(Locale.US, "    3. Độ trễ tối đa về mặt vật lý: %.1f mẫu ở %d kHz", g.maxLagSamplesFromGeometry, g.sampleRate / 1000))
            } else {
                sb.appendLine("    3. Độ trễ tối đa về mặt vật lý: Không xác định được từ phần cứng")
            }
        } else {
            sb.appendLine("No microphone geometry probe run yet.")
        }
        sb.appendLine("==========================================")
        return sb.toString()
    }

    Scaffold(
        containerColor = BgIdle,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Stereo Audio Capability Probe",
                        style = AlertSub,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_back_stereo_probe")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = FgIdle
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // PART 4: PASS / FAIL SUMMARY BANNER
            val (isPass, statusMsg) = passStatus
            val bannerColor = when (isPass) {
                true -> Color(0xFF065F46) // Emerald green
                false -> CriticalBg
                null -> Color(0xFF1E293B) // Neutral slate
            }
            val bannerTextColor = when (isPass) {
                true -> AccentLive
                false -> CriticalFg
                null -> FgIdle
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = bannerColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stereo_probe_summary_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "HARDWARE VERIFICATION SUMMARY",
                        style = HistoryRow,
                        fontWeight = FontWeight.Bold,
                        color = bannerTextColor,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusMsg,
                        style = IdleStatus,
                        fontWeight = FontWeight.Bold,
                        color = bannerTextColor,
                        fontSize = 14.sp
                    )
                }
            }

            // ACTION BUTTONS: Record 3s & Copy Results
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val camcorderStereo = sourceProbes.firstOrNull { it.name == "CAMCORDER" && it.constructed && it.actualChannelCount == 2 }
                        val firstSource = camcorderStereo ?: sourceProbes.firstOrNull { it.constructed }
                        if (firstSource == null) {
                            recordingError = "No valid AudioRecord source available to capture stereo."
                            return@Button
                        }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            recordingError = "RECORD_AUDIO permission not granted"
                            return@Button
                        }
                        isRecording = true
                        recordingError = null
                        scope.launch {
                            try {
                                val res = withContext(Dispatchers.IO) {
                                    captureAndAnalyzeStereo(firstSource.sourceId, firstSource.name)
                                }
                                recordingResult = res
                            } catch (e: Exception) {
                                recordingError = "Recording failed: ${e.message}"
                            } finally {
                                isRecording = false
                            }
                        }
                    },
                    enabled = !isRecording && sourceProbes.any { it.constructed },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentLive,
                        contentColor = BgIdle
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_record_stereo_probe")
                ) {
                    if (isRecording) {
                        CircularProgressIndicator(
                            color = BgIdle,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Recording 3s...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Record 3s", fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val report = buildClipboardReport()
                        clipboard.setPrimaryClip(ClipData.newPlainText("StereoProbe", report))
                        Toast.makeText(context, "Full report copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF334155),
                        contentColor = FgIdle
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_copy_stereo_probe")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Results", fontWeight = FontWeight.Bold)
                }
            }

            recordingError?.let { err ->
                Text(
                    text = err,
                    color = CriticalBg,
                    style = HistoryRow,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = {
                    copyCsv("PulsePath Part B CSV", allPartBCsv(partBState))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("part_b_copy_all_csv"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentLive,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy All Part B CSV", fontWeight = FontWeight.Bold)
            }

            B1SpeedSweepCard(
                state = partBState,
                captureMode = listenerCaptureMode,
                calibrationStatus = calibrationStatus,
                captureReady = partBCaptureReady,
                selectedSpeedKmh = selectedSpeedKmh,
                selectedSide = selectedKnownSide,
                onSelectSpeed = { selectedSpeedKmh = it },
                onSelectSide = { selectedKnownSide = it },
                onCapture = ::launchB1Capture,
                onCopy = { copyCsv("PulsePath B1 CSV", b1Csv(partBState.b1b2Rows)) },
                onClear = PartBProbeSession.store::clearB1B2
            )

            B2DualWeightingCard(
                state = partBState,
                onCopy = { copyCsv("PulsePath B2 CSV", b2Csv(partBState.b1b2Rows)) }
            )

            B3SequenceCard(
                state = partBState,
                captureReady = partBCaptureReady,
                selectedScenario = selectedScenario,
                onSelectScenario = { selectedScenario = it },
                onCapture = ::launchB3Capture,
                onAssociation = PartBProbeSession.store::setAssociation,
                onSourceGroupId = PartBProbeSession.store::setSourceGroupId,
                onCopy = { copyCsv("PulsePath B3 CSV", b3Csv(partBState.b3Rows)) },
                onClear = PartBProbeSession.store::clearB3
            )

            HorizontalDivider(color = Color(0xFF334155))

            // PART 1: CAPABILITY REPORT
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "PART 1: AUDIO SOURCE CONSTRUCT PROBES",
                        style = HistoryRow,
                        color = AccentLive,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )

                    Text(
                        text = "AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED:\n$unprocessedProp",
                        style = HistoryRow,
                        color = FgIdle,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )

                    val activeCaptureMode by com.soundsight.audio.ListenerService.captureMode.collectAsState()
                    Text(
                        text = "ListenerService Active Capture Mode:\n$activeCaptureMode",
                        style = HistoryRow,
                        color = AccentLive,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )

                    HorizontalDivider(color = Color(0xFF2E3D4F))

                    sourceProbes.forEach { p ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Source: ${p.name} (AudioSource.${p.name})",
                                style = HistoryRow,
                                fontWeight = FontWeight.Bold,
                                color = if (p.constructed) AccentLive else CriticalBg,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (p.constructed) {
                                Text("• Construction: SUCCEEDED", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Actual Source Granted: ${p.actualSource}", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Actual Channels: ${p.actualChannelCount} ${if (p.actualChannelCount == 2) "(STEREO)" else "(MONO)"}", color = if (p.actualChannelCount == 2) AccentLive else WarningBg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Actual Sample Rate: ${p.actualSampleRate} Hz", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            } else {
                                Text("• Construction: FAILED", color = CriticalBg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("• Error: ${p.error}", color = Degraded, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // PART 2: DSP ANALYSIS REPORT
            if (recordingResult != null) {
                val r = recordingResult!!
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "PART 2: 3-SECOND STEREO DSP ANALYSIS",
                            style = HistoryRow,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Text(
                            text = "Captured via: ${r.selectedSource} @ ${r.sampleRate}Hz (${r.totalSamplesPerChannel} samples/channel)",
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp
                        )

                        HorizontalDivider(color = Color(0xFF2E3D4F))

                        // Byte identical check
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("a) Byte-identical channels:", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = if (r.byteIdentical) "TRUE (FATAL MONO)" else "FALSE (Pass)",
                                color = if (r.byteIdentical) CriticalBg else AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Lag 0 correlation
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("b) Norm Corr @ Lag 0:", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = String.format(Locale.US, "%.4f", r.normCorrLag0),
                                color = if (r.normCorrLag0 < 0.98) AccentLive else WarningBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Broadband best lag
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("c) Broadband Lag [-64..+64]:", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = "${r.bestBroadbandLag} smp (r=${String.format(Locale.US, "%.4f", r.maxBroadbandCorr)})",
                                color = FgIdle,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Broadband RMS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("d) Broadband RMS (L / R):", style = HistoryRow, color = Degraded, fontSize = 13.sp)
                            Text(
                                text = "${String.format(Locale.US, "%.1f", r.rmsLdBFS)} / ${String.format(Locale.US, "%.1f", r.rmsRdBFS)} dBFS",
                                color = FgIdle,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        HorizontalDivider(color = Color(0xFF2E3D4F))

                        Text(
                            text = "e) Band-limited RMS & ILD Analysis:",
                            style = HistoryRow,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "LOW BAND (150 - 1000 Hz):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text("• RMS Left: ${String.format(Locale.US, "%.2f", r.rmsLLowDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRLowDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• Low-band ILD (20*log10(L/R)): ${String.format(Locale.US, "%.2f", r.ildLowDb)} dB", color = AccentLive, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• Low-band Lag (f): ${r.bestLowBandLag} smp (r=${String.format(Locale.US, "%.4f", r.maxLowBandCorr)})", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "HIGH BAND (2000 - 6000 Hz):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text("• RMS Left: ${String.format(Locale.US, "%.2f", r.rmsLHighDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• RMS Right: ${String.format(Locale.US, "%.2f", r.rmsRHighDb)} dBFS", color = FgIdle, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("• High-band ILD (20*log10(L/R)): ${String.format(Locale.US, "%.2f", r.ildHighDb)} dB", color = AccentLive, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        HorizontalDivider(color = Color(0xFF2E3D4F))

                        Text(
                            text = "f) APPROACH TREND (band-limited energy over time):",
                            style = HistoryRow,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "LOW BAND (150 - 1000 Hz):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "LOW band: " + r.lowBandTrend.seriesDbfs.joinToString(", ") { String.format(Locale.US, "%.1f", it) } + " dBFS",
                                color = FgIdle,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "• rise: ${String.format(Locale.US, "%.1f", r.lowBandTrend.riseDb)} dB | verdict: ${r.lowBandTrend.verdict}",
                                color = when (r.lowBandTrend.verdict) {
                                    "RISING" -> AccentLive
                                    "FALLING" -> WarningBg
                                    else -> FgIdle
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "HIGH BAND (2000 - 6000 Hz):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "HIGH band: " + r.highBandTrend.seriesDbfs.joinToString(", ") { String.format(Locale.US, "%.1f", it) } + " dBFS",
                                color = FgIdle,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "• rise: ${String.format(Locale.US, "%.1f", r.highBandTrend.riseDb)} dB | verdict: ${r.highBandTrend.verdict}",
                                color = when (r.highBandTrend.verdict) {
                                    "RISING" -> AccentLive
                                    "FALLING" -> WarningBg
                                    else -> FgIdle
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // PART 3: MIC GEOMETRY REPORT
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_mic_geometry")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PART 3: MIC GEOMETRY PROBE",
                            style = HistoryRow,
                            color = AccentLive,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )

                        Button(
                            onClick = {
                                isProbingGeometry = true
                                geometryError = null
                                scope.launch {
                                    try {
                                        val res = MicGeometryProbe.probe(context)
                                        micGeometryResult = res
                                    } catch (e: Exception) {
                                        geometryError = "Geometry probe error: ${e.message}"
                                    } finally {
                                        isProbingGeometry = false
                                    }
                                }
                            },
                            enabled = !isProbingGeometry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentLive,
                                contentColor = BgIdle
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("btn_probe_mic_geometry")
                        ) {
                            if (isProbingGeometry) {
                                CircularProgressIndicator(
                                    color = BgIdle,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Đọc cấu hình micro", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    geometryError?.let {
                        Text(it, color = CriticalBg, fontSize = 12.sp)
                    }

                    if (micGeometryResult != null) {
                        val g = micGeometryResult!!
                        HorizontalDivider(color = Color(0xFF2E3D4F))

                        Text(
                            text = "Hardware Capture: ${g.captureChannels} Ch @ ${g.sampleRate} Hz (${g.mics.size} active mics)",
                            style = HistoryRow,
                            color = Degraded,
                            fontSize = 12.sp
                        )

                        // Monospace block per microphone
                        g.mics.forEachIndexed { index, mic ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Microphone #$index (ID: ${mic.id})",
                                    color = AccentLive,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text("• description: ${mic.description}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• address: ${mic.address}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• type: ${mic.type}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• location: ${mic.location} (${MicGeometryProbe.locationName(mic.location)})", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• directionality: ${mic.directionality}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• positionKnown: ${mic.positionKnown}", color = if (mic.positionKnown) AccentLive else WarningBg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• position (X, Y, Z): (${mic.positionX}, ${mic.positionY}, ${mic.positionZ}) m", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• orientation (X, Y, Z): (${mic.orientationX}, ${mic.orientationY}, ${mic.orientationZ})", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("• channelMapping: ${mic.channelMapping.joinToString { "(ch=${it.first}, type=${it.second})" }}", color = AccentLive, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        if (g.mics.isEmpty()) {
                            Text(
                                text = "No active microphones returned (or API < 28)",
                                color = WarningBg,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Derived block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "DERIVED GEOMETRY BLOCK:",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text("• geometryUsable: ${g.geometryUsable}", color = if (g.geometryUsable) AccentLive else WarningBg, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("• baselineMetres: ${g.baselineMetres?.let { String.format(Locale.US, "%.4f m", it) } ?: "null"}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("• baselineVector: ${g.baselineVector?.let { "(${it.first}, ${it.second}, ${it.third})" } ?: "null"}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("• maxLagSamplesFromGeometry: ${g.maxLagSamplesFromGeometry?.let { String.format(Locale.US, "%.2f", it) } ?: "null"}", color = FgIdle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Vietnamese Interpretation
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "KẾT QUẢ PHÂN TÍCH (VIETNAMESE):",
                                color = AccentLive,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )

                            val distText = if (g.baselineMetres != null) {
                                String.format(Locale.US, "1. Khoảng cách hai micro: %.2f cm", g.baselineMetres * 100.0f)
                            } else {
                                "1. Khoảng cách hai micro: Máy không khai báo vị trí micro"
                            }
                            Text(
                                text = distText,
                                color = FgIdle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            val axisText = if (g.baselineVector != null) {
                                val (vx, vy, vz) = g.baselineVector
                                val ax = kotlin.math.abs(vx)
                                val ay = kotlin.math.abs(vy)
                                val az = kotlin.math.abs(vz)
                                val axisStr = if (ax >= ay && ax >= az) {
                                    "chiều ngang màn hình"
                                } else if (ay >= ax && ay >= az) {
                                    "chiều dọc màn hình"
                                } else {
                                    "chiều trước-sau máy"
                                }
                                "2. Trục hai micro nằm chủ yếu theo: $axisStr"
                            } else {
                                "2. Trục hai micro: Máy không khai báo vị trí micro"
                            }
                            Text(
                                text = axisText,
                                color = FgIdle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            val lagText = if (g.maxLagSamplesFromGeometry != null) {
                                String.format(Locale.US, "3. Độ trễ tối đa về mặt vật lý: %.1f mẫu ở %d kHz", g.maxLagSamplesFromGeometry, g.sampleRate / 1000)
                            } else {
                                "3. Độ trễ tối đa về mặt vật lý: Máy không khai báo vị trí micro"
                            }
                            Text(
                                text = lagText,
                                color = FgIdle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---- Probe & DSP Implementation ----

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
private fun probeAudioSource(sourceId: Int, name: String): SourceProbeResult {
    var record: AudioRecord? = null
    try {
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBufferSize, sampleRate * 2 * 2)

        record = AudioRecord(
            sourceId,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (record.state == AudioRecord.STATE_INITIALIZED) {
            val actualSource = record.audioSource
            val actualChannels = record.channelCount
            val actualRate = record.sampleRate
            return SourceProbeResult(
                name = name,
                sourceId = sourceId,
                constructed = true,
                actualSource = actualSource,
                actualChannelCount = actualChannels,
                actualSampleRate = actualRate
            )
        } else {
            return SourceProbeResult(
                name = name,
                sourceId = sourceId,
                constructed = false,
                error = "AudioRecord STATE_UNINITIALIZED"
            )
        }
    } catch (e: Exception) {
        return SourceProbeResult(
            name = name,
            sourceId = sourceId,
            constructed = false,
            error = e.message ?: e.toString()
        )
    } finally {
        try {
            record?.release()
        } catch (_: Exception) {}
    }
}

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
private fun captureAndAnalyzeStereo(sourceId: Int, sourceName: String): RecordingProbeResult {
    val sampleRate = 48000
    val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    val bufferSize = max(minBufferSize, sampleRate * 2 * 2)

    val record = AudioRecord(
        sourceId,
        sampleRate,
        channelConfig,
        audioFormat,
        bufferSize
    )

    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IllegalStateException("Failed to initialize AudioRecord for $sourceName")
    }

    val totalSamplesNeeded = sampleRate * 3 // 3 seconds per channel
    val interleavedShortsNeeded = totalSamplesNeeded * 2 // 2 channels
    val interleavedBuffer = ShortArray(interleavedShortsNeeded)
    var totalRead = 0

    try {
        record.startRecording()
        val tempBuffer = ShortArray(4096)
        while (totalRead < interleavedShortsNeeded) {
            val toRead = minOf(tempBuffer.size, interleavedShortsNeeded - totalRead)
            val read = record.read(tempBuffer, 0, toRead)
            if (read < 0) {
                throw IllegalStateException("AudioRecord.read returned error code $read")
            }
            if (read > 0) {
                System.arraycopy(tempBuffer, 0, interleavedBuffer, totalRead, read)
                totalRead += read
            }
        }
    } finally {
        try {
            record.stop()
            record.release()
        } catch (_: Exception) {}
    }

    // De-interleave into left and right float arrays (normalized -1.0..1.0)
    val left = FloatArray(totalSamplesNeeded)
    val right = FloatArray(totalSamplesNeeded)
    var byteIdentical = true

    for (i in 0 until totalSamplesNeeded) {
        val sL = interleavedBuffer[i * 2]
        val sR = interleavedBuffer[i * 2 + 1]
        if (sL != sR) {
            byteIdentical = false
        }
        left[i] = sL / 32768.0f
        right[i] = sR / 32768.0f
    }

    // b) Normalized cross-correlation at lag 0
    val normCorrLag0 = computeNormalizedCorrelation(left, right, lag = 0)

    // c) Integer lag search over -64..+64 for broadband
    val (bestBroadbandLag, maxBroadbandCorr) = findBestLag(left, right, maxLag = 64)

    // d) RMS of each channel separately in dBFS
    val rmsL = computeRms(left)
    val rmsR = computeRms(right)
    val rmsLdBFS = toDbFs(rmsL)
    val rmsRdBFS = toDbFs(rmsR)

    // e) Bandpass filter: Low (150-1000 Hz) and High (2000-6000 Hz)
    val biquadLow = BiquadBandpass(fLow = 150.0, fHigh = 1000.0, sampleRate = sampleRate.toDouble())
    val biquadHigh = BiquadBandpass(fLow = 2000.0, fHigh = 6000.0, sampleRate = sampleRate.toDouble())

    val leftLow = biquadLow.process(left)
    val rightLow = biquadLow.process(right)

    val leftHigh = biquadHigh.process(left)
    val rightHigh = biquadHigh.process(right)

    val rmsLLow = computeRms(leftLow)
    val rmsRLow = computeRms(rightLow)
    val rmsLLowDb = toDbFs(rmsLLow)
    val rmsRLowDb = toDbFs(rmsRLow)
    val ildLowDb = if (rmsRLow > 1e-9) 20.0 * log10(rmsLLow / rmsRLow) else 0.0

    val rmsLHigh = computeRms(leftHigh)
    val rmsRHigh = computeRms(rightHigh)
    val rmsLHighDb = toDbFs(rmsLHigh)
    val rmsRHighDb = toDbFs(rmsRHigh)
    val ildHighDb = if (rmsRHigh > 1e-9) 20.0 * log10(rmsLHigh / rmsRHigh) else 0.0

    // f) Low-band lag search over -64..+64
    val (bestLowBandLag, maxLowBandCorr) = findBestLag(leftLow, rightLow, maxLag = 64)

    // Approach Trend: mono downmix split into 6 equal sub-windows
    val mono = FloatArray(totalSamplesNeeded) { i -> (left[i] + right[i]) * 0.5f }
    val monoLow = BiquadBandpass(fLow = 150.0, fHigh = 1000.0, sampleRate = sampleRate.toDouble()).process(mono)
    val monoHigh = BiquadBandpass(fLow = 2000.0, fHigh = 6000.0, sampleRate = sampleRate.toDouble()).process(mono)

    val numWindows = 6
    val windowSize = totalSamplesNeeded / numWindows
    val lowSeries = ArrayList<Double>(numWindows)
    val highSeries = ArrayList<Double>(numWindows)

    for (w in 0 until numWindows) {
        val start = w * windowSize
        val end = start + windowSize
        val subLow = monoLow.copyOfRange(start, end)
        val subHigh = monoHigh.copyOfRange(start, end)
        lowSeries.add(toDbFs(computeRms(subLow)))
        highSeries.add(toDbFs(computeRms(subHigh)))
    }

    val lowBandTrend = computeBandTrend(lowSeries)
    val highBandTrend = computeBandTrend(highSeries)

    return RecordingProbeResult(
        selectedSource = sourceName,
        sampleRate = sampleRate,
        totalSamplesPerChannel = totalSamplesNeeded,
        byteIdentical = byteIdentical,
        normCorrLag0 = normCorrLag0,
        bestBroadbandLag = bestBroadbandLag,
        maxBroadbandCorr = maxBroadbandCorr,
        rmsLdBFS = rmsLdBFS,
        rmsRdBFS = rmsRdBFS,
        rmsLLowDb = rmsLLowDb,
        rmsRLowDb = rmsRLowDb,
        ildLowDb = ildLowDb,
        rmsLHighDb = rmsLHighDb,
        rmsRHighDb = rmsRHighDb,
        ildHighDb = ildHighDb,
        bestLowBandLag = bestLowBandLag,
        maxLowBandCorr = maxLowBandCorr,
        lowBandTrend = lowBandTrend,
        highBandTrend = highBandTrend
    )
}

private fun computeBandTrend(series: List<Double>): BandTrendResult {
    if (series.isEmpty()) return BandTrendResult(emptyList(), 0.0, "STEADY")
    val first = series[0]
    val maxVal = series.maxOrNull() ?: first
    val minVal = series.minOrNull() ?: first
    val rise = maxVal - first
    val drop = first - minVal

    var nonNegativeSteps = 0
    var nonPositiveSteps = 0
    for (i in 0 until series.size - 1) {
        val diff = series[i + 1] - series[i]
        if (diff >= -1e-6) nonNegativeSteps++
        if (diff <= 1e-6) nonPositiveSteps++
    }

    val verdict = if (rise > 6.0 && nonNegativeSteps >= 4) {
        "RISING"
    } else if (drop > 6.0 && nonPositiveSteps >= 4) {
        "FALLING"
    } else {
        "STEADY"
    }

    return BandTrendResult(
        seriesDbfs = series,
        riseDb = rise,
        verdict = verdict
    )
}

// ---- Naive DSP Helpers ----

private fun computeRms(samples: FloatArray): Double {
    var sumSq = 0.0
    for (s in samples) {
        sumSq += s * s
    }
    return sqrt(sumSq / samples.size)
}

private fun toDbFs(rms: Double): Double {
    if (rms <= 1e-9) return -120.0
    return 20.0 * log10(rms)
}

private fun computeNormalizedCorrelation(left: FloatArray, right: FloatArray, lag: Int): Double {
    val n = left.size
    val startL = if (lag >= 0) lag else 0
    val startR = if (lag >= 0) 0 else -lag
    val length = n - kotlin.math.abs(lag)
    if (length <= 0) return 0.0

    var sumLR = 0.0
    var sumL2 = 0.0
    var sumR2 = 0.0

    for (i in 0 until length) {
        val l = left[startL + i].toDouble()
        val r = right[startR + i].toDouble()
        sumLR += l * r
        sumL2 += l * l
        sumR2 += r * r
    }

    val denom = sqrt(sumL2 * sumR2)
    return if (denom > 1e-12) sumLR / denom else 0.0
}

private fun findBestLag(left: FloatArray, right: FloatArray, maxLag: Int): Pair<Int, Double> {
    var bestLag = 0
    var maxCorr = -2.0

    for (lag in -maxLag..maxLag) {
        val corr = computeNormalizedCorrelation(left, right, lag)
        if (corr > maxCorr) {
            maxCorr = corr
            bestLag = lag
        }
    }
    return bestLag to maxCorr
}

/**
 * Standard second-order IIR Biquad Band-pass Filter (constant 0 dB peak gain).
 */
private class BiquadBandpass(fLow: Double, fHigh: Double, sampleRate: Double) {
    private val f0 = sqrt(fLow * fHigh)
    private val bw = (fHigh - fLow) / f0
    private val w0 = 2.0 * PI * f0 / sampleRate
    private val alpha = sin(w0) * Math.sinh(Math.log(2.0) / 2.0 * bw * w0 / sin(w0))

    // BPF with constant 0 dB peak gain:
    // b0 = alpha, b1 = 0, b2 = -alpha
    // a0 = 1 + alpha, a1 = -2*cos(w0), a2 = 1 - alpha
    private val a0 = 1.0 + alpha
    private val b0 = alpha / a0
    private val b1 = 0.0
    private val b2 = -alpha / a0
    private val a1 = (-2.0 * cos(w0)) / a0
    private val a2 = (1.0 - alpha) / a0

    fun process(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[i] = y0.toFloat()

            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return output
    }
}
