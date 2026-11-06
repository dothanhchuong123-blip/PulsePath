package com.soundsight.audio.motion

/**
 * Pure accelerometer+gyroscope spike-detection math, with no Android framework dependency so it
 * can be unit tested directly - mirrors the NoiseFloor/spike-gate split used for audio.
 *
 * A "motion spike" requires BOTH sensors to cross their threshold within [coOccurrenceWindowMs]
 * of each other. Either sensor alone is a common false-positive source on a motorbike (a pothole
 * is a hard accel spike with no rotation; leaning into a turn is rotation with no accel spike),
 * so requiring both is itself the primary false-positive guard.
 *
 * CALIBRATION: there is still no real motorbike crash telemetry to measure against (none exists),
 * but these are no longer a blind guess either. Measured on a real device (Xiaomi 2211133C "fuxi",
 * 2026-08-19) via the Demo panel's peak-hold readout ("Peak Accel Δ / Gyro Since Reset"):
 *
 *   Motion                                    Peak accel Δ (m/s^2)   Peak gyro (rad/s)
 *   Normal handling (pick up, hold, set down)  9.6                    3.86
 *   Firm knock (pothole proxy)                 12.6                   4.63
 *   Fast aggressive wrist-twist                24.0                   35.22
 *   Drop from waist height onto soft surface   92.7                   21.87
 *
 * Two things this revealed: (1) the old placeholder (20.0/3.5) was too low - a fast wrist-twist
 * (e.g. yanking the phone off its mount) cleared both bars and would have wrongly corroborated a
 * marginal-confidence collision classification; (2) gyro magnitude does NOT reliably separate
 * benign handling from a crash (the wrist-twist's gyro reading is actually higher than the drop
 * test's) - accel delta is the real discriminator here, which is why it's the one raised furthest.
 * accelSpikeDeltaMs2 is set roughly at the geometric mean of the worst benign reading (24.0) and
 * the crash-proxy reading (92.7), clearing the former with ~1.9x margin and sitting ~2x under the
 * latter. gyroSpikeMagnitudeRadS is raised only enough to clear ordinary handling cleanly; it is
 * not the deciding factor given the AND-combination with accel.
 *
 * Caveat that still applies: this is one operator, one device, a soft-surface drop test - not a
 * real high-speed asphalt impact, which would likely produce an even larger accel spike (a soft
 * landing under-represents real crash severity, so if this threshold is wrong, it's more likely
 * too high than too low). Revise if real on-device fall/crash data ever becomes available.
 */
class MotionSpikeDetector(
    private val accelSpikeDeltaMs2: Float = 45.0f,
    private val gyroSpikeMagnitudeRadS: Float = 5.0f,
    private val coOccurrenceWindowMs: Long = 500L,
    private val spikeHistoryWindowMs: Long = 15_000L,
    private val baselineAlpha: Float = 0.02f
) {
    private var accelBaseline: Float? = null
    private var lastAccelSpikeAtMs: Long? = null
    private var lastGyroSpikeAtMs: Long? = null
    private val combinedSpikes = ArrayDeque<Long>()

    var lastAccelMagnitude: Float = 0f
        private set
    var lastGyroMagnitude: Float = 0f
        private set

    /** Largest |magnitude - baseline| seen since the last [resetPeaks] - for calibration. */
    var peakAccelDelta: Float = 0f
        private set

    /** Largest gyro magnitude seen since the last [resetPeaks] - for calibration. */
    var peakGyroMagnitude: Float = 0f
        private set

    @Synchronized
    fun submitAccel(magnitude: Float, nowMs: Long) {
        lastAccelMagnitude = magnitude
        val baseline = accelBaseline
        if (baseline == null) {
            // First sample: adopt it as the resting baseline (typically ~9.8 m/s^2 including
            // gravity, regardless of phone orientation, since magnitude is orientation-agnostic).
            accelBaseline = magnitude
            return
        }
        val delta = kotlin.math.abs(magnitude - baseline)
        if (delta > peakAccelDelta) peakAccelDelta = delta
        if (delta >= accelSpikeDeltaMs2) {
            lastAccelSpikeAtMs = nowMs
            tryRecordCombinedSpike(nowMs)
            // Deliberately do not fold the spike sample into the baseline - otherwise a
            // sustained shake would be "forgotten" as the new normal within a few samples.
        } else {
            accelBaseline = baseline + baselineAlpha * (magnitude - baseline)
        }
    }

    @Synchronized
    fun submitGyro(magnitude: Float, nowMs: Long) {
        lastGyroMagnitude = magnitude
        if (magnitude > peakGyroMagnitude) peakGyroMagnitude = magnitude
        if (magnitude >= gyroSpikeMagnitudeRadS) {
            lastGyroSpikeAtMs = nowMs
            tryRecordCombinedSpike(nowMs)
        }
    }

    /** Zeroes the peak-hold readout (for calibration UI: "reset, do the motion, read the peak"). */
    @Synchronized
    fun resetPeaks() {
        peakAccelDelta = 0f
        peakGyroMagnitude = 0f
    }

    /**
     * Clears ALL motion evidence: the resting baseline, both last-spike timestamps, the combined
     * spike history, the last magnitudes, and the peak-hold calibration values.
     *
     * Called at sensor-session boundaries (see MotionSensor.start/stop) so evidence recorded in
     * one mounting/session can never corroborate a collision classification in the next: after a
     * stop/start, hasSpikeNear must find nothing from before the boundary, and the old baseline -
     * measured against a possibly different phone orientation - must not become the baseline of
     * the new session.
     *
     * Deliberately distinct from [resetPeaks]: that one exists for the calibration readout and
     * must NOT erase the already-recorded spikes used by AlertPolicy corroboration. This one
     * erases everything, precisely because a session is ending.
     */
    @Synchronized
    fun reset() {
        accelBaseline = null
        lastAccelSpikeAtMs = null
        lastGyroSpikeAtMs = null
        combinedSpikes.clear()
        lastAccelMagnitude = 0f
        lastGyroMagnitude = 0f
        peakAccelDelta = 0f
        peakGyroMagnitude = 0f
    }

    private fun tryRecordCombinedSpike(nowMs: Long) {
        val accelAt = lastAccelSpikeAtMs ?: return
        val gyroAt = lastGyroSpikeAtMs ?: return
        if (kotlin.math.abs(accelAt - gyroAt) <= coOccurrenceWindowMs) {
            combinedSpikes.addLast(nowMs)
            cleanOld(nowMs)
        }
    }

    private fun cleanOld(nowMs: Long) {
        while (combinedSpikes.isNotEmpty() && (nowMs - combinedSpikes.first()) > spikeHistoryWindowMs) {
            combinedSpikes.removeFirst()
        }
    }

    /** True if a combined accel+gyro spike was recorded within [windowMs] of [aroundMs]. */
    @Synchronized
    fun hasSpikeNear(aroundMs: Long, windowMs: Long = 3000L): Boolean {
        cleanOld(maxOf(aroundMs, (combinedSpikes.lastOrNull() ?: aroundMs)))
        return combinedSpikes.any { kotlin.math.abs(it - aroundMs) <= windowMs }
    }
}
