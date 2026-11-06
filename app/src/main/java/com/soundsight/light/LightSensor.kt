package com.soundsight.light

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the register/unregister lifecycle of one ambient-light sensing session against a
 * [HarshLightDetector], with no Android framework dependency so the boundary rules are
 * unit-testable through a fake registration seam:
 *
 *  - [endSession] ALWAYS clears the detector, even if unregistering is a no-op - stale evidence
 *    from a previous session must never influence contrast in the next one;
 *  - [beginNewSession] also clears, but only for a genuinely NEW session: the early return while
 *    already registered keeps a duplicate start() from resetting live dwell state mid-session.
 */
class LightSensorSession(private val detector: HarshLightDetector) {

    @Volatile
    var isRegistered: Boolean = false
        private set

    /**
     * Starts a session. Returns immediately without resetting when one is already active; resets
     * the detector before registering otherwise. [register] returns false when the light sensor is
     * unavailable, in which case nothing is marked registered.
     */
    @Synchronized
    fun beginNewSession(register: () -> Boolean): Boolean {
        if (isRegistered) return true
        detector.reset()
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
    }
}

/**
 * Thin SensorManager wrapper for ambient light sensing. All decision math lives in [HarshLightDetector]
 * (plain Kotlin, unit-testable); this object only manages listener registration and forwards lux readings.
 *
 * SENSOR_DELAY_NORMAL is used because ambient light changes slowly and high-frequency sampling is wasteful
 * on battery.
 *
 * Session boundaries go through [LightSensorSession] so stop()/start() cannot leak evidence across sessions.
 */
object LightSensor {
    private const val TAG = "LightSensor"
    private val detector = HarshLightDetector()
    internal val session = LightSensorSession(detector)
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var dwellJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isBoostedFlow = MutableStateFlow(false)
    val isBoostedFlow: StateFlow<Boolean> = _isBoostedFlow.asStateFlow()

    /**
     * How strongly the boost should apply, in `0f..1f`. Prefer this over [isBoostedFlow] for
     * anything that paints: it carries the proportional strength, and the theme fades between
     * levels instead of cutting. [isBoostedFlow] remains the right signal for decisions that are
     * genuinely binary, such as the screen-brightness override.
     */
    private val _boostLevelFlow = MutableStateFlow(0f)
    val boostLevelFlow: StateFlow<Float> = _boostLevelFlow.asStateFlow()

    val isBoosted: Boolean get() = detector.isBoosted
    val boostLevel: Float get() = detector.boostLevel
    val lastLux: Float get() = detector.lastLux

    /**
     * Checks if the device has an ambient light sensor.
     * When this returns false, the UI degrades from Auto to a manual toggle.
     */
    fun hasSensor(context: Context): Boolean {
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return sm?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }

    @Synchronized
    fun start(context: Context): Boolean {
        return session.beginNewSession {
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sm == null) {
                false
            } else {
                val lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
                if (lightSensor == null) {
                    false
                } else {
                    val l = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            val lux = event.values[0]
                            val nowMs = nowMonotonicMs()
                            val boosted = detector.submitLux(lux, nowMs)
                            if (_isBoostedFlow.value != boosted) {
                                // Log transitions only: the Xiaomi delivers ~5 light events a
                                // second, and a line per event was the loudest thing in the
                                // rider's logcat while saying nothing new.
                                Log.d(TAG, "Boost ${if (boosted) "ON" else "OFF"} at $lux lux (level=${detector.boostLevel})")
                                _isBoostedFlow.value = boosted
                            }
                            // Published on every sample, not only on gate flips: while the gate
                            // stays open the strength keeps tracking the light.
                            if (_boostLevelFlow.value != detector.boostLevel) {
                                _boostLevelFlow.value = detector.boostLevel
                            }

                            dwellJob?.cancel()
                            if (detector.isPendingTransition) {
                                dwellJob = coroutineScope.launch {
                                    delay(detector.dwellMs + 100)
                                    val updated = detector.evaluate(nowMonotonicMs())
                                    Log.d(TAG, "Dwell completed: updated boosted=$updated level=${detector.boostLevel}")
                                    if (_isBoostedFlow.value != updated) {
                                        _isBoostedFlow.value = updated
                                    }
                                    if (_boostLevelFlow.value != detector.boostLevel) {
                                        _boostLevelFlow.value = detector.boostLevel
                                    }
                                }
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    val registered = sm.registerListener(l, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    if (registered) {
                        sensorManager = sm
                        listener = l
                        Log.d(TAG, "LightSensor registered successfully")
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        dwellJob?.cancel()
        dwellJob = null
        session.endSession {
            val sm = sensorManager
            val l = listener
            if (sm != null && l != null) {
                sm.unregisterListener(l)
            }
            sensorManager = null
            listener = null
        }
        _isBoostedFlow.value = false
        _boostLevelFlow.value = 0f
        Log.d(TAG, "LightSensor stopped")
    }

    fun nowMonotonicMs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000_000L
}
