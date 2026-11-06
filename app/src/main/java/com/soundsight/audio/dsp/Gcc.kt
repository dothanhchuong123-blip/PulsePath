package com.soundsight.audio.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sqrt

enum class Weighting { PHAT, COHERENCE }

/** Whitening floor as a fraction of the strongest in-band bin (-40 dB). See gcc(). */
private const val PHAT_REGULARISATION = 0.01

/**
 * @param lagSamples fractional lag of the cross-correlation peak.
 *
 *   SIGN CONVENTION - read this before touching anything downstream.
 *
 *   lagSamples is the argument that maximises  c(tau) = sum_i left[i+tau] * right[i].
 *   A POSITIVE lag therefore means the waveform appears LATER in the `left` array
 *   than in the `right` array.
 *
 *   This is the SAME sign convention as StereoProbe.findBestLag() in P13, which is
 *   what produced the on-device measurements the DirectionGate thresholds are built
 *   on (clap on the left -> +7, clap on the right -> -8, 15/08/2026, Xiaomi 13 -
 *   model 2211133C "fuxi"; the identity was resolved from the device on 2026-08-24,
 *   correcting earlier "13 Pro" comments).
 *   The physical left/right mapping is therefore an EMPIRICAL fact about this
 *   hardware's channel ordering, recorded in DirectionGate, not something derived
 *   here. Do not "fix" the sign of this function to match intuition: it would
 *   silently invert every direction the app ever shows, with no crash and no test
 *   failure anywhere else.
 *
 * @param peakValue value of the weighted correlation at the peak (arbitrary scale).
 * @param secondPeakValue highest value at least 3 samples away from the peak.
 * @param peakRatio peakValue / max(secondPeakValue, 1e-12). Gate 5 uses this.
 * @param normalizedCorrelation Pearson-style normalised cross-correlation of the
 *   band-limited channels at the integer peak lag, in -1..1. This - NOT peakValue -
 *   is what DirectionGate compares against 0.5 / 0.3, because those two numbers came
 *   from P13's normalised correlation (0.826 stationary, 0.31-0.38 hand claps).
 */
data class GccResult(
    val lagSamples: Double,
    val peakValue: Double,
    val secondPeakValue: Double,
    val peakRatio: Double,
    val normalizedCorrelation: Double
)

/**
 * Reusable scratch storage for a serial GCC caller.
 *
 * This object is intentionally not thread-safe. Keep one workspace per serial analyser/coroutine;
 * callers that cannot guarantee exclusive access should use the public [gcc] overload, which
 * creates isolated storage for every invocation and therefore retains its previous thread-safety.
 */
internal class GccWorkspace {
    private val buffersByShape = ArrayList<GccBuffers>(2)

    /** Number of scratch DoubleArrays retained by this workspace (diagnostic for tests). */
    internal var arrayAllocationCount: Int = 0
        private set

    /** DoubleArray payload bytes retained by this workspace (object headers are not included). */
    internal var arrayAllocationBytes: Long = 0L
        private set

    internal fun array(size: Int): DoubleArray {
        arrayAllocationCount++
        arrayAllocationBytes += size.toLong() * 8L
        return DoubleArray(size)
    }

    internal fun buffers(
        leftSize: Int,
        rightSize: Int,
        fftSize: Int,
        span: Int,
        maxLagSamples: Int
    ): GccBuffers {
        // Avoid allocating a compound map key on every hot-path lookup. A live analyser has only
        // two shapes (whole window and sub-window), so a tiny indexed scan is cheaper and stable.
        var index = 0
        while (index < buffersByShape.size) {
            val candidate = buffersByShape[index]
            if (candidate.matches(leftSize, rightSize, fftSize, span, maxLagSamples)) {
                return candidate
            }
            index++
        }
        return GccBuffers(
            fftSize = fftSize,
            span = span,
            leftSize = leftSize,
            rightSize = rightSize,
            maxLagSamples = maxLagSamples,
            workspace = this
        ).also(buffersByShape::add)
    }
}

internal class GccFilterBuffers(size: Int, workspace: GccWorkspace) {
    val re: DoubleArray = workspace.array(size)
    val im: DoubleArray = workspace.array(size)
}

internal class GccBuffers(
    private val fftSize: Int,
    private val span: Int,
    private val leftSize: Int,
    private val rightSize: Int,
    private val maxLagSamples: Int,
    private val workspace: GccWorkspace
) {
    val lRe: DoubleArray = workspace.array(fftSize)
    val lIm: DoubleArray = workspace.array(fftSize)
    val rRe: DoubleArray = workspace.array(fftSize)
    val rIm: DoubleArray = workspace.array(fftSize)
    val xRe: DoubleArray = workspace.array(fftSize)
    val xIm: DoubleArray = workspace.array(fftSize)
    val corr: DoubleArray = workspace.array(span)
    val leftFilter: GccFilterBuffers? = filterBuffers(leftSize)
    val rightFilter: GccFilterBuffers? = filterBuffers(rightSize)

    private var retainedCoherenceMagL: DoubleArray? = null
    private var retainedCoherenceMagR: DoubleArray? = null

    val coherenceMagL: DoubleArray
        get() = requireNotNull(retainedCoherenceMagL)

    val coherenceMagR: DoubleArray
        get() = requireNotNull(retainedCoherenceMagR)

    private fun filterBuffers(signalSize: Int): GccFilterBuffers? =
        if (signalSize == 0) null else GccFilterBuffers(nextPow2(signalSize), workspace)

    fun ensureCoherenceMagnitudes(half: Int) {
        if (retainedCoherenceMagL == null || retainedCoherenceMagR == null) {
            retainedCoherenceMagL = workspace.array(half + 1)
            retainedCoherenceMagR = workspace.array(half + 1)
        }
    }

    fun matches(
        leftSize: Int,
        rightSize: Int,
        fftSize: Int,
        span: Int,
        maxLagSamples: Int
    ): Boolean =
        this.leftSize == leftSize &&
            this.rightSize == rightSize &&
            this.fftSize == fftSize &&
            this.span == span &&
            this.maxLagSamples == maxLagSamples
}

fun gcc(
    left: DoubleArray,
    right: DoubleArray,
    sampleRate: Int,
    lowHz: Double = 150.0,
    highHz: Double = 1000.0,
    maxLagSamples: Int = 32,
    weighting: Weighting = Weighting.PHAT
): GccResult = gcc(
    left = left,
    right = right,
    sampleRate = sampleRate,
    lowHz = lowHz,
    highHz = highHz,
    maxLagSamples = maxLagSamples,
    weighting = weighting,
    workspace = GccWorkspace()
)

/** Scratch-array-allocation-free after warm-up for fixed input shapes; [workspace] is single-owner. */
internal fun gcc(
    left: DoubleArray,
    right: DoubleArray,
    sampleRate: Int,
    lowHz: Double,
    highHz: Double,
    maxLagSamples: Int,
    weighting: Weighting,
    workspace: GccWorkspace
): GccResult {
    val n = max(left.size, right.size)
    require(n > 0) { "gcc() needs a non-empty signal" }
    require(maxLagSamples in 1 until n) { "maxLagSamples out of range: $maxLagSamples" }

    // 1. Zero-pad to at least 2n so the circular correlation cannot wrap.
    val fftSize = nextPow2(2 * n)

    // The wide lag window is also part of the buffer shape. Compute it before acquiring storage.
    val wide = minOf(fftSize / 2 - 1, maxOf(6 * maxLagSamples, 256))
    val span = 2 * wide + 1
    val buffers = workspace.buffers(
        leftSize = left.size,
        rightSize = right.size,
        fftSize = fftSize,
        span = span,
        maxLagSamples = maxLagSamples
    )

    val lRe = buffers.lRe; val lIm = buffers.lIm
    val rRe = buffers.rRe; val rIm = buffers.rIm
    lRe.fill(0.0); lIm.fill(0.0)
    rRe.fill(0.0); rIm.fill(0.0)
    // Hann window before padding. Without it, the hard edges of the clip leak across
    // the whole band, and PHAT then whitens that leakage up to full weight - measured
    // as a delay biased ~2.5 samples toward zero (12.0 read as 9.4).
    applyHannInto(left, lRe)
    applyHannInto(right, rRe)

    // 2. Forward transforms and cross-spectrum X = L * conj(R).
    fft(lRe, lIm)
    fft(rRe, rIm)

    val xRe = buffers.xRe
    val xIm = buffers.xIm
    for (k in 0 until fftSize) {
        xRe[k] = lRe[k] * rRe[k] + lIm[k] * rIm[k]
        xIm[k] = lIm[k] * rRe[k] - lRe[k] * rIm[k]
    }

    // 3. Restrict to the ITD band. Above ~1143 Hz the phase wraps within the
    //    microphone spacing and manufactures false peaks.
    maskBandInPlace(xRe, xIm, sampleRate, lowHz, highHz)

    // 4. Weighting. Computed for bins 0..fftSize/2 and applied to the mirror too,
    //    so the spectrum stays Hermitian and the inverse transform stays real.
    val half = fftSize / 2

    // Regularisation floor: bins more than 40 dB below the strongest in-band bin are
    // not whitened up to full weight. Textbook PHAT (floor = 0) divides every bin by
    // its own magnitude, which promotes near-empty leakage bins - carrying essentially
    // arbitrary phase - to the same authority as the bins that actually hold the horn.
    var maxMag = 0.0
    for (k in 0..half) {
        val mag = sqrt(xRe[k] * xRe[k] + xIm[k] * xIm[k])
        if (mag > maxMag) maxMag = mag
    }
    val floor = PHAT_REGULARISATION * maxMag + 1e-12

    when (weighting) {
        Weighting.PHAT -> {
            for (k in 0..half) {
                val mag = sqrt(xRe[k] * xRe[k] + xIm[k] * xIm[k])
                if (mag <= 0.0) continue
                val w = 1.0 / (mag + floor)
                applyWeight(xRe, xIm, k, fftSize, w)
            }
        }
        Weighting.COHERENCE -> {
            // Magnitude-squared coherence smoothed over 3 neighbouring bins. With a
            // single frame the unsmoothed MSC is identically 1, so the smoothing is
            // what carries the information: bins where L and R disagree in phase
            // across neighbours (wind, uncorrelated noise) get pushed toward 0.
            buffers.ensureCoherenceMagnitudes(half)
            val magL = buffers.coherenceMagL
            val magR = buffers.coherenceMagR
            for (k in 0..half) {
                magL[k] = sqrt(lRe[k] * lRe[k] + lIm[k] * lIm[k])
                magR[k] = sqrt(rRe[k] * rRe[k] + rIm[k] * rIm[k])
            }
            for (k in 0..half) {
                val mag = sqrt(xRe[k] * xRe[k] + xIm[k] * xIm[k])
                if (mag <= 0.0) continue
                var sRe = 0.0; var sIm = 0.0; var sL = 0.0; var sR = 0.0
                for (j in (k - 1)..(k + 1)) {
                    if (j < 0 || j > half) continue
                    sRe += xRe[j]; sIm += xIm[j]
                    sL += magL[j] * magL[j]
                    sR += magR[j] * magR[j]
                }
                val msc = ((sRe * sRe + sIm * sIm) / (sL * sR + 1e-24)).coerceIn(0.0, 1.0)
                val w = msc / (magL[k] * magR[k] + floor)
                applyWeight(xRe, xIm, k, fftSize, w)
            }
        }
    }

    // 5. Back to the lag domain.
    ifft(xRe, xIm)

    // 6. Build the lag domain over a WIDE window, but search for the peak only inside
    //    the physically reachable lags. Index for lag tau is tau mod fftSize, which is
    //    the fftshift written as an index map instead of a copy.
    //
    //    The wide window exists for step 8: a competing lobe that beats ours from
    //    outside the plausible range is exactly the evidence that we are looking at
    //    noise rather than a wavefront.
    val corr = buffers.corr
    for (i in 0 until span) {
        val tau = i - wide
        corr[i] = xRe[((tau % fftSize) + fftSize) % fftSize]
    }

    val searchLo = wide - maxLagSamples
    val searchHi = wide + maxLagSamples
    var peakIdx = searchLo
    for (i in searchLo..searchHi) if (corr[i] > corr[peakIdx]) peakIdx = i

    // 7. Parabolic interpolation over the three samples around the peak.
    var delta = 0.0
    if (peakIdx in 1 until span - 1) {
        val ym = corr[peakIdx - 1]
        val y0 = corr[peakIdx]
        val yp = corr[peakIdx + 1]
        val denom = ym - 2.0 * y0 + yp
        if (abs(denom) > 1e-18) {
            delta = (0.5 * (ym - yp) / denom).coerceIn(-1.0, 1.0)
        }
    }
    val lagSamples = (peakIdx - wide) + delta

    // 8. Runner-up peak, measured OUTSIDE the main lobe.
    //
    //    A fixed "at least 3 samples away" exclusion does not work here and the
    //    failure is silent. Band-limiting to 150-1000 Hz makes the correlation's main
    //    lobe roughly 48000/850 ~= 56 samples wide, so a sample 3 away is still on the
    //    same lobe and the ratio comes out ~1.00 for a perfectly clean signal - which
    //    would make a peakRatio > 1.8 gate reject every event ever recorded, with no
    //    error anywhere. Measured on synthetic signals: 1.01 at 20 dB SNR.
    //
    //    So: walk down from the peak until the curve turns back up, and only then look
    //    for a competitor - across the WIDE window, since inside +/-32 lags a
    //    band-limited signal only ever has the one lobe (noise included, measured).
    var lo = peakIdx
    while (lo > 0 && corr[lo - 1] < corr[lo]) lo--
    var hi = peakIdx
    while (hi < span - 1 && corr[hi + 1] < corr[hi]) hi++

    var second = 0.0
    for (i in 0 until span) {
        if (i in lo..hi) continue
        if (corr[i] > second) second = corr[i]
    }

    val peakValue = corr[peakIdx]
    val peakRatio = (peakValue / max(second, 1e-12)).coerceIn(0.0, 100.0)

    // Normalised correlation on the band-limited channels, at the integer peak lag.
    // Same formula as StereoProbe.computeNormalizedCorrelation (P13) so the numbers
    // stay comparable to the measurements the gate thresholds came from.
    bandFilterInto(left, sampleRate, lowHz, highHz, buffers.leftFilter)
    bandFilterInto(right, sampleRate, lowHz, highHz, buffers.rightFilter)
    val normalized = normalizedCorrelationAt(
        left = buffers.leftFilter?.re,
        leftSize = left.size,
        right = buffers.rightFilter?.re,
        rightSize = right.size,
        lag = Math.round(lagSamples).toInt()
    )

    return GccResult(
        lagSamples = lagSamples,
        peakValue = peakValue,
        secondPeakValue = second,
        peakRatio = peakRatio,
        normalizedCorrelation = normalized
    )
}

/** Same operation order as applyHann(), but writes into reusable zero-padded storage. */
private fun applyHannInto(x: DoubleArray, out: DoubleArray) {
    val n = x.size
    if (n <= 1) {
        if (n == 1) out[0] = x[0]
        return
    }
    for (i in 0 until n) {
        out[i] = x[i] * 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
    }
}

private fun bandFilterInto(
    x: DoubleArray,
    sampleRate: Int,
    lowHz: Double,
    highHz: Double,
    buffers: GccFilterBuffers?
) {
    if (x.isEmpty()) return
    requireNotNull(buffers)
    buffers.re.fill(0.0)
    buffers.im.fill(0.0)
    x.copyInto(buffers.re)
    fft(buffers.re, buffers.im)
    maskBandInPlace(buffers.re, buffers.im, sampleRate, lowHz, highHz)
    ifft(buffers.re, buffers.im)
}

private fun applyWeight(xRe: DoubleArray, xIm: DoubleArray, k: Int, n: Int, w: Double) {
    xRe[k] *= w
    xIm[k] *= w
    val mirror = (n - k) % n
    if (mirror != k) {
        xRe[mirror] *= w
        xIm[mirror] *= w
    }
}

/** c(lag) = sum_i left[i+lag] * right[i], normalised. Identical to P13's helper. */
fun normalizedCorrelationAt(left: DoubleArray, right: DoubleArray, lag: Int): Double {
    return normalizedCorrelationAt(left, left.size, right, right.size, lag)
}

private fun normalizedCorrelationAt(
    left: DoubleArray?,
    leftSize: Int,
    right: DoubleArray?,
    rightSize: Int,
    lag: Int
): Double {
    val n = minOf(leftSize, rightSize)
    val startL = if (lag >= 0) lag else 0
    val startR = if (lag >= 0) 0 else -lag
    val length = n - abs(lag)
    if (length <= 0) return 0.0

    requireNotNull(left)
    requireNotNull(right)

    var sumLR = 0.0
    var sumL2 = 0.0
    var sumR2 = 0.0
    for (i in 0 until length) {
        val l = left[startL + i]
        val r = right[startR + i]
        sumLR += l * r
        sumL2 += l * l
        sumR2 += r * r
    }
    val denom = sqrt(sumL2 * sumR2)
    return if (denom > 1e-12) sumLR / denom else 0.0
}
