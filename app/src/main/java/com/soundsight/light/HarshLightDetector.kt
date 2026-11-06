package com.soundsight.light

/**
 * Pure Kotlin decision engine for harsh-light contrast boosting.
 *
 * All decision math lives here, taking ambient lux and a monotonic timestamp in milliseconds,
 * returning whether the contrast boost should be on or off. Zero Android framework dependencies
 * so that state transitions, hysteresis, and rapid shadow flickers can be unit-tested on the JVM
 * against synthetic lux traces without a physical device or emulator.
 *
 * ## Hysteresis & Dwell Justification
 *
 *  - [LUX_BOOST_THRESHOLD] (10,000 lux): Full outdoor daylight begins around 10,000 lux, while
 *    direct tropical/summer sun reaches 30,000–100,000+ lux. Below 10,000 lux (typical open shade
 *    or heavy overcast at 1,000–5,000 lux), standard screen palettes with modern display auto-brightness
 *    remain comfortable and readable. Above 10,000 lux, ambient glare on a bar-mounted motorcycle phone
 *    severely degrades contrast and washes out subtle tint distinctions, requiring a near-pure black/white
 *    high-contrast palette (WCAG AAA >= 7:1) for rider safety.
 *
 *  - [LUX_RELEASE_THRESHOLD] (5,000 lux): Creating a wide 5,000-lux deadband prevents boundary oscillation.
 *    5,000 lux corresponds to deep tree canopies, entering covered bridges, or overcast skies, safely away
 *    from direct solar glare.
 *
 *  - [DEFAULT_DWELL_MS] (2,500 ms): At typical riding speeds (30–60 km/h ≈ 8–17 m/s), passing under a
 *    roadside tree canopy or utility pole shadow produces a dip lasting ~0.3 to 1.8 seconds. Requiring 2.5
 *    seconds of continuous low light before releasing the boost completely eliminates HUD strobing / flicker
 *    under intermittent foliage. Similarly, a 2.5-second dwell on the boost onset prevents brief specular
 *    flashes (e.g. sunlight glinting off a car window or chrome mirror while riding in shade) from jarring
 *    the rider with an unnecessary palette swap.
 *
 * ## Boost strength
 *
 * [isBoosted] is the gate - *may* the boost apply. [boostLevel] is how much of it applies, in `0f..1f`.
 * Splitting the two is what makes the boost proportional instead of all-or-nothing: 10,500 lux (bright
 * overcast) and 80,000 lux (direct sun on a bar mount) used to receive the identical maximum treatment,
 * which meant the boost had to be tuned for the worst case and therefore always overshot the common one.
 *
 *  - Gate closed: the level is always exactly 0. The ramp is deliberately *downstream* of the dwell and
 *    hysteresis, never a replacement for them - a partial boost that fluttered under tree shadows would
 *    reintroduce the exact strobing the dwell exists to prevent.
 *  - Gate open: the level runs from [MIN_ACTIVE_LEVEL] at the boost threshold up to 1.0 at
 *    [LUX_FULL_BOOST], on a smoothstep so there is no visible corner at either end.
 *
 * [MIN_ACTIVE_LEVEL] (0.35) is the floor rather than 0 because the gate has already decided the light
 * is harsh; opening it into a treatment indistinguishable from "off" would make the 2.5s dwell buy
 * nothing. [LUX_FULL_BOOST] (40,000 lux) is where the level saturates - that is genuine open direct
 * sun, well past the 10,000 lux where daylight merely begins.
 */
class HarshLightDetector(
    val boostThresholdLux: Float = LUX_BOOST_THRESHOLD,
    val releaseThresholdLux: Float = LUX_RELEASE_THRESHOLD,
    val dwellMs: Long = DEFAULT_DWELL_MS,
    val fullBoostLux: Float = LUX_FULL_BOOST
) {
    companion object {
        const val LUX_BOOST_THRESHOLD = 10_000f
        const val LUX_RELEASE_THRESHOLD = 5_000f
        const val DEFAULT_DWELL_MS = 2_500L
        const val LUX_FULL_BOOST = 40_000f
        const val MIN_ACTIVE_LEVEL = 0.35f

        /**
         * Maps a lux reading to a boost strength, assuming the gate is already open.
         * Pure and public so the ramp can be characterised in tests without driving a detector
         * through its dwell timers first.
         */
        fun levelForLux(
            lux: Float,
            boostThresholdLux: Float = LUX_BOOST_THRESHOLD,
            fullBoostLux: Float = LUX_FULL_BOOST
        ): Float {
            if (fullBoostLux <= boostThresholdLux) return 1f
            val span = ((lux - boostThresholdLux) / (fullBoostLux - boostThresholdLux))
                .coerceIn(0f, 1f)
            // Smoothstep: zero first derivative at both ends, so neither the moment the boost
            // engages nor the moment it saturates has a visible corner in it.
            val eased = span * span * (3f - 2f * span)
            return MIN_ACTIVE_LEVEL + (1f - MIN_ACTIVE_LEVEL) * eased
        }
    }

    var isBoosted: Boolean = false
        private set

    /**
     * How strongly the boost should apply right now, in `0f..1f`. Exactly 0 whenever [isBoosted]
     * is false, and never below [MIN_ACTIVE_LEVEL] while it is true.
     */
    var boostLevel: Float = 0f
        private set

    var lastLux: Float = 0f
        private set

    var lastTimestampMs: Long = 0L
        private set

    private var pendingTarget: Boolean? = null
    private var pendingSinceMs: Long = 0L

    /**
     * Submits a new ambient light reading in lux with its monotonic timestamp (in milliseconds).
     * Returns the updated [isBoosted] state.
     */
    fun submitLux(lux: Float, timestampMs: Long): Boolean {
        lastLux = lux
        lastTimestampMs = timestampMs

        if (!isBoosted) {
            // Looking to enter boost mode: requires lux >= boostThresholdLux sustained for dwellMs
            if (lux >= boostThresholdLux) {
                if (pendingTarget != true) {
                    pendingTarget = true
                    pendingSinceMs = timestampMs
                }
                if (timestampMs - pendingSinceMs >= dwellMs) {
                    isBoosted = true
                    pendingTarget = null
                }
            } else {
                // Intermittent shadow or glare dropped below boost threshold: cancel pending boost
                pendingTarget = null
                pendingSinceMs = 0L
            }
        } else {
            // Currently boosted, looking to release: requires lux <= releaseThresholdLux sustained for dwellMs
            if (lux <= releaseThresholdLux) {
                if (pendingTarget != false) {
                    pendingTarget = false
                    pendingSinceMs = timestampMs
                }
                if (timestampMs - pendingSinceMs >= dwellMs) {
                    isBoosted = false
                    pendingTarget = null
                }
            } else {
                // Still in harsh sun or in the deadband (> 5,000 lux): tree shadows < 2.5s fall here!
                pendingTarget = null
                pendingSinceMs = 0L
            }
        }

        // The strength tracks lux continuously while the gate is open, so the treatment keeps
        // scaling with the light after the gate has stopped changing.
        boostLevel = if (isBoosted) levelForLux(lux, boostThresholdLux, fullBoostLux) else 0f

        return isBoosted
    }

    /**
     * True if a boost or release transition is actively pending dwell timer completion.
     */
    val isPendingTransition: Boolean get() = pendingTarget != null

    /**
     * Evaluates whether the pending dwell time has elapsed at [currentTimestampMs].
     * Useful when the ambient light sensor is an on-change sensor that reports no new samples
     * when lux remains perfectly constant.
     */
    fun evaluate(currentTimestampMs: Long): Boolean {
        if (pendingTarget != null) {
            return submitLux(lastLux, currentTimestampMs)
        }
        return isBoosted
    }

    /**
     * Resets internal detector state and pending dwell timers.
     */
    fun reset() {
        isBoosted = false
        boostLevel = 0f
        lastLux = 0f
        lastTimestampMs = 0L
        pendingTarget = null
        pendingSinceMs = 0L
    }
}
