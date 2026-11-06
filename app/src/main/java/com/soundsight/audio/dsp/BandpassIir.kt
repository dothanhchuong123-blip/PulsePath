package com.soundsight.audio.dsp

import kotlin.math.roundToInt

/**
 * Cascaded-biquad IIR bandpass filter (300–2500 Hz at 16 kHz sample rate).
 *
 * Used to band-limit the spike detector input so the noise-floor tracker and spike gate measure
 * acoustic hazard energy (horns at 400–2000 Hz, sirens at 500–1800 Hz) rather than low-frequency
 * wind buffeting (<200 Hz) and engine/exhaust firing-order vibration (20–120 Hz plus harmonics).
 *
 * **Why an IIR filter is used here:**
 * [com.soundsight.audio.dsp.Bands] states: "Deliberately NOT an IIR filter … phase IS the
 * measurement in GCC." That constraint is real and applies strictly to the stereo direction
 * GCC path, where non-linear phase distortion would compromise inter-channel time-difference
 * measurements. In contrast, this spike gate measures magnitude/energy alone (RMS ratio over time).
 * An IIR cascaded-biquad filter provides steep attenuation at low frequencies with minimal
 * computational overhead (~4 biquads per sample) and zero heap allocations during streaming
 * audio capture.
 *
 * Implemented as a 4th-order Butterworth cascade (two 2nd-order high-pass sections at 300 Hz and
 * two 2nd-order low-pass sections at 2500 Hz) using Transposed Direct Form II.
 *
 * Filter state carries across chunk boundaries. Call [reset] when the capture stream restarts.
 */
class BandpassIir(maxInputSamples: Int = 1600) {

    private class BiquadSection(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    ) {
        var s1: Double = 0.0
        var s2: Double = 0.0

        fun reset() {
            s1 = 0.0
            s2 = 0.0
        }

        fun process(x: Double): Double {
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            return y
        }
    }

    private val stages = arrayOf(
        // Highpass section 1 (300 Hz @ 16 kHz, Q = 0.54119610)
        BiquadSection(
            b0 = 8.989201351902367e-01,
            b1 = -1.7978402703804735e+00,
            b2 = 8.989201351902367e-01,
            a1 = -1.791587696777784e+00,
            a2 = 8.040928439831628e-01
        ),
        // Highpass section 2 (300 Hz @ 16 kHz, Q = 1.30656296)
        BiquadSection(
            b0 = 9.536398743746654e-01,
            b1 = -1.9072797487493307e+00,
            b2 = 9.536398743746654e-01,
            a1 = -1.9006465638071275e+00,
            a2 = 9.139129336915337e-01
        ),
        // Lowpass section 1 (2500 Hz @ 16 kHz, Q = 0.54119610)
        BiquadSection(
            b0 = 1.2567451583798339e-01,
            b1 = 2.5134903167596678e-01,
            b2 = 1.2567451583798339e-01,
            a1 = -6.284099332330609e-01,
            a2 = 1.3110799658499456e-01
        ),
        // Lowpass section 2 (2500 Hz @ 16 kHz, Q = 1.30656296)
        BiquadSection(
            b0 = 1.685758072132765e-01,
            b1 = 3.37151614426553e-01,
            b2 = 1.685758072132765e-01,
            a1 = -8.429291415944099e-01,
            a2 = 5.172323704475159e-01
        )
    )

    /** Clears filter memory. Call when the capture stream (re)starts. */
    fun reset() {
        for (stage in stages) {
            stage.reset()
        }
    }

    /**
     * Filters [inputLen] mono 16 kHz samples from [input] into [output].
     *
     * @param input raw mono 16-bit PCM samples at 16 kHz
     * @param inputLen number of samples to process; must not exceed buffer bounds.
     * @param output receives the filtered 16-bit PCM samples
     * @return number of samples written to [output]
     */
    fun process(input: ShortArray, inputLen: Int, output: ShortArray): Int {
        require(inputLen <= input.size) { "inputLen exceeds input buffer size" }
        require(inputLen <= output.size) { "inputLen exceeds output buffer size" }

        val s0 = stages[0]
        val s1 = stages[1]
        val s2 = stages[2]
        val s3 = stages[3]

        for (i in 0 until inputLen) {
            var v = input[i].toDouble()
            v = s0.process(v)
            v = s1.process(v)
            v = s2.process(v)
            v = s3.process(v)
            val clamped = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = clamped.toShort()
        }
        return inputLen
    }

    /** Convenience overload that processes the entire [input] array. */
    fun process(input: ShortArray, output: ShortArray): Int {
        return process(input, input.size, output)
    }
}
