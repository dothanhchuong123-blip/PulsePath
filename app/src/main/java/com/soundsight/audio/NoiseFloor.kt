package com.soundsight.audio

/**
 * Noise-floor multipliers, one per named detector stop - lower means more sensitive (a smaller
 * multiple of the quiet floor counts as a spike).
 *
 * The first three stops are the round-1 measured taper: LOW→MEDIUM is −0.6, MEDIUM→HIGH is −0.4
 * (each step ≈ 0.67× the previous one). [MAX] continues that taper rather than picking a round
 * number by eye: −0.4 × 0.67 ≈ −0.27, giving 1.6 − 0.27 ≈ 1.33.
 *
 * **PROVISIONAL, NOT MEASURED.** Unlike [com.soundsight.domain.DirectionGate]'s constants, no
 * field measurement backs 1.33 - it is a considered placeholder. A wrong value here is not a
 * safety failure either way: too sensitive just means more false-positive buzzes; too
 * conservative under-delivers on the feature. It ships flagged so nobody mistakes it for the
 * measured kind of number; a B1-style campaign can validate or retune it later. Reachable only
 * through the slider's extended past-High gesture - see
 * [com.soundsight.ui.settings.MaterialSensitivitySlider].
 *
 * [MAX] additionally means something the multiplier never said: it is the demo/experience stop.
 * Selecting it sets [com.soundsight.core.AlertConfig.demoFastAlert], which makes a bare spike
 * seize the screen immediately and drops the classification floors to DEMO_MIN_* - see that
 * flag's doc. The multiplier was never what made Max feel broken; a laptop speaker clears 1.33x
 * easily, and the alert screen was being withheld further downstream by the confidence floors and
 * by AlertPolicy.ACTIONABLE_MS, not by this number.
 */
enum class Sensitivity(val multiplier: Double) {
    LOW(2.6),
    MEDIUM(2.0),
    HIGH(1.6),
    MAX(1.33)
}

class NoiseFloor(
    private val windowSeconds: Double = DEFAULT_WINDOW_SECONDS,
    private val minFloor: Double = DEFAULT_MIN_FLOOR,
    val ceiling: Double = DEFAULT_CEILING
) {
    companion object {
        const val DEFAULT_WINDOW_SECONDS = 15.0
        const val DEFAULT_MIN_FLOOR = 1200.0

        /**
         * Absolute upper clamp on the spike detection threshold.
         *
         * **PROVISIONAL, NOT MEASURED.**
         * Prevents the spike gate threshold from tracking the background noise floor arbitrarily
         * upward into digital saturation during high-speed riding or strong wind buffeting.
         *
         * To settle this constant: measure in-band RMS floor (300–2500 Hz) at 0, 20, 40, 60 km/h
         * on the target device (using the Stereo Probe capture rows which record GNSS speed alongside
         * audio) and set this ceiling above the 95th percentile in-band riding floor but below
         * typical hazard arrival levels (~ -15 to -10 dBFS in-band, i.e. ~5200 to 10300 RMS).
         * 10000.0 corresponds to ~ -10.3 dBFS.
         */
        const val DEFAULT_CEILING = 10000.0
    }

    private val history = ArrayDeque<Pair<Long, Double>>()
    private var isFrozen: Boolean = false

    val isFrozenNow: Boolean
        get() = isFrozen

    @Synchronized
    fun setFrozen(frozen: Boolean) {
        this.isFrozen = frozen
    }

    @Synchronized
    fun submit(rms: Double) {
        if (isFrozen) return
        val now = System.currentTimeMillis()
        history.addLast(now to rms)
        cleanOldEntries(now)
    }

    @Synchronized
    fun floor(): Double {
        val now = System.currentTimeMillis()
        cleanOldEntries(now)
        if (history.isEmpty()) return minFloor

        val sorted = history.map { it.second }.sorted()
        val size = sorted.size
        // 20th percentile calculation
        val index = ((size - 1) * 0.20).toInt().coerceIn(0, size - 1)
        val p20 = sorted[index]

        return maxOf(p20, minFloor)
    }

    @Synchronized
    fun threshold(sensitivity: Sensitivity): Double {
        val currentFloor = floor()
        return minOf(currentFloor * sensitivity.multiplier, ceiling)
    }

    @Synchronized
    fun isSpike(rms: Double, sensitivity: Sensitivity): Boolean {
        val threshold = threshold(sensitivity)
        return rms >= threshold
    }

    private fun cleanOldEntries(now: Long) {
        val windowMs = (windowSeconds * 1000).toLong()
        while (history.isNotEmpty() && (now - history.first().first) > windowMs) {
            history.removeFirst()
        }
    }
}
