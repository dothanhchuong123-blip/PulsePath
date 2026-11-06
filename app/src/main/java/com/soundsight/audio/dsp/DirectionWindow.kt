package com.soundsight.audio.dsp

import com.soundsight.domain.DirectionGate
import kotlin.math.abs

/**
 * Trigger-aligned window resolution for stereo direction analysis, shared by [StereoAnalyzer]
 * and the calibration screen so both can never disagree about whether a clip is safe to analyse.
 *
 * A clip carries its own alignment metadata ([com.soundsight.audio.StereoClip]:
 * [preRoll][com.soundsight.audio.StereoClip.preRollFrameCount] and
 * [trigger][com.soundsight.audio.StereoClip.triggerFrameIndex]). A recorder that just started or
 * restarted snapshots far fewer than one second of pre-roll while still passing any length-only
 * check - its post-roll would then be analysed as if it were the event. These helpers make every
 * precondition explicit and fail closed to null (= emit UNKNOWN, run no GCC).
 */
object DirectionWindow {

    /** Full expected pre-roll: 1.0 s at 48 kHz. Anything else is an incomplete capture. */
    const val EXPECTED_PRE_ROLL_FRAMES = 48_000

    /** Pre-event noise reference: the existing 0.5 s of frames immediately at the clip start. */
    const val NOISE_REF_FRAMES = 24_000

    /** Analysis window: the existing 400 ms starting AT the verified trigger index. */
    const val ANALYSIS_FRAMES = 19_200

    /** Frames in the 50 ms trigger chunk that spike captures append before snapshotting. */
    const val TRIGGER_CHUNK_FRAMES = 2_400

    /**
     * Smallest window GCC accepts: gcc() requires n > maxLagSamples(32); anything at or below
     * this cannot produce a trustworthy lag estimate and never qualifies as a vote.
     */
    const val MIN_USABLE_WINDOW_FRAMES = 32

    /**
     * A verified, safe analysis window. Frames are indices into the clip's per-channel arrays.
     *
     * @property noiseRefEnd exclusive end of the pre-event noise reference (always starts at 0).
     * @property analysisStart first frame of the trigger-aligned analysis window.
     * @property analysisEnd exclusive end of the analysis window (exactly 400 ms later).
     */
    data class SafeWindow(
        val noiseRefEnd: Int,
        val analysisStart: Int,
        val analysisEnd: Int
    )

    /**
     * Trigger-onset index for a SPIKE capture whose pre-roll snapshot was taken after the 50 ms
     * trigger chunk was appended: the chunk sits at the tail of the snapshot, so onset is
     * (preRollFrames - 2_400). Clamped only so construction cannot go negative on a pathologically
     * short snapshot - such clips are then rejected by [resolve] anyway.
     */
    fun spikeTriggerIndex(preRollFrameCount: Int): Int =
        (preRollFrameCount - TRIGGER_CHUNK_FRAMES).coerceAtLeast(0)

    /**
     * Resolves the analysis window for a clip, or null when ANY precondition fails:
     *
     *  - alignment metadata present (a missing field means the producer could not honestly
     *    state where its trigger was);
     *  - the stereo pre-roll is the FULL expected 48,000 frames - not a partial snapshot;
     *  - the trigger index lies within the snapshot AND leaves the whole 0.5 s noise reference
     *    strictly before the trigger;
     *  - at least 400 ms of audio exists AFTER the trigger.
     *
     * Callers must treat null as DirectionEstimate.UNKNOWN without running GCC.
     */
    fun resolve(
        frameCount: Int,
        preRollFrameCount: Int?,
        triggerFrameIndex: Int?
    ): SafeWindow? {
        if (preRollFrameCount == null || triggerFrameIndex == null) return null
        if (preRollFrameCount != EXPECTED_PRE_ROLL_FRAMES) return null
        // Noise reference [0, NOISE_REF_FRAMES) must lie wholly before the trigger.
        if (triggerFrameIndex < NOISE_REF_FRAMES) return null
        // The trigger cannot sit beyond the captured pre-roll (forced captures sit exactly at
        // its end).
        if (triggerFrameIndex > preRollFrameCount) return null
        // At least 400 ms must exist after the trigger for the analysis window.
        if (frameCount - triggerFrameIndex < ANALYSIS_FRAMES) return null
        return SafeWindow(
            noiseRefEnd = NOISE_REF_FRAMES,
            analysisStart = triggerFrameIndex,
            analysisEnd = triggerFrameIndex + ANALYSIS_FRAMES
        )
    }

    /**
     * Whether ONE sub-window's lag estimate is trustworthy enough for its SIGN to count toward
     * the agreement gate (A9).
     *
     * A sign alone was never evidence: three noisy sub-windows can share the whole-window sign
     * by chance and manufacture "stable" agreement despite physically impossible lag, ambiguous
     * near-zero lag, correlation below the medium floor, sub-noise SNR, or wind-level RMS. Every
     * limit here is the EXISTING unchanged DirectionGate value applied per-window; nothing was
     * re-fitted. Only votes whose sign ALSO matches the whole window count (checked by caller).
     */
    fun isQualifiedVote(
        lagSamples: Double,
        normalizedCorrelation: Double,
        bandSnrDb: Double,
        bandRmsDbFs: Double,
        windowFrames: Int
    ): Boolean {
        if (windowFrames <= MIN_USABLE_WINDOW_FRAMES) return false
        if (abs(lagSamples) > DirectionGate.LAG_PHYSICAL_MAX) return false
        if (abs(lagSamples) <= DirectionGate.LAG_AMBIGUOUS_MIN) return false
        if (bandSnrDb < DirectionGate.BAND_SNR_DB_MIN) return false
        if (bandRmsDbFs > DirectionGate.WIND_RMS_DBFS_MAX) return false
        if (normalizedCorrelation < DirectionGate.CORR_MEDIUM) return false
        return true
    }
}
