package com.soundsight.audio.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.sqrt

/**
 * Owns the register/unregister lifecycle of one motion-sensing session against a
 * [MotionSpikeDetector], with no Android framework dependency so the boundary rules are
 * unit-testable through a fake registration seam:
 *
 *  - [endSession] ALWAYS clears the detector, even if unregistering is a no-op - stale evidence
 *    from a previous session must never corroborate a classification in the next one;
 *  - [beginNewSession] also clears, but only for a genuinely NEW session: the early return while
 *    already registered keeps a duplicate start() from erasing live evidence mid-session.
 */
class MotionSensorSession(
    private val detector: MotionSpikeDetector,
    private val fallDetector: FallDetector? = null
) {

    @Volatile
    var isRegistered: Boolean = false
        private set

    /**
     * Starts a session. Returns immediately without resetting when one is already active; resets
     * the detector before registering otherwise. [register] returns false when sensors are not
     * available, in which case nothing is marked registered.
     */
    @Synchronized
    fun beginNewSession(register: () -> Boolean): Boolean {
        if (isRegistered) return true
        // Fresh mounting/session -> old baseline and spike history describe different physics.
        detector.reset()
        fallDetector?.reset()
        if (!register()) return false
        isRegistered = true
        return true
    }

    /**
     * Ends the session: unregisters first, then clears all evidence. The unregister lambda runs
     * unconditionally so the reset happens even when there was never a manager/listener to
     * unregister.
     */
    @Synchronized
    fun endSession(unregister: (() -> Unit)?) {
        unregister?.invoke()
        isRegistered = false
        detector.reset()
        fallDetector?.reset()
    }
}

/**
 * Thin SensorManager wrapper. All the math lives in plain Kotlin: [MotionSpikeDetector] (the
 * accel+gyro co-occurrence that corroborates an audio collision) and [FallDetector] (the
 * motion-only fall rule: impact, then tilt, then still). This object only registers listeners
 * and forwards samples to both. Neither TYPE_ACCELEROMETER nor TYPE_GYROSCOPE requires a
 * runtime permission on Android.
 *
 * Session boundaries go through [MotionSensorSession] so stop()/start() cannot leak motion
 * evidence across sessions - see that class for why both edges reset both detectors.
 *
 * A detected fall is published on [fallEvents]; `HudViewModel` turns it into the same
 * COLLISION_IMPACT / CRITICAL event the audio path would have produced, so the alert, the siren
 * and the crash-response screen are the ones the rider already knows.
 */
object MotionSensor {
    private val detector = MotionSpikeDetector()
    private val fallDetector = FallDetector()
    internal val session = MotionSensorSession(detector, fallDetector)
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    // Sensor callbacks arrive on the main looper; the collector is a viewModelScope coroutine on
    // the same thread. A small buffer with tryEmit means a fall is never dropped for want of a
    // suspension point, and never blocks the sensor callback.
    private val _fallEvents = MutableSharedFlow<FallDetector.FallEvent>(extraBufferCapacity = 8)
    val fallEvents: SharedFlow<FallDetector.FallEvent> = _fallEvents.asSharedFlow()

    val lastAccelMagnitude: Float get() = detector.lastAccelMagnitude
    val lastGyroMagnitude: Float get() = detector.lastGyroMagnitude
    val peakAccelDelta: Float get() = detector.peakAccelDelta
    val peakGyroMagnitude: Float get() = detector.peakGyroMagnitude

    /** For calibration: zero the peak-hold readout before performing a motion to measure. */
    fun resetPeaks() = detector.resetPeaks()

    /** The fall detector's state for the Demo panel readout. */
    fun fallSnapshot(): FallDetector.Snapshot = fallDetector.snapshot()

    @Synchronized
    fun start(context: Context): Boolean {
        return session.beginNewSession {
            val sm =
                context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sm == null) {
                false
            } else {
                val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                if (accel == null && gyro == null) {
                    false
                } else {
                    val l = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            // SensorEvent.timestamp is elapsed-realtime NANOS since boot - the
                            // same monotonic domain as SystemClock.elapsedRealtimeNanos(). Wall
                            // clock must never enter here: audio/motion co-occurrence is judged
                            // in this domain only, so a wall-clock adjustment cannot create or
                            // destroy an apparent correlation (see AudioClip.capturedMonotonicMs).
                            val nowMs = event.timestamp / 1_000_000L
                            val magnitude = sqrt(
                                event.values[0] * event.values[0] +
                                    event.values[1] * event.values[1] +
                                    event.values[2] * event.values[2]
                            )
                            when (event.sensor.type) {
                                Sensor.TYPE_ACCELEROMETER -> {
                                    detector.submitAccel(magnitude, nowMs)
                                    fallDetector.submitAccel(
                                        event.values[0], event.values[1], event.values[2], nowMs
                                    )?.let { _fallEvents.tryEmit(it) }
                                }
                                Sensor.TYPE_GYROSCOPE -> {
                                    detector.submitGyro(magnitude, nowMs)
                                    fallDetector.submitGyro(magnitude, nowMs)
                                }
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    accel?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
                    gyro?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
                    sensorManager = sm
                    listener = l
                    true
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        session.endSession {
            val sm = sensorManager
            val l = listener
            if (sm != null && l != null) {
                sm.unregisterListener(l)
            }
            sensorManager = null
            listener = null
        }
    }

    /** True if a combined accel+gyro spike was recorded near [aroundMs] - see MotionSpikeDetector.
     *  [aroundMs] must be in the elapsed-realtime domain, matching what onSensorChanged records. */
    fun hasRecentSpike(aroundMs: Long, windowMs: Long = 3000L): Boolean =
        detector.hasSpikeNear(aroundMs, windowMs)

    /** Monotonic now, exposed so call sites stamp captures in exactly the sensor timestamp domain. */
    fun nowMonotonicMs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000_000L
}
