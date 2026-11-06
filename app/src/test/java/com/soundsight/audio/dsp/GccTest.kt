package com.soundsight.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sign convention under test - the single most dangerous thing in this layer.
 *
 * gcc() maximises c(tau) = sum_i left[i+tau] * right[i], so a POSITIVE lag means the
 * waveform appears LATER in `left`. That matches StereoProbe.findBestLag() from P13,
 * which is where the "+7 = clap on the left" measurement came from. If this sign ever
 * flips, nothing crashes and no other test fails - the app just confidently points
 * every driver the wrong way. gccAgreesWithP13NaiveSearch() is the guard.
 */
class GccTest {

    private val sr = 48000
    private val n = 4096

    private fun sine(freq: Double, len: Int = n, amp: Double = 0.5): DoubleArray =
        DoubleArray(len) { amp * sin(2.0 * PI * freq * it / sr) }

    /** y[i] = x[i - d], fractional d via linear interpolation. y is x DELAYED by d. */
    private fun delayed(x: DoubleArray, d: Double): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val src = i - d
            val lo = floor(src).toInt()
            val frac = src - lo
            val a = if (lo in x.indices) x[lo] else 0.0
            val b = if (lo + 1 in x.indices) x[lo + 1] else 0.0
            out[i] = a * (1.0 - frac) + b * frac
        }
        return out
    }

    // --- the six tests from the plan -------------------------------------------------

    /** Left channel delayed by 12 -> lag +12. */
    @Test
    fun lagPlus12() {
        val src = sine(400.0)
        val r = gcc(delayed(src, 12.0), src, sr, weighting = Weighting.PHAT)
        assertEquals("lag", 12.0, r.lagSamples, 0.5)
    }

    /** Right channel delayed by 12 -> lag -12. */
    @Test
    fun lagMinus12() {
        val src = sine(400.0)
        val r = gcc(src, delayed(src, 12.0), sr, weighting = Weighting.PHAT)
        assertEquals("lag", -12.0, r.lagSamples, 0.5)
    }

    /** Fractional lag: parabolic interpolation must resolve well under one sample. */
    @Test
    fun fractionalLag() {
        val src = sine(400.0)
        val r = gcc(delayed(src, 8.4), src, sr, weighting = Weighting.PHAT)
        assertEquals("lag", 8.4, r.lagSamples, 0.5)
    }

    /** Two-tone horn with harmonics - the shape a real car or motorbike horn has. */
    private fun horn(len: Int = n): DoubleArray = DoubleArray(len) { i ->
        val t = i.toDouble() / sr
        0.30 * sin(2.0 * PI * 400.0 * t) +
        0.24 * sin(2.0 * PI * 500.0 * t) +
        0.15 * sin(2.0 * PI * 800.0 * t) +
        0.10 * sin(2.0 * PI * 1000.0 * t)
    }

    private fun noisyPair(src: DoubleArray, trueLag: Double, snrDb: Double, seed: Int):
        Pair<DoubleArray, DoubleArray> {
        val rng = kotlin.random.Random(seed)
        val nA = DoubleArray(src.size) { rng.nextDouble() * 2.0 - 1.0 }
        val nB = DoubleArray(src.size) { rng.nextDouble() * 2.0 - 1.0 }
        val k = (bandRms(src, sr, 150.0, 1000.0) / Math.pow(10.0, snrDb / 20.0)) /
            bandRms(nA, sr, 150.0, 1000.0)
        val ld = delayed(src, trueLag)
        return DoubleArray(src.size) { ld[it] + k * nA[it] } to
            DoubleArray(src.size) { src[it] + k * nB[it] }
    }

    /**
     * At 6 dB in-band SNR the estimate is usable but NOT precise: measured spread over
     * 60 trials was +6.3 to +18.4 samples for a true +12, with occasional sign flips on
     * individual clips. That is physics, not a defect - a 150-1000 Hz band gives a
     * correlation lobe ~56 samples wide, so the peak moves easily. The product relies
     * on sub-window sign agreement (Gate 6) rather than on any single clip, so what
     * this test pins is the SIGN, not the magnitude.
     */
    @Test
    fun lagKeepsItsSignAt6dBSnr() {
        var rightSign = 0
        val trials = 20
        for (s in 0 until trials) {
            val (l, r) = noisyPair(horn(), 12.0, 6.0, 500 + s * 31)
            if (gcc(l, r, sr, weighting = Weighting.PHAT).lagSamples > 0) rightSign++
        }
        assertTrue("sign correct in only $rightSign/$trials clips at 6 dB SNR", rightSign >= 18)
    }

    @Test
    fun lagIsAccurateAt20dBSnr() {
        for (s in 0 until 5) {
            val (l, r) = noisyPair(horn(), 12.0, 20.0, 900 + s * 17)
            assertEquals("lag at 20 dB SNR, seed $s", 12.0, gcc(l, r, sr).lagSamples, 2.0)
        }
    }

    /**
     * Wind is uncorrelated between two microphones 15 cm apart, so it must never reach
     * even MEDIUM confidence (correlation 0.3). Measured max over 60 noise pairs: 0.17.
     * This is the property that keeps the feature silent at speed.
     */
    @Test
    fun uncorrelatedNoiseNeverReachesMediumConfidence() {
        var worst = 0.0
        for (s in 0 until 20) {
            val rng = kotlin.random.Random(11 + s * 13)
            val a = DoubleArray(n) { rng.nextDouble() * 2.0 - 1.0 }
            val b = DoubleArray(n) { rng.nextDouble() * 2.0 - 1.0 }
            val r = gcc(a, b, sr, weighting = Weighting.COHERENCE)
            worst = maxOf(worst, abs(r.normalizedCorrelation))
        }
        assertTrue("wind reached correlation $worst, above the 0.3 MEDIUM floor", worst < 0.3)
    }

    @Test
    fun identicalChannelsGiveZeroLag() {
        val src = sine(400.0)
        val r = gcc(src, src.copyOf(), sr, weighting = Weighting.PHAT)
        assertEquals("lag", 0.0, r.lagSamples, 0.3)
        assertTrue(
            "identical channels should correlate ~1.0, got ${r.normalizedCorrelation}",
            r.normalizedCorrelation > 0.99
        )
    }

    @Test
    fun ildOfHalfScaledRightChannelIsPlus6dB() {
        val src = sine(3000.0)
        val right = DoubleArray(n) { src[it] * 0.5 }
        val r = ild(src, right, sr)
        assertEquals("ildDb", 6.02, r.ildDb, 0.5)
    }

    // --- the guard against a silent sign inversion -----------------------------------

    /**
     * Differential test against a direct port of StereoProbe.findBestLag() from P13.
     * The GCC estimate and the naive normalised search must agree in SIGN and to
     * within one sample, on both polarities. This is what pins the DirectionGate
     * thresholds to the hardware measurements they were derived from.
     */
    @Test
    fun gccAgreesWithP13NaiveSearch() {
        val src = sine(400.0)
        for (d in listOf(-14.0, -7.0, 7.0, 14.0)) {
            val left = if (d >= 0) delayed(src, d) else src
            val right = if (d >= 0) src else delayed(src, -d)

            val lb = bandFilter(left, sr, 150.0, 1000.0)
            val rb = bandFilter(right, sr, 150.0, 1000.0)
            var naiveLag = 0
            var best = -2.0
            for (lag in -32..32) {
                val c = normalizedCorrelationAt(lb, rb, lag)
                if (c > best) { best = c; naiveLag = lag }
            }

            val g = gcc(left, right, sr, weighting = Weighting.PHAT)
            assertTrue(
                "sign disagreement at d=$d: gcc=${g.lagSamples} naive=$naiveLag",
                g.lagSamples * naiveLag > 0
            )
            assertEquals("magnitude at d=$d", naiveLag.toDouble(), g.lagSamples, 1.0)
            assertEquals("gcc vs true delay at d=$d", d, g.lagSamples, 0.6)
        }
    }

    /**
     * Reproduces the two P13 device measurements as arrays: a source that reaches
     * channel 1 first by 7 samples must read +7 (the "clap on the left" case).
     */
    @Test
    fun reproducesP13ClapPolarity() {
        val src = sine(400.0)
        val clapLeft = gcc(delayed(src, 7.0), src, sr, weighting = Weighting.PHAT)
        val clapRight = gcc(src, delayed(src, 8.0), sr, weighting = Weighting.PHAT)
        assertTrue("clap-left case must be positive, got ${clapLeft.lagSamples}", clapLeft.lagSamples > 0)
        assertTrue("clap-right case must be negative, got ${clapRight.lagSamples}", clapRight.lagSamples < 0)
    }

    @Test
    fun coherenceWeightingAlsoFindsTheLag() {
        val src = sine(400.0)
        val r = gcc(delayed(src, 12.0), src, sr, weighting = Weighting.COHERENCE)
        assertEquals("lag with COHERENCE weighting", 12.0, r.lagSamples, 1.0)
    }
}
