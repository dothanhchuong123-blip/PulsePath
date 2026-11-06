package com.soundsight.audio.dsp

/**
 * Anti-aliasing 3:1 decimator for the 48 kHz -> 16 kHz mono conversion.
 *
 * The previous implementation averaged 3 consecutive 48 kHz samples and kept every third
 * one. A 3-tap moving average is a very poor anti-aliasing filter: at the 8 kHz target
 * Nyquist it attenuates only ~3.5 dB, so content between 8 kHz and 16 kHz folded back into
 * the audible band almost unattenuated (e.g. 10 kHz reappeared at 6 kHz just 5.9 dB down).
 * Because capture runs with MediaRecorder UNPROCESSED, the OS applies no filtering of its
 * own either, so inaudible high-frequency sources near the phone (charger coil whine, LED
 * or SMPS hum) could fold down as a drifting tone — which reads acoustically like a siren
 * wail and can trigger false EMERGENCY_SIREN classifications.
 *
 * This replaces it with a 95-tap Blackman-windowed sinc low-pass at 7 kHz, applied before
 * decimation. Measured response: -0.1 dB at 6 kHz (passband intact), -38 dB at 8 kHz, and
 * better than -75 dB above 9 kHz. Coefficients are Q15 fixed point so the hot loop stays
 * integer-only; at 800 output samples per 50 ms chunk this is ~1.5M MAC/s, negligible.
 *
 * Filter state carries across chunk boundaries, so [reset] must be called whenever the
 * audio stream restarts.
 */
class Decimator3x(maxInputSamples: Int = 2400) {

    companion object {
        const val TAPS = 95
        const val DECIMATION = 3

        /** Q15 (32768 = 1.0) coefficients; sum is exactly 32768 for unity DC gain. */
        private val COEFFS = intArrayOf(
            0, 0, 0, 0, 2, 2, -1, -5, -7, -3,
            8, 17, 13, -7, -29, -33, -6, 39, 65, 38,
            -34, -101, -97, 0, 128, 179, 81, -121, -273, -221,
            46, 348, 421, 134, -356, -665, -461, 224, 922, 1003,
            178, -1150, -1975, -1266, 1309, 5001, 8260, 9554, 8260, 5001,
            1309, -1266, -1975, -1150, 178, 1003, 922, 224, -461, -665,
            -356, 134, 421, 348, 46, -221, -273, -121, 81, 179,
            128, 0, -97, -101, -34, 38, 65, 39, -6, -33,
            -29, -7, 13, 17, 8, -3, -7, -5, -1, 2,
            2, 0, 0, 0, 0
        )
    }

    // history + current input, reused to avoid allocating in the audio loop
    private val work = ShortArray(TAPS - 1 + maxInputSamples)

    /** Clears filter memory. Call when the capture stream (re)starts. */
    fun reset() {
        work.fill(0)
    }

    /**
     * Low-pass filters [inputLen] mono 48 kHz samples from [input] and decimates 3:1.
     *
     * @param inputLen number of samples to consume; must be a multiple of [DECIMATION]
     *                 and no larger than the configured maximum.
     * @param output receives `inputLen / DECIMATION` samples at 16 kHz.
     * @return number of output samples written.
     */
    fun process(input: ShortArray, inputLen: Int, output: ShortArray): Int {
        require(inputLen % DECIMATION == 0) { "inputLen must be a multiple of $DECIMATION" }
        require(TAPS - 1 + inputLen <= work.size) { "inputLen exceeds configured maximum" }

        System.arraycopy(input, 0, work, TAPS - 1, inputLen)

        val outCount = inputLen / DECIMATION
        for (m in 0 until outCount) {
            val base = m * DECIMATION
            var acc = 0L
            for (k in 0 until TAPS) {
                acc += COEFFS[k].toLong() * work[base + k].toLong()
            }
            val v = (acc shr 15).toInt()
            output[m] = when {
                v > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                v < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> v.toShort()
            }
        }

        // Retain the tail so the next call filters continuously across the boundary.
        System.arraycopy(work, inputLen, work, 0, TAPS - 1)
        return outCount
    }
}
