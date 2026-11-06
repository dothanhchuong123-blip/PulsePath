package com.soundsight.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative in-place radix-2 Cooley-Tukey FFT.
 *
 * Pure Kotlin, zero android.* imports, so audio/dsp/ stays unit testable on the JVM.
 *
 * Twiddle factors are precomputed per transform size and cached, which keeps the
 * numerical error flat across stages (a running twiddle recurrence drifts, and a
 * drifting twiddle produces a *plausible but wrong* correlation peak rather than a
 * crash - the exact failure mode this layer must not have).
 */

private val twiddleCache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

@Synchronized
private fun twiddles(n: Int): Pair<DoubleArray, DoubleArray> = twiddleCache.getOrPut(n) {
    val half = n / 2
    val c = DoubleArray(half)
    val s = DoubleArray(half)
    for (k in 0 until half) {
        val ang = -2.0 * PI * k / n
        c[k] = cos(ang)
        s[k] = sin(ang)
    }
    c to s
}

fun nextPow2(n: Int): Int {
    require(n > 0) { "nextPow2 requires n > 0, got $n" }
    var p = 1
    while (p < n) p = p shl 1
    return p
}

/** Forward FFT, in place. Unnormalised. */
fun fft(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

/** Inverse FFT, in place. Scaled by 1/n, so ifft(fft(x)) == x. */
fun ifft(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = true)

private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
    val n = re.size
    require(im.size == n) { "re and im must have the same length ($n vs ${im.size})" }
    require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
    if (n == 1) return

    // Bit-reversal permutation.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }

    val (cosT, sinT) = twiddles(n)
    val sinSign = if (inverse) -1.0 else 1.0

    var len = 2
    while (len <= n) {
        val half = len shr 1
        val stride = n / len
        var base = 0
        while (base < n) {
            for (k in 0 until half) {
                val t = k * stride
                val wRe = cosT[t]
                val wIm = sinT[t] * sinSign
                val a = base + k
                val b = a + half
                val vRe = re[b] * wRe - im[b] * wIm
                val vIm = re[b] * wIm + im[b] * wRe
                re[b] = re[a] - vRe
                im[b] = im[a] - vIm
                re[a] += vRe
                im[a] += vIm
            }
            base += len
        }
        len = len shl 1
    }

    if (inverse) {
        val inv = 1.0 / n
        for (i in 0 until n) {
            re[i] *= inv
            im[i] *= inv
        }
    }
}
