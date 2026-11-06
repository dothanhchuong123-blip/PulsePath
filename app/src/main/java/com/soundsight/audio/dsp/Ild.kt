package com.soundsight.audio.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * Interaural level difference. Positive ildDb means the LEFT channel is louder.
 *
 * TELEMETRY ONLY. Must NOT be used to decide direction.
 *
 * Measured on device 15/08/2026 (Xiaomi 13, model 2211133C "fuxi" - identity resolved from the
 * device 2026-08-24; UNPROCESSED 48 kHz stereo): the two
 * channels differ by a FIXED ~3.2 dB microphone sensitivity offset (3.22 / 3.28 /
 * 2.78 / 4.00 dB across four runs). After removing that offset, the direction-induced
 * ILD was 0.15 dB for a clap on the left and -0.06 dB for a clap on the right - it
 * does not even change sign. The phone body is too thin to cast a usable acoustic
 * shadow, so the duplex-theory high-band path does not exist on this hardware.
 *
 * Kept because measuring it and reporting the null result is worth more in the
 * submission than a second signal that manufactures false confidence.
 * Do not wire this into DirectionGate.
 */
data class IldResult(
    val ildDb: Double,
    val rmsLeftDb: Double,
    val rmsRightDb: Double
)

fun ild(
    left: DoubleArray,
    right: DoubleArray,
    sampleRate: Int,
    lowHz: Double = 2000.0,
    highHz: Double = 6000.0
): IldResult {
    val rl = bandRms(left, sampleRate, lowHz, highHz)
    val rr = bandRms(right, sampleRate, lowHz, highHz)
    return IldResult(
        ildDb = 20.0 * log10(max(rl, 1e-12) / max(rr, 1e-12)),
        rmsLeftDb = toDbFs(rl),
        rmsRightDb = toDbFs(rr)
    )
}
