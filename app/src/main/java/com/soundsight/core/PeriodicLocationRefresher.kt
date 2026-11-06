package com.soundsight.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic one-shot GNSS refresh for [LocationMode.COLLISION_ONLY] (shown to the rider as
 * "Auto" / "Tự động" - see [Strings.locationModeCollisionOnly]): a fresh fix every
 * [REFRESH_INTERVAL_MS], not a continuous stream. This keeps the position a real collision
 * message would fall back on from going stale for hours, at a fraction of [SpeedProvider]'s 1 Hz
 * battery cost, and it never populates a live speed readout - that stays [LocationMode.ALWAYS]-only.
 *
 * Reuses [LocationHelper.freshFix] unchanged: the exact same one-shot `requestSingleUpdate`
 * pattern already used, without claiming the location foreground-service type, for the
 * collision-time fix from the crash screen. Extending that established, already-accepted pattern
 * to a timer inside [com.soundsight.audio.ListenerService] rather than inventing a second location
 * path keeps the one precedent this project already leans on for why `COLLISION_ONLY` needs no
 * FGS location type (see `foregroundServiceTypes` in ListenerService.kt).
 *
 * **PROVISIONAL, NOT MEASURED.** [REFRESH_INTERVAL_MS] is a considered placeholder, not a
 * measured value - unlike [com.soundsight.domain.DirectionGate]'s constants. What would settle
 * it: a battery-cost comparison (Off vs this mode vs Always-on, same protocol as
 * [LocationBatteryCost]) and how stale a "someone can find me" message is allowed to be before it
 * stops being useful.
 */
object PeriodicLocationRefresher {
    const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
    private const val FIX_TIMEOUT_MS = 8_000L

    @Volatile
    private var job: Job? = null

    /** Idempotent: a duplicate start while already running does not reschedule. */
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            runPeriodicRefreshLoop(
                intervalMs = REFRESH_INTERVAL_MS,
                isActiveFn = { isActive },
                delayFn = { delay(it) },
                refresh = { LocationHelper.freshFix(appContext, timeoutMs = FIX_TIMEOUT_MS) }
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * The refresh loop's timing, extracted as a plain suspend function so it is unit-testable with
 * fake clock/refresh hooks and no Android or real coroutine delay involved - the same pattern
 * this project uses elsewhere (e.g. [com.soundsight.audio.foregroundServiceTypes]) to keep a
 * safety-relevant decision testable without a device.
 *
 * Waits [intervalMs] before every refresh, including the first - a mode switch or service start
 * must not fire an immediate extra fix on top of whatever mode change already triggered it.
 *
 * A failing [refresh] (a timed-out fix, no provider, a permission race) is swallowed - best
 * effort, a missed tick just means the next one tries again - but [CancellationException] is
 * always rethrown, the same rule [com.soundsight.audio.ListenerService]'s own capture loop
 * follows, so cancelling the surrounding coroutine (service stop, mode switch) still actually
 * stops the loop instead of being silently caught as just another failed refresh.
 */
internal suspend fun runPeriodicRefreshLoop(
    intervalMs: Long,
    isActiveFn: () -> Boolean,
    delayFn: suspend (Long) -> Unit,
    refresh: suspend () -> Unit
) {
    while (isActiveFn()) {
        delayFn(intervalMs)
        if (!isActiveFn()) break
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
