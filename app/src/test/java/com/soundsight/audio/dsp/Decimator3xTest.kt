package com.soundsight.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Verifies the anti-aliasing decimator actually suppresses the fold-down that produced
 * phantom siren-like tones. The old 3-tap box average is reproduced here as a baseline so
 * the improvement is measured, not asserted.
 */
class Decimator3xTest {

    private val fsIn = 48000
    private val fsOut = 16000
    private val chunk = 2400 // one 50 ms chunk at 48 kHz

    /** Runs a 48 kHz tone through the decimator across several chunks, returns 16 kHz output. */
    private fun decimateTone(freqHz: Double, chunks: Int = 8): DoubleArray {
        val dec = Decimator3x(chunk)
        dec.reset()
        val out = ShortArray(chunk / 3)
        val collected = ArrayList<Double>()
        var n = 0
        repeat(chunks) {
            val input = ShortArray(chunk)
            for (i in 0 until chunk) {
                input[i] = (16000.0 * sin(2.0 * PI * freqHz * n / fsIn)).toInt().toShort()
                n++
            }
            val produced = dec.process(input, chunk, out)
            for (j in 0 until produced) collected.add(out[j].toDouble())
        }
        // drop the first chunk: filter memory is still filling (group delay ~47 samples)
        return collected.drop(chunk / 3).toDoubleArray()
    }

    /** The previous implementation, for baseline comparison. */
    private fun boxDecimateTone(freqHz: Double, chunks: Int = 8): DoubleArray {
        val collected = ArrayList<Double>()
        var n = 0
        repeat(chunks) {
            val mono = DoubleArray(chunk) {
                val v = 16000.0 * sin(2.0 * PI * freqHz * n / fsIn); n++; v
            }
            for (m in 0 until chunk / 3) {
                val b = m * 3
                collected.add((mono[b] + mono[b + 1] + mono[b + 2]) / 3.0)
            }
        }
        return collected.drop(chunk / 3).toDoubleArray()
    }

    /** Goertzel-style magnitude of [signal] at [freqHz], normalised by length. */
    private fun magnitudeAt(signal: DoubleArray, freqHz: Double, fs: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in signal.indices) {
            val w = 2.0 * PI * freqHz * i / fs
            re += signal[i] * cos(w)
            im -= signal[i] * sin(w)
        }
        return hypot(re, im) / signal.size
    }

    /** Where a 48 kHz tone lands after 3:1 decimation to 16 kHz. */
    private fun aliasFreq(fIn: Double): Double {
        val m = Math.round(fIn / fsOut).toDouble()
        return abs(fIn - m * fsOut)
    }

    @Test
    fun passband_toneBelowCutoff_isPreservedAtCorrectFrequency() {
        val out = decimateTone(1000.0)
        val at1k = magnitudeAt(out, 1000.0, fsOut)
        // reference: amplitude 16000 sine has magnitude ~8000 for a matched bin
        assertTrue("1 kHz should pass essentially unattenuated, got $at1k", at1k > 7000.0)
    }

    @Test
    fun passband_4kHzStillPreserved() {
        val out = decimateTone(4000.0)
        val at4k = magnitudeAt(out, 4000.0, fsOut)
        assertTrue("4 kHz should pass, got $at4k", at4k > 6500.0)
    }

    @Test
    fun aliasing_10kHzNoLongerFoldsDownTo6kHz() {
        // 10 kHz is above the 8 kHz Nyquist; without filtering it reappears at 6 kHz.
        val fIn = 10000.0
        assertEquals(6000.0, aliasFreq(fIn), 1.0)

        val newOut = decimateTone(fIn)
        val oldOut = boxDecimateTone(fIn)

        val newAlias = magnitudeAt(newOut, 6000.0, fsOut)
        val oldAlias = magnitudeAt(oldOut, 6000.0, fsOut)

        assertTrue(
            "old box filter should show a strong 6 kHz alias (baseline), got $oldAlias",
            oldAlias > 2000.0
        )
        assertTrue(
            "new filter must suppress the 6 kHz alias far below the old one " +
                "(old=$oldAlias new=$newAlias)",
            newAlias < oldAlias / 50.0
        )
    }

    @Test
    fun aliasing_12kHzNoLongerFoldsDownTo4kHz() {
        val fIn = 12000.0
        assertEquals(4000.0, aliasFreq(fIn), 1.0)

        val newAlias = magnitudeAt(decimateTone(fIn), 4000.0, fsOut)
        val oldAlias = magnitudeAt(boxDecimateTone(fIn), 4000.0, fsOut)

        assertTrue("baseline should alias audibly, got $oldAlias", oldAlias > 1500.0)
        assertTrue(
            "12 kHz must not fold into the 4 kHz band (old=$oldAlias new=$newAlias)",
            newAlias < oldAlias / 50.0
        )
    }

    @Test
    fun sirenSignature_driftingUltrasonicNoLongerProducesSweepingAudibleTone() {
        // A drifting 11->13 kHz whine (charger/LED coil noise) previously folded down into a
        // smooth ~4.7->3.2 kHz sweep, which reads as the "pitch oscillates" siren signature.
        val dec = Decimator3x(chunk)
        dec.reset()
        val out = ShortArray(chunk / 3)
        val newCollected = ArrayList<Double>()
        val oldCollected = ArrayList<Double>()

        val totalChunks = 16
        var phase = 0.0
        var phaseOld = 0.0
        var n = 0
        val totalSamples = totalChunks * chunk

        repeat(totalChunks) {
            val input = ShortArray(chunk)
            val mono = DoubleArray(chunk)
            for (i in 0 until chunk) {
                val inst = 11000.0 + 2000.0 * (n.toDouble() / totalSamples)
                phase += 2.0 * PI * inst / fsIn
                phaseOld = phase
                val v = 10000.0 * sin(phase)
                input[i] = v.toInt().toShort()
                mono[i] = v
                n++
            }
            val produced = dec.process(input, chunk, out)
            for (j in 0 until produced) newCollected.add(out[j].toDouble())
            for (m in 0 until chunk / 3) {
                val b = m * 3
                oldCollected.add((mono[b] + mono[b + 1] + mono[b + 2]) / 3.0)
            }
        }

        val newSig = newCollected.drop(chunk / 3).toDoubleArray()
        val oldSig = oldCollected.drop(chunk / 3).toDoubleArray()

        // Total audible energy in the band the phantom sweep occupied (3-5 kHz).
        var newEnergy = 0.0
        var oldEnergy = 0.0
        var f = 3000.0
        while (f <= 5000.0) {
            newEnergy += magnitudeAt(newSig, f, fsOut)
            oldEnergy += magnitudeAt(oldSig, f, fsOut)
            f += 100.0
        }

        // The old box filter leaks a clearly measurable phantom sweep into 3-5 kHz
        // (measured ~817 for this input); assert it is unmistakably present as a baseline.
        assertTrue(
            "baseline must show the phantom sweep energy, got $oldEnergy",
            oldEnergy > 500.0
        )
        assertTrue(
            "drifting ultrasonic must no longer create an audible sweep " +
                "(old=$oldEnergy new=$newEnergy)",
            newEnergy < oldEnergy / 20.0
        )
    }

    @Test
    fun dcGain_isUnity_soLoudnessAndSpikeDetectionAreUnaffected() {
        val dec = Decimator3x(chunk)
        dec.reset()
        val out = ShortArray(chunk / 3)
        val input = ShortArray(chunk) { 1000 }
        // run several chunks so the filter fully settles on the constant
        repeat(4) { dec.process(input, chunk, out) }
        val settled = out[out.size - 1].toDouble()
        assertEquals("constant input must survive at unity gain", 1000.0, settled, 5.0)
    }

    @Test
    fun outputLength_isExactlyOneThirdOfInput() {
        val dec = Decimator3x(chunk)
        dec.reset()
        val out = ShortArray(chunk / 3)
        val produced = dec.process(ShortArray(chunk), chunk, out)
        assertEquals(chunk / 3, produced)
    }

    @Test
    fun continuityAcrossChunks_noDiscontinuityAtBoundary() {
        // A steady tone processed in chunks must not show a seam; compare the second and
        // third chunk's energy, which should match closely once settled.
        val out = decimateTone(1000.0, chunks = 6)
        val third = out.copyOfRange(0, chunk / 3)
        val fourth = out.copyOfRange(chunk / 3, 2 * chunk / 3)
        val e1 = magnitudeAt(third, 1000.0, fsOut)
        val e2 = magnitudeAt(fourth, 1000.0, fsOut)
        assertEquals("chunk boundary should be seamless", e1, e2, e1 * 0.05)
    }
}
