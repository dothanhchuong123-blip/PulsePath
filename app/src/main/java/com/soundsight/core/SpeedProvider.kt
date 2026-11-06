package com.soundsight.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One GNSS speed observation. Built only from fixes where [Location.hasSpeed] is true: GNSS
 * speed comes from Doppler and is good, while speed differentiated from successive positions is
 * not - so this app never computes speed itself, and a fix without a native speed never becomes
 * a sample at all.
 *
 * [fixAgeMs] is carried because a speed reading the UI cannot date is a speed reading the UI
 * must not show. [receivedMonotonicMs] is the elapsed-realtime stamp of arrival, so
 * [SpeedResolver] can age the sample on a clock that cannot jump.
 */
data class SpeedSample(
    val speedMps: Float,
    val accuracyM: Float,
    val fixAgeMs: Long,
    val receivedMonotonicMs: Long
)

/** How the HUD may present a speed. [HELD] is the under-bridge state: last value, visibly stale. */
enum class SpeedDisplayState { LIVE, HELD, NO_FIX }

data class SpeedReading(
    val kmh: Float?,
    val state: SpeedDisplayState,
    val accuracyM: Float,
    val fixAgeMs: Long
)

/**
 * Pure ageing logic for GNSS speed samples - plain Kotlin so the hold/expiry contract is
 * unit-testable with no device.
 *
 * A fresh fix reads [SpeedDisplayState.LIVE]. When fixes stop arriving - under a bridge, in a
 * tunnel - the last value is HELD, visibly marked stale, for [HOLD_WINDOW_MS]; past that it
 * blanks to [SpeedDisplayState.NO_FIX]. A frozen last value presented as current would be a
 * lie, and a blank the instant a fix is 3.1 seconds old would flicker at every urban canyon;
 * the two-stage ageing is the honest middle.
 */
object SpeedResolver {
    const val LIVE_MAX_AGE_MS = 3_000L
    const val HOLD_WINDOW_MS = 8_000L

    fun resolve(sample: SpeedSample?, nowMonotonicMs: Long): SpeedReading {
        if (sample == null) return SpeedReading(null, SpeedDisplayState.NO_FIX, 0f, 0)
        val ageNowMs = sample.fixAgeMs + (nowMonotonicMs - sample.receivedMonotonicMs).coerceAtLeast(0L)
        val kmh = if (sample.speedMps < SpeedProvider.SPEED_FLOOR_MPS) 0f else sample.speedMps * 3.6f
        return when {
            ageNowMs <= LIVE_MAX_AGE_MS -> SpeedReading(kmh, SpeedDisplayState.LIVE, sample.accuracyM, ageNowMs)
            ageNowMs <= HOLD_WINDOW_MS -> SpeedReading(kmh, SpeedDisplayState.HELD, sample.accuracyM, ageNowMs)
            else -> SpeedReading(null, SpeedDisplayState.NO_FIX, 0f, ageNowMs)
        }
    }
}

/**
 * Streaming GNSS speed at 1 Hz, next to (never replacing) [LocationHelper]'s one-shot cached
 * lookup. Stays on the framework [LocationManager]: `play-services-location` is deliberately
 * absent from this project and adding it is out of scope.
 *
 * Only [ALWAYS][LocationMode.ALWAYS] starts this. Callbacks run on a private HandlerThread so
 * the 1 Hz delivery never touches the main thread; the flow holds only the latest sample, so a
 * slow collector cannot back up location delivery.
 */
object SpeedProvider {
    private const val THREAD_NAME = "SpeedProviderGps"
    private const val INTERVAL_MS = 1_000L

    /**
     * Absolute speed floor in m/s (~2.52 km/h).
     * Speeds below this floor clamp to 0.0 m/s (NOT to blank / null) so a stationary rider
     * stopped at traffic lights or with the device resting on a desk sees a steady 0.
     */
    const val SPEED_FLOOR_MPS = 0.7f

    /**
     * Multiplier k for GNSS speed accuracy rejection.
     * At k = 2.0, this corresponds to the 2-sigma (~95.4%) confidence interval of a
     * Gaussian velocity error distribution. A reported speed below 2.0 * speedAccuracy
     * is statistically indistinguishable from zero-velocity Doppler receiver jitter
     * and must be rejected to prevent stationary drift.
     */
    const val K_SPEED_ACCURACY = 2.0f

    /**
     * Rejection threshold for horizontal position accuracy in meters.
     * Fixes with horizontal accuracy worse than 25m indicate degraded satellite geometry,
     * urban canyons, or severe multipath where native Doppler speed estimates are unreliable.
     */
    const val MAX_ACCURACY_M = 25.0f

    private val _sample = MutableStateFlow<SpeedSample?>(null)
    val sample: StateFlow<SpeedSample?> = _sample.asStateFlow()

    @Volatile
    private var running = false
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var thread: HandlerThread? = null

    // Hysteresis tracking:
    // Requires two consecutive above-floor samples to leave zero, preventing HUD chatter
    // around the floor threshold. A single below-floor sample immediately returns to zero.
    @Volatile
    private var consecutiveAboveFloor = 0
    @Volatile
    private var isMoving = false

    @Synchronized
    internal fun resetForTesting() {
        consecutiveAboveFloor = 0
        isMoving = false
        _sample.value = null
    }

    /** True when a fix carried a usable native speed; everything else is discarded. */
    @Synchronized
    internal fun toSample(location: Location, receivedMonotonicMs: Long): SpeedSample? {
        if (!location.hasSpeed()) return null
        if (location.speed < 0f) return null

        // Reject fixes with poor horizontal accuracy outright.
        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > MAX_ACCURACY_M) {
            return null
        }

        val rawSpeed = location.speed

        // Below the absolute floor (~0.7 m/s ≈ 2.5 km/h), the reading clamps to 0 (NOT null/blank)
        // so a stopped rider sees zero, not "no fix". One below-floor sample immediately returns
        // the state to zero.
        if (rawSpeed < SPEED_FLOOR_MPS) {
            consecutiveAboveFloor = 0
            isMoving = false
            return SpeedSample(
                speedMps = 0f,
                accuracyM = location.accuracy,
                fixAgeMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L),
                receivedMonotonicMs = receivedMonotonicMs
            )
        }

        // When rawSpeed is above the floor, check speed accuracy if available (API 26+).
        // At k = 2.0 (2-sigma, ~95.4% confidence interval), any sample where speed < k * speedAccuracy
        // is statistically indistinguishable from Doppler noise (e.g. stationary drift spikes
        // of 0.7-1.5 m/s or degraded GNSS fixes). Such samples are rejected outright as noise.
        if (location.hasSpeedAccuracy() && location.speedAccuracyMetersPerSecond > 0f) {
            if (rawSpeed < K_SPEED_ACCURACY * location.speedAccuracyMetersPerSecond) {
                return null
            }
        }

        // Hysteresis across the floor: two consecutive above-floor samples are required to
        // leave zero, preventing the display from chattering across the floor threshold.
        val filteredSpeed = if (isMoving) {
            rawSpeed
        } else {
            consecutiveAboveFloor++
            if (consecutiveAboveFloor >= 2) {
                isMoving = true
                rawSpeed
            } else {
                0f
            }
        }

        return SpeedSample(
            speedMps = filteredSpeed,
            accuracyM = location.accuracy,
            fixAgeMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L),
            receivedMonotonicMs = receivedMonotonicMs
        )
    }

    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        consecutiveAboveFloor = 0
        isMoving = false
        val handlerThread = HandlerThread(THREAD_NAME).apply { start() }
        val handler = Handler(handlerThread.looper)
        val l = object : LocationListener {
            // Explicit object, not a lambda: before API 30 LocationListener has four abstract
            // methods (no defaults), so a SAM lambda compiled against newer metadata would
            // crash with AbstractMethodError the moment an older framework called any of them.
            override fun onLocationChanged(location: Location) {
                val sample = try {
                    toSample(location, com.soundsight.audio.motion.MotionSensor.nowMonotonicMs())
                } catch (_: SecurityException) {
                    null
                } ?: return
                _sample.value = sample
            }

            override fun onProviderDisabled(provider: String) {}

            override fun onProviderEnabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Looper overload (API 30+): 1 Hz delivery never touches the main thread.
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    INTERVAL_MS,
                    0f,
                    l,
                    handler.looper
                )
            } else {
                // Pre-30: the four-argument overload delivers on the CALLING thread's looper,
                // so registration is posted onto our own handler thread to keep the main
                // thread out of the delivery path.
                handler.post {
                    try {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            INTERVAL_MS,
                            0f,
                            l
                        )
                    } catch (_: SecurityException) {
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
            manager = lm
            listener = l
            thread = handlerThread
            running = true
            true
        } catch (_: SecurityException) {
            quit(handlerThread)
            false
        } catch (_: IllegalArgumentException) {
            quit(handlerThread)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            listener?.let { manager?.removeUpdates(it) }
        } catch (_: Exception) {
            // The provider is going away anyway; nothing here is worth crashing a ride for.
        }
        listener = null
        manager = null
        thread?.quitSafely()
        thread = null
        running = false
        consecutiveAboveFloor = 0
        isMoving = false
        _sample.value = null
    }

    private fun quit(handlerThread: HandlerThread) {
        handlerThread.quitSafely()
    }
}
