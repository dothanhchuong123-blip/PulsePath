package com.soundsight.audio.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Frequency-domain band limiting.
 *
 * Deliberately NOT an IIR filter: brick-wall masking in the frequency domain is
 * deterministic, has zero phase distortion (an IIR biquad shifts phase, and phase
 * IS the measurement in GCC), and is testable to an exact number.
 */

/** Hann window, returns a new array. */
fun applyHann(x: DoubleArray): DoubleArray {
    val n = x.size
    if (n <= 1) return x.copyOf()
    val out = DoubleArray(n)
    for (i in 0 until n) {
        out[i] = x[i] * 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
    }
    return out
}

/**
 * Zeroes every bin outside [lowHz, highHz] and its conjugate mirror, in place.
 * Applied symmetrically so the spectrum stays Hermitian and the inverse FFT
 * comes back real.
 */
internal fun maskBandInPlace(
    re: DoubleArray,
    im: DoubleArray,
    sampleRate: Int,
    lowHz: Double,
    highHz: Double
) {
    val n = re.size
    val binHz = sampleRate.toDouble() / n
    val kLow = ceil(lowHz / binHz).toInt()
    val kHigh = floor(highHz / binHz).toInt().coerceAtMost(n / 2)
    for (k in 0..n / 2) {
        if (k in kLow..kHigh) continue
        re[k] = 0.0
        im[k] = 0.0
        val mirror = (n - k) % n
        re[mirror] = 0.0
        im[mirror] = 0.0
    }
}

/** Band-limited copy of x, same length as x, zero phase shift. */
fun bandFilter(x: DoubleArray, sampleRate: Int, lowHz: Double, highHz: Double): DoubleArray {
    if (x.isEmpty()) return DoubleArray(0)
    val n = nextPow2(x.size)
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    x.copyInto(re)
    fft(re, im)
    maskBandInPlace(re, im, sampleRate, lowHz, highHz)
    ifft(re, im)
    return re.copyOf(x.size)
}

/** RMS of x restricted to [lowHz, highHz]. */
fun bandRms(x: DoubleArray, sampleRate: Int, lowHz: Double, highHz: Double): Double {
    if (x.isEmpty()) return 0.0
    val filtered = bandFilter(x, sampleRate, lowHz, highHz)
    var sum = 0.0
    for (v in filtered) sum += v * v
    return sqrt(sum / filtered.size)
}

/** RMS in dBFS, floored at -120 dB. Matches StereoProbe's convention (P13). */
fun toDbFs(rms: Double): Double {
    if (rms <= 1e-9) return -120.0
    return 20.0 * kotlin.math.log10(rms)
}
