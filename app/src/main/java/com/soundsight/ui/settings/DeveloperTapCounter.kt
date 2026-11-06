package com.soundsight.ui.settings

import android.os.SystemClock

/**
 * Tracks taps toward unlocking Developer Mode in Settings.
 *
 * Rules:
 * - 7 taps to unlock.
 * - If more than [timeoutMs] (~2000 ms) pass between consecutive taps, the count resets to 1.
 * - Taps 1 to 3 produce [TapResult.Ignored] (silent).
 * - Taps 4 to 6 produce [TapResult.Countdown] with steps remaining (3, 2, 1).
 * - Tap 7 produces [TapResult.Unlocked].
 * - If already unlocked, tapping produces [TapResult.AlreadyUnlocked].
 * - Count is held in local memory and resets on timeout, unlock, or screen exit.
 */
class DeveloperTapCounter(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    val timeoutMs: Long = 2000L,
    val requiredTaps: Int = 7
) {
    var tapCount: Int = 0
        private set

    private var lastTapTimeMs: Long = 0L

    sealed interface TapResult {
        data object Ignored : TapResult
        data class Countdown(val stepsRemaining: Int) : TapResult
        data object Unlocked : TapResult
        data object AlreadyUnlocked : TapResult
    }

    fun onVersionTap(isAlreadyUnlocked: Boolean): TapResult {
        if (isAlreadyUnlocked) {
            tapCount = 0
            lastTapTimeMs = 0L
            return TapResult.AlreadyUnlocked
        }

        val now = clock()
        if (lastTapTimeMs == 0L || now - lastTapTimeMs > timeoutMs) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTimeMs = now

        return when {
            tapCount >= requiredTaps -> {
                tapCount = 0
                lastTapTimeMs = 0L
                TapResult.Unlocked
            }
            tapCount >= 4 -> {
                TapResult.Countdown(requiredTaps - tapCount)
            }
            else -> {
                TapResult.Ignored
            }
        }
    }

    fun reset() {
        tapCount = 0
        lastTapTimeMs = 0L
    }
}
