package com.soundsight.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.HapticPattern

class Haptics(private val context: Context) {

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun play(pattern: HapticPattern) {
        if (pattern == HapticPattern.NONE) {
            cancel()
            return
        }

        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val baseTimings = AlertPresentation.getHapticTimings(pattern)
        if (baseTimings.isEmpty()) {
            cancel()
            return
        }

        val cycles = AlertPresentation.getHapticCycles(pattern)
        if (cycles <= 0) {
            cancel()
            return
        }

        val totalLength = baseTimings.size * cycles
        val timings = LongArray(totalLength)
        for (c in 0 until cycles) {
            System.arraycopy(baseTimings, 0, timings, c * baseTimings.size, baseTimings.size)
        }

        val effect: VibrationEffect = if (vib.hasAmplitudeControl()) {
            val amplitudes = IntArray(timings.size) { i ->
                // Even indices are "off" delays (except timing index 0 is initial off delay),
                // odd indices are "on" vibration intervals
                if (i % 2 == 1) 255 else 0
            }
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vib.vibrate(effect, attributes)
        } else {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vib.vibrate(effect, audioAttributes)
        }
    }

    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
    }
}
