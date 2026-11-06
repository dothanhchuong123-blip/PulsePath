package com.soundsight.audio.dsp

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.soundsight.audio.ListenerService
import com.soundsight.core.OnboardingStore
import com.soundsight.domain.DirectionConfidence
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.DirectionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

data class DirectionResult(
    val estimate: DirectionEstimate,
    val capturedAtMs: Long
)

object StereoAnalyzer {
    private const val TAG = "StereoAnalyzer"

    private val _results = MutableSharedFlow<DirectionResult>(extraBufferCapacity = 64)
    val results: SharedFlow<DirectionResult> = _results.asSharedFlow()

    private var job: Job? = null
    private val isAnalyzing = AtomicBoolean(false)

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return

        val appContext = context.applicationContext

        job = scope.launch(Dispatchers.Default) {
            // This collector is serial, so it can safely retain GCC scratch storage across clips.
            // Keeping the workspace local makes that single-owner lifetime explicit and prevents
            // a future second analyser job from racing over the same arrays.
            val gccWorkspace = GccWorkspace()
            ListenerService.stereoClips.collect { clip ->
                // Step 1: Drop if analysis is already running
                if (!isAnalyzing.compareAndSet(false, true)) {
                    Log.d(TAG, "Analysis in progress, dropping stereo clip capturedAtMs=${clip.capturedAtMs}")
                    return@collect
                }

                val startAnalysisMs = System.currentTimeMillis()

                try {
                    // Step 2: ROTATION GATE (Free check before any DSP)
                    val rotation = currentRotation(appContext)

                    val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
                    if (!isLandscape) {
                        Log.d(TAG, "Device is in portrait (rotation=$rotation), emitting UNKNOWN without DSP")
                        _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                        return@collect
                    }

                    // Step 3: CALIBRATION LOAD - fail closed (A5).
                    //
                    // A DataStore read failure used to become "direction supported, channels not
                    // swapped", letting the analyser emit a side through a mapping it never
                    // successfully loaded - a confidently wrong-side arrow for any device whose
                    // true mapping differs. Every read failure below emits UNKNOWN with a
                    // structured rejection reason and returns BEFORE any PCM conversion or GCC;
                    // no exception path substitutes a default mapping.
                    val directionDisabled = try {
                        OnboardingStore.directionDisabledFlow(appContext).first()
                    } catch (e: Exception) {
                        Log.w(TAG, "REJECT reason=calibration_read_failed field=directionDisabled error=${e.message}")
                        _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                        return@collect
                    }

                    if (directionDisabled) {
                        Log.d(TAG, "Direction disabled in calibration, emitting UNKNOWN without DSP")
                        _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                        return@collect
                    }

                    val calibration = try {
                        OnboardingStore.directionCalibrationStateFlow(appContext).first()
                    } catch (e: Exception) {
                        Log.w(TAG, "REJECT reason=calibration_read_failed field=channel_calibration error=${e.message}")
                        _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                        return@collect
                    }

                    // Step 3b: POLARITY IS BOUND TO THE ROTATION IT WAS MEASURED UNDER (A6).
                    //
                    // channelsSwapped describes phone-to-bike LEFT/RIGHT at calibration time.
                    // Rotating the phone 180 degrees swaps physical bike-left and bike-right
                    // while the boolean stays put, so analysis may proceed ONLY at exactly the
                    // recorded rotation. Never calibrated, legacy install without a rotation
                    // key, or a different rotation all abstain; none of them infers a mapping.
                    val savedRotation = calibration.calibrationRotation
                    when {
                        !calibration.channelsSwappedPresent || savedRotation == null -> {
                            Log.i(
                                TAG,
                                "REJECT reason=calibration_missing_or_legacy present=${calibration.channelsSwappedPresent} rotation=$savedRotation"
                            )
                            _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                            return@collect
                        }
                        (savedRotation != Surface.ROTATION_90 && savedRotation != Surface.ROTATION_270) ||
                            savedRotation != rotation -> {
                            Log.i(TAG, "REJECT reason=rotation_mismatch saved=$savedRotation current=$rotation")
                            _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                            return@collect
                        }
                    }
                    val channelsSwapped = calibration.channelsSwapped

                    // Step 4: WINDOW RESOLUTION BEFORE ANY CONVERSION OR DSP (A7).
                    //
                    // The clip states its own pre-roll length and trigger index; anything that
                    // is not a full second of pre-roll with a usable trigger alignment is
                    // rejected here - previously a partial pre-roll could hide behind its long
                    // post-roll and be analysed as if post-event audio were the event.
                    val frameCount = minOf(clip.left.size, clip.right.size)
                    val window = DirectionWindow.resolve(
                        frameCount = frameCount,
                        preRollFrameCount = clip.preRollFrameCount,
                        triggerFrameIndex = clip.triggerFrameIndex
                    )
                    if (window == null) {
                        Log.w(
                            TAG,
                            "REJECT reason=unsafe_window frames=$frameCount preRoll=${clip.preRollFrameCount} trigger=${clip.triggerFrameIndex}"
                        )
                        _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                        return@collect
                    }

                    // Step 5: Convert ONLY the verified ranges to doubles (-1.0 .. 1.0).
                    val leftNoiseRef = DoubleArray(window.noiseRefEnd) { clip.left[it] / 32768.0 }
                    val rightNoiseRef = DoubleArray(window.noiseRefEnd) { clip.right[it] / 32768.0 }
                    val analysisLen = window.analysisEnd - window.analysisStart
                    val leftAnalysis = DoubleArray(analysisLen) {
                        clip.left[window.analysisStart + it] / 32768.0
                    }
                    val rightAnalysis = DoubleArray(analysisLen) {
                        clip.right[window.analysisStart + it] / 32768.0
                    }

                    // Split analysis window into 4 equal 100ms pieces
                    val subLen = analysisLen / 4
                    val subWindowsLeft = Array(4) { i ->
                        leftAnalysis.copyOfRange(i * subLen, (i + 1) * subLen)
                    }
                    val subWindowsRight = Array(4) { i ->
                        rightAnalysis.copyOfRange(i * subLen, (i + 1) * subLen)
                    }

                    // Step 6: Band RMS & SNR PER CHANNEL, gated on the worse one (A8).
                    //
                    // Either noisy or occluded channel can corrupt a stereo delay estimate, so
                    // choosing the better channel's quality implicitly hid exactly the condition
                    // most likely to manufacture a spurious lag. Wind RMS gates on max(L, R),
                    // SNR on min(L, R): both thresholds are the EXISTING unchanged constants.
                    val leftAnalysisRms = bandRms(leftAnalysis, clip.sampleRate, 150.0, 1000.0)
                    val rightAnalysisRms = bandRms(rightAnalysis, clip.sampleRate, 150.0, 1000.0)
                    val leftBandRmsDbFs = toDbFs(leftAnalysisRms)
                    val rightBandRmsDbFs = toDbFs(rightAnalysisRms)
                    val bandRmsDbFs = max(leftBandRmsDbFs, rightBandRmsDbFs)

                    val leftNoiseRms = bandRms(leftNoiseRef, clip.sampleRate, 150.0, 1000.0)
                    val rightNoiseRms = bandRms(rightNoiseRef, clip.sampleRate, 150.0, 1000.0)
                    val leftSnrDb = 20.0 * log10(leftAnalysisRms / max(leftNoiseRms, 1e-12))
                    val rightSnrDb = 20.0 * log10(rightAnalysisRms / max(rightNoiseRms, 1e-12))
                    val snrDb = min(leftSnrDb, rightSnrDb)

                    // Step 6b: EARLY SNR DECISION, PHAT ONLY (A10).
                    //
                    // The live path used to select COHERENCE weighting for exactly the SNR
                    // values DirectionGate rejects, spending full GCC work on a branch that
                    // could never produce an arrow. Below the floor this analyser abstains
                    // immediately; Weighting.COHERENCE itself survives (with its tests) for the
                    // bounded Part B probe experiment. The calibration screen keeps its own
                    // selector deliberately - different acceptance rule, not proven unreachable.
                    if (snrDb < DirectionGate.BAND_SNR_DB_MIN) {
                        logTelemetry(
                            clip = clip,
                            captureMode = ListenerService.captureMode.value,
                            lag = null,
                            peakRatio = null,
                            normCorr = null,
                            snrDb = snrDb,
                            leftSnrDb = leftSnrDb,
                            rightSnrDb = rightSnrDb,
                            bandRmsDbFs = bandRmsDbFs,
                            leftRmsDbFs = leftBandRmsDbFs,
                            rightRmsDbFs = rightBandRmsDbFs,
                            signAgree = 0,
                            ildDb = null,
                            estimate = DirectionEstimate.UNKNOWN,
                            elapsedMs = System.currentTimeMillis() - startAnalysisMs,
                            rejectReason = "snr_below_floor"
                        )
                        _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                        return@collect
                    }
                    val weighting = Weighting.PHAT

                    // Step 7: GCC on whole window + 4 sub-windows, votes QUALIFIED before they
                    // count (A9).
                    //
                    // A sub-window contributes its vote only if ITS OWN estimate meets every
                    // existing limit - physical lag bound, clear of ambiguity, two-channel SNR,
                    // RMS, normalized correlation, usable length - AND its sign matches the
                    // whole-window sign. SIGN_AGREEMENT_MIN stays 3; fewer than three qualified
                    // agreeing votes means UNKNOWN, never a lowered bar.
                    val wholeGcc = gcc(
                        left = leftAnalysis,
                        right = rightAnalysis,
                        sampleRate = clip.sampleRate,
                        lowHz = 150.0,
                        highHz = 1000.0,
                        maxLagSamples = 32,
                        weighting = weighting,
                        workspace = gccWorkspace
                    )

                    var itdSignAgreement = 0
                    val wholeLag = wholeGcc.lagSamples
                    for (i in 0 until 4) {
                        val subGcc = gcc(
                            left = subWindowsLeft[i],
                            right = subWindowsRight[i],
                            sampleRate = clip.sampleRate,
                            lowHz = 150.0,
                            highHz = 1000.0,
                            maxLagSamples = 32,
                            weighting = weighting,
                            workspace = gccWorkspace
                        )
                        // SAME SIGN check: both positive, both negative, or both zero
                        val sameSign = (wholeLag > 0 && subGcc.lagSamples > 0) ||
                            (wholeLag < 0 && subGcc.lagSamples < 0) ||
                            (wholeLag == 0.0 && subGcc.lagSamples == 0.0)
                        if (!sameSign) continue

                        // Qualify THIS window's own estimate before its sign counts.
                        val subLeftRms = bandRms(subWindowsLeft[i], clip.sampleRate, 150.0, 1000.0)
                        val subRightRms = bandRms(subWindowsRight[i], clip.sampleRate, 150.0, 1000.0)
                        val subRmsDbFs = max(toDbFs(subLeftRms), toDbFs(subRightRms))
                        val subSnrL = 20.0 * log10(subLeftRms / max(leftNoiseRms, 1e-12))
                        val subSnrR = 20.0 * log10(subRightRms / max(rightNoiseRms, 1e-12))
                        val qualified = DirectionWindow.isQualifiedVote(
                            lagSamples = subGcc.lagSamples,
                            normalizedCorrelation = subGcc.normalizedCorrelation,
                            bandSnrDb = min(subSnrL, subSnrR),
                            bandRmsDbFs = subRmsDbFs,
                            windowFrames = subWindowsLeft[i].size
                        )
                        if (qualified) {
                            itdSignAgreement++
                        }
                    }

                    // ILD for telemetry only
                    val ildResult = ild(
                        left = leftAnalysis,
                        right = rightAnalysis,
                        sampleRate = clip.sampleRate,
                        lowHz = 2000.0,
                        highHz = 6000.0
                    )

                    // Step 8: DirectionGate.evaluate()
                    val estimate = DirectionGate.evaluate(
                        itdLagSamples = wholeGcc.lagSamples,
                        itdPeakRatio = wholeGcc.peakRatio,
                        itdPeakCorrelation = wholeGcc.normalizedCorrelation,
                        itdBandSnrDb = snrDb,
                        itdBandRmsDbFs = bandRmsDbFs,
                        itdSignAgreement = itdSignAgreement,
                        channelsSwapped = channelsSwapped
                    )

                    _results.emit(DirectionResult(estimate, clip.capturedAtMs))

                    logTelemetry(
                        clip = clip,
                        captureMode = ListenerService.captureMode.value,
                        lag = wholeGcc.lagSamples,
                        peakRatio = wholeGcc.peakRatio,
                        normCorr = wholeGcc.normalizedCorrelation,
                        snrDb = snrDb,
                        leftSnrDb = leftSnrDb,
                        rightSnrDb = rightSnrDb,
                        bandRmsDbFs = bandRmsDbFs,
                        leftRmsDbFs = leftBandRmsDbFs,
                        rightRmsDbFs = rightBandRmsDbFs,
                        signAgree = itdSignAgreement,
                        ildDb = ildResult.ildDb,
                        estimate = estimate,
                        elapsedMs = System.currentTimeMillis() - startAnalysisMs,
                        rejectReason = null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stereo analysis: ${e.message}", e)
                    _results.emit(DirectionResult(DirectionEstimate.UNKNOWN, clip.capturedAtMs))
                } finally {
                    isAnalyzing.set(false)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(appContext: Context): Int {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                appContext.display?.rotation ?: Surface.ROTATION_0
            } catch (_: Exception) {
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } else {
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    /** One structured telemetry line per decision, including BOTH per-channel quality values. */
    private fun logTelemetry(
        clip: com.soundsight.audio.StereoClip,
        captureMode: com.soundsight.audio.CaptureMode,
        lag: Double?,
        peakRatio: Double?,
        normCorr: Double?,
        snrDb: Double,
        leftSnrDb: Double,
        rightSnrDb: Double,
        bandRmsDbFs: Double,
        leftRmsDbFs: Double,
        rightRmsDbFs: Double,
        signAgree: Int,
        ildDb: Double?,
        estimate: DirectionEstimate,
        elapsedMs: Long,
        rejectReason: String?
    ) {
        val isShown = (estimate.confidence != DirectionConfidence.UNKNOWN)
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "TELEMETRY: capturedAtMs=%d mode=%s lag=%s peakRatio=%s normCorr=%s snrDb=%.1f snrL=%.1f snrR=%.1f bandRmsDbFs=%.1f rmsL=%.1f rmsR=%.1f signAgree=%d ildDb=%s side=%s conf=%s shown=%b reject=%s elapsedMs=%d",
                clip.capturedAtMs,
                captureMode.name,
                fmt(lag),
                fmt(peakRatio),
                fmt(normCorr),
                snrDb,
                leftSnrDb,
                rightSnrDb,
                bandRmsDbFs,
                leftRmsDbFs,
                rightRmsDbFs,
                signAgree,
                fmt(ildDb),
                estimate.side?.name ?: "NONE",
                estimate.confidence.name,
                isShown,
                rejectReason ?: "-",
                elapsedMs
            )
        )
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else String.format(Locale.US, "%.2f", v)

    fun stop() {
        job?.cancel()
        job = null
        isAnalyzing.set(false)
    }
}
