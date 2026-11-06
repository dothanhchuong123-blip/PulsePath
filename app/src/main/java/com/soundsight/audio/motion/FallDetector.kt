package com.soundsight.audio.motion

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motion-only fall detection: pure Kotlin, no Android dependency, unit-tested on synthetic IMU
 * sequences. This is D9's option O3 - the one design the audio evidence left open, because the
 * on-device classifier heard 0 of 4 real vehicle collisions, 0 of 5 body falls and 0 of 1
 * bicycle fall (`measurements/local-classifier/2026-09-15-collision/`). The sound side cannot
 * detect the rider's own crash; this class is what can.
 *
 * ## The rule
 *
 * A fall is three things in order, all read from the phone in its handlebar mount:
 *
 *  1. **Impact** - the accelerometer magnitude departs from its running baseline by at least
 *     [impactDeltaMs2]. This opens an *episode*; the gravity direction from
 *     [preImpactLookbackMs] before the impact is frozen as the "upright" reference.
 *  2. **Tilt** - after [settleDelayMs], the low-passed gravity direction differs from that
 *     reference by at least [tiltDegrees]. A bicycle or motorbike lying on its side has rotated
 *     the mount by roughly 90 degrees; a pothole, however hard, leaves it where it was.
 *  3. **Still** - for [stillWindowMs] the magnitude stays within [stillDeltaMs2] of 1 g and the
 *     gyroscope under [stillGyroRadS]. A rider who is still riding is never still; a bike on the
 *     ground is.
 *
 * All three within [confirmTimeoutMs] of the last impact (and [maxEpisodeMs] of the first), or
 * the episode is dropped without a word: potholes, kerbs, hard braking and a rider who gets up
 * and rides on before the phone has been still for [stillWindowMs] all end that way. The settle
 * delay and the still window overlap, so a bike that stops dead is reported ~1.5 s after the
 * bar hits and one that slides or bounces a little later - never before 1.5 s of stillness. After a fall is reported the detector sleeps for
 * [cooldownMs] so the same crash cannot be reported twice.
 *
 * The tilt-and-still pair is the discriminator, not the impact size. The impact bar only has to
 * sit above mount handling, because unclipping the phone and putting it in a pocket is exactly
 * "impact, then a new orientation, then still" - the one benign sequence that mimics a fall.
 *
 * ## What the numbers are and are not
 *
 * **PROVISIONAL - no mounted-phone IMU record exists** (W-L9 in the plan repo produces it). The
 * only measurements are the 2026-08-19 hand-held readings in [MotionSpikeDetector]'s comment:
 * normal handling peaked at 9.6 m/s^2 delta, a firm knock 12.6, a fast wrist-twist (the
 * unclip-from-mount proxy) 24.0, a waist-height drop onto a soft surface 92.7. [impactDeltaMs2]
 * sits at 35: 1.5x the wrist-twist, 2.6x under the soft drop, and a bar hitting asphalt from
 * bar height is harder than a soft drop from waist height. [tiltDegrees] at 50 keeps a bike
 * leaning on a wall (~20-30 degrees) and a phone re-seated at a different mount angle out, and
 * is well under the ~90 of a bike on its side. The stillness bounds are the noise a mounted
 * phone shows at rest with the engine off; W-L9's baseline runs will say whether an idling
 * motorbike needs them wider.
 *
 * The rule is a rule, not a probability: it fires or it does not, and the event it produces is
 * labelled as coming from the motion sensors so the rider and whoever reads the history can see
 * that. The crash-response screen it leads to never calls or texts anyone by itself (see
 * `CrashResponseScreen`), which is what makes a false fall a nuisance and not an emergency.
 *
 * ## Time
 *
 * Every timestamp is elapsed-realtime milliseconds, the sensor's own domain (see
 * [MotionSensor]); the gravity low-pass is time-constant based ([gravityTauMs]) so a different
 * sensor rate changes nothing but resolution. The magnitude baseline is the same per-sample EMA
 * as [MotionSpikeDetector] so "peak accel delta" here and in the Demo panel are the same
 * quantity.
 */
class FallDetector(
    private val impactDeltaMs2: Float = 35.0f,
    private val tiltDegrees: Float = 50.0f,
    private val settleDelayMs: Long = 1000L,
    private val stillWindowMs: Long = 1500L,
    private val stillDeltaMs2: Float = 2.5f,
    private val stillGyroRadS: Float = 0.6f,
    private val confirmTimeoutMs: Long = 6000L,
    private val maxEpisodeMs: Long = 10_000L,
    private val cooldownMs: Long = 30_000L,
    private val preImpactLookbackMs: Long = 1500L,
    private val gravityTauMs: Long = 300L,
    private val baselineAlpha: Float = 0.02f
) {
    enum class State { IDLE, IMPACT, COOLDOWN }

    /** One detected fall. Times are elapsed-realtime ms, the sensor domain. */
    data class FallEvent(
        /** The first impact of the episode - the instant the crash happened. */
        val impactAtMs: Long,
        /** When the tilt-and-still rule was satisfied; the alert can exist no earlier. */
        val detectedAtMs: Long,
        val peakAccelDeltaMs2: Float,
        val tiltDegrees: Float,
        val stillMs: Long
    )

    /** Read-only view for the Demo panel. */
    data class Snapshot(
        val state: State,
        val peakAccelDeltaMs2: Float,
        val lastTiltDegrees: Float,
        val stillMs: Long,
        val fallCount: Int
    )

    private class GravitySample(val atMs: Long, val x: Float, val y: Float, val z: Float)

    private var state = State.IDLE
    private var accelBaseline: Float? = null

    // Low-passed gravity direction and its history for the pre-impact reference.
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var hasGravity = false
    private var lastAccelAtMs: Long? = null
    private val gravityHistory = ArrayDeque<GravitySample>()
    private var lastGravitySnapshotAtMs: Long? = null

    // Episode.
    private var impactAtMs = 0L
    private var lastImpactAtMs = 0L
    private var preImpactGravity: GravitySample? = null
    private var peakAccelDelta = 0f
    private var lastUnstillAtMs = Long.MIN_VALUE
    private var lastTiltDegrees = 0f
    private var cooldownUntilMs = 0L
    private var fallCount = 0

    /**
     * Feeds one accelerometer vector (m/s^2, gravity included) at [nowMs]. Returns a [FallEvent]
     * on the sample that completes the rule, null on every other sample.
     */
    @Synchronized
    fun submitAccel(x: Float, y: Float, z: Float, nowMs: Long): FallEvent? {
        val magnitude = sqrt(x * x + y * y + z * z)
        updateGravity(x, y, z, nowMs)

        val baseline = accelBaseline
        val delta: Float
        if (baseline == null) {
            accelBaseline = magnitude
            delta = 0f
        } else {
            delta = abs(magnitude - baseline)
            if (delta < impactDeltaMs2) {
                // As in MotionSpikeDetector: impact samples never move the baseline.
                accelBaseline = baseline + baselineAlpha * (magnitude - baseline)
            }
        }
        if (abs(magnitude - STANDARD_GRAVITY) > stillDeltaMs2) lastUnstillAtMs = nowMs

        when (state) {
            State.COOLDOWN -> {
                if (nowMs >= cooldownUntilMs) state = State.IDLE
                return null
            }
            State.IDLE -> {
                if (baseline != null && delta >= impactDeltaMs2) beginEpisode(nowMs, delta)
                return null
            }
            State.IMPACT -> {
                if (delta >= impactDeltaMs2) lastImpactAtMs = nowMs
                if (delta > peakAccelDelta) peakAccelDelta = delta
                val deadline = min(lastImpactAtMs + confirmTimeoutMs, impactAtMs + maxEpisodeMs)
                if (nowMs > deadline) {
                    state = State.IDLE
                    return null
                }
                if (nowMs - impactAtMs < settleDelayMs) return null
                val pre = preImpactGravity ?: return null
                lastTiltDegrees = angleDegrees(pre.x, pre.y, pre.z, gx, gy, gz)
                val stillMs = nowMs - lastUnstillAtMs
                if (stillMs < stillWindowMs) return null
                if (lastTiltDegrees < tiltDegrees) return null
                state = State.COOLDOWN
                cooldownUntilMs = nowMs + cooldownMs
                fallCount++
                return FallEvent(
                    impactAtMs = impactAtMs,
                    detectedAtMs = nowMs,
                    peakAccelDeltaMs2 = peakAccelDelta,
                    tiltDegrees = lastTiltDegrees,
                    stillMs = stillMs
                )
            }
        }
    }

    /** Feeds one gyroscope magnitude (rad/s). Rotation only ever breaks stillness. */
    @Synchronized
    fun submitGyro(magnitude: Float, nowMs: Long) {
        if (magnitude > stillGyroRadS) lastUnstillAtMs = max(lastUnstillAtMs, nowMs)
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        state = state,
        peakAccelDeltaMs2 = peakAccelDelta,
        lastTiltDegrees = lastTiltDegrees,
        stillMs = lastAccelAtMs?.let { if (lastUnstillAtMs == Long.MIN_VALUE) 0L else it - lastUnstillAtMs } ?: 0L,
        fallCount = fallCount
    )

    /**
     * Clears everything, including the cooldown and the gravity history. Called at sensor-session
     * boundaries by [MotionSensorSession] for the same reason [MotionSpikeDetector.reset] is: a
     * reference orientation from a previous mounting must never be the "upright" of the next.
     */
    @Synchronized
    fun reset() {
        state = State.IDLE
        accelBaseline = null
        gx = 0f; gy = 0f; gz = 0f
        hasGravity = false
        lastAccelAtMs = null
        gravityHistory.clear()
        lastGravitySnapshotAtMs = null
        impactAtMs = 0L
        lastImpactAtMs = 0L
        preImpactGravity = null
        peakAccelDelta = 0f
        lastUnstillAtMs = Long.MIN_VALUE
        lastTiltDegrees = 0f
        cooldownUntilMs = 0L
        fallCount = 0
    }

    private fun beginEpisode(nowMs: Long, delta: Float) {
        state = State.IMPACT
        impactAtMs = nowMs
        lastImpactAtMs = nowMs
        peakAccelDelta = delta
        lastTiltDegrees = 0f
        // An impact is not stillness, whatever the magnitude bound says about this one sample.
        lastUnstillAtMs = max(lastUnstillAtMs, nowMs)
        // The newest gravity sample at least preImpactLookbackMs old - before the tip-over that
        // precedes the bar hitting the ground. A session younger than that uses its oldest sample.
        val cutoff = nowMs - preImpactLookbackMs
        preImpactGravity = gravityHistory.lastOrNull { it.atMs <= cutoff }
            ?: gravityHistory.firstOrNull()
            ?: if (hasGravity) GravitySample(nowMs, gx, gy, gz) else null
    }

    private fun updateGravity(x: Float, y: Float, z: Float, nowMs: Long) {
        val last = lastAccelAtMs
        if (!hasGravity || last == null) {
            gx = x; gy = y; gz = z
            hasGravity = true
            // Stillness is counted from the first sample, never from before the session began.
            if (lastUnstillAtMs == Long.MIN_VALUE) lastUnstillAtMs = nowMs
        } else {
            val dtMs = (nowMs - last).coerceIn(1L, 500L)
            val alpha = (1.0 - exp(-dtMs.toDouble() / gravityTauMs.toDouble())).toFloat()
            gx += alpha * (x - gx)
            gy += alpha * (y - gy)
            gz += alpha * (z - gz)
        }
        lastAccelAtMs = nowMs
        val lastSnap = lastGravitySnapshotAtMs
        if (lastSnap == null || nowMs - lastSnap >= GRAVITY_SNAPSHOT_INTERVAL_MS) {
            gravityHistory.addLast(GravitySample(nowMs, gx, gy, gz))
            lastGravitySnapshotAtMs = nowMs
            val keepFrom = nowMs - preImpactLookbackMs - GRAVITY_HISTORY_MARGIN_MS
            while (gravityHistory.size > 1 && gravityHistory.first().atMs < keepFrom) {
                gravityHistory.removeFirst()
            }
        }
    }

    companion object {
        const val STANDARD_GRAVITY = 9.80665f
        private const val GRAVITY_SNAPSHOT_INTERVAL_MS = 100L
        private const val GRAVITY_HISTORY_MARGIN_MS = 1500L

        /** Angle between two vectors in degrees; 0 when either is (near) zero. */
        fun angleDegrees(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
            val na = sqrt(ax * ax + ay * ay + az * az)
            val nb = sqrt(bx * bx + by * by + bz * bz)
            if (na < 1e-3f || nb < 1e-3f) return 0f
            val cos = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos.toDouble())).toFloat()
        }
    }
}
