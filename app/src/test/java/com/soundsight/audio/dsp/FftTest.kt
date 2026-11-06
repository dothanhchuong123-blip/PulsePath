package com.soundsight.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FftTest {

    @Test
    fun cosineAtBin8_putsEnergyInBins8And56() {
        val n = 64
        val re = DoubleArray(n) { cos(2.0 * PI * 8.0 * it / n) }
        val im = DoubleArray(n)
        fft(re, im)

        var total = 0.0
        var inTargetBins = 0.0
        for (k in 0 until n) {
            val e = re[k] * re[k] + im[k] * im[k]
            total += e
            if (k == 8 || k == 56) inTargetBins += e
        }
        assertTrue(
            "expected >99% of energy in bins 8 and 56, got ${inTargetBins / total}",
            inTargetBins / total > 0.99
        )
    }

    @Test
    fun ifftOfFft_reconstructsSignal() {
        val rng = kotlin.random.Random(42)
        val n = 256
        val original = DoubleArray(n) { rng.nextDouble() * 2.0 - 1.0 }
        val re = original.copyOf()
        val im = DoubleArray(n)

        fft(re, im)
        ifft(re, im)

        for (i in 0 until n) {
            assertEquals("sample $i", original[i], re[i], 1e-9)
            assertEquals("imag $i", 0.0, im[i], 1e-9)
        }
    }

    @Test
    fun bandRms_passesInBandAndRejectsOutOfBand() {
        val sr = 48000
        val n = 4096
        val amp = 0.5
        val x = DoubleArray(n) { amp * sin(2.0 * PI * 400.0 * it / sr) }
        val trueRms = amp / sqrt(2.0)

        val inBand = bandRms(x, sr, 150.0, 1000.0)
        assertEquals("in-band RMS", trueRms, inBand, trueRms * 0.05)

        val outOfBand = bandRms(x, sr, 2000.0, 6000.0)
        assertTrue(
            "out-of-band RMS should be < 2% of true RMS, got ${outOfBand / trueRms}",
            outOfBand < trueRms * 0.02
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun fftRejectsNonPowerOfTwo() {
        fft(DoubleArray(100), DoubleArray(100))
    }

    @Test
    fun nextPow2Works() {
        assertEquals("1", 1L, nextPow2(1).toLong())
        assertEquals("100", 128L, nextPow2(100).toLong())
        assertEquals("1024", 1024L, nextPow2(1024).toLong())
        assertEquals("1025", 2048L, nextPow2(1025).toLong())
    }

    /**
     * Zero phase shift is the whole reason bandFilter is FFT masking and not a biquad:
     * a filter that shifts phase would shift the very delay GCC is trying to measure.
     */
    @Test
    fun bandFilterDoesNotShiftPhase() {
        val sr = 48000
        val n = 4096
        val x = DoubleArray(n) { sin(2.0 * PI * 400.0 * it / sr) }
        val y = bandFilter(x, sr, 150.0, 1000.0)

        // Guard against an edge-transient-only match: compare over the middle.
        var maxDiff = 0.0
        for (i in 512 until n - 512) maxDiff = maxOf(maxDiff, abs(x[i] - y[i]))
        assertTrue("band filter altered the in-band waveform by $maxDiff", maxDiff < 0.02)
    }
}
