package com.soundsight.domain

import kotlin.math.abs

/**
 * Decides whether a direction may be shown at all.
 *
 * Every threshold below comes from measurements taken on a Xiaomi 13 (model `2211133C`,
 * device `fuxi`, Android 16 / API 36) on 15/08/2026 (P13 StereoProbe, UNPROCESSED 48 kHz
 * stereo), not from theory.
 *
 * Device identity resolved 2026-08-24 (round 2) by reading the target directly:
 * `ro.product.model=2211133C`, `ro.product.device=fuxi`, `ro.product.marketname=Xiaomi 13`.
 * Earlier comments said "Xiaomi 13 Pro"; that was wrong, and the record now says so. This
 * matters to `LAG_PHYSICAL_MAX`: a Xiaomi 13 is 152.8 mm tall, so a top-to-bottom microphone
 * pair maxes out at about 21.38 samples at 343 m/s (20 °C) - the 22.0 ceiling is roughly 3%
 * above what the chassis could produce end-to-end, and sits inside the 21.3-22.8 sample span
 * that 0-40 °C implies. It stays unchanged: tightening it on chassis arithmetic alone would be
 * guess-instead-of-measure, and the number is only settled when MicGeometryProbe reports a real
 * CHANNEL_MAPPING_DIRECT pair for this route (C1 profile input).
 *
 * They are a safety contract, not tuning knobs. Loosening one does not make the
 * feature work more often - it makes it lie more often.
 *
 * There is deliberately NO ILD path. ILD was implemented, measured, and found to
 * carry no directional information on this hardware (see Ild.kt). Do not add one back.
 *
 * NOTE (round 2, 2026-08-24) - a measured speed input now EXISTS but is not used here yet.
 * `SpeedProvider` streams 1 Hz GNSS Doppler speed when the rider opts into "Always on" location,
 * and every Stereo Probe capture row now carries that measured speed with its accuracy and fix
 * age (plus an IMU moving vote). That removes the premise "the app has no speed input" - but it
 * does NOT license adding a speed term to [evaluate]: Gate 1 still keys on in-band RMS as a
 * speed proxy because there is no B1 dataset yet to choose a threshold from, and a GNSS speed
 * brings its own failure modes (no fix, stale fix under a bridge, permission revoked mid-ride,
 * mode switched to Off). In every one of those the only safe direction result is UNKNOWN - never
 * an assumed speed. What would license the change: the full B1 speed-sweep table (5 repeats per
 * side per bucket, measured speeds per the Part B protocol) showing a speed envelope that
 * separates usable from unusable captures better than the RMS proxy does, without raising
 * wrong-side output. Until then, editing these constants on the strength of a speedometer is
 * exactly the guess-instead-of-measure move the research document warns against.
 */
object DirectionGate {

    const val WIND_RMS_DBFS_MAX = -25.0
    const val LAG_PHYSICAL_MAX = 22.0
    const val LAG_AMBIGUOUS_MIN = 4.0
    const val BAND_SNR_DB_MIN = 6.0
    const val SIGN_AGREEMENT_MIN = 3
    const val CORR_HIGH = 0.5
    const val CORR_MEDIUM = 0.3

    /**
     * @param itdLagSamples signed lag from Gcc.gcc(). Positive = source on the LEFT
     *   on the reference device; see the sign-convention note in GccResult.
     * @param itdPeakRatio TELEMETRY ONLY - not used in any decision. See the note at
     *   the former Gate 5 below.
     * @param itdBandRmsDbFs absolute level of the 150-1000 Hz band. Wind/speed proxy.
     * @param itdSignAgreement 0..4, how many of the 4 sub-windows agreed on the sign.
     * @param channelsSwapped from the one-time calibration; inverts the side mapping.
     */
    fun evaluate(
        itdLagSamples: Double,
        itdPeakRatio: Double,
        itdPeakCorrelation: Double,
        itdBandSnrDb: Double,
        itdBandRmsDbFs: Double,
        itdSignAgreement: Int,
        channelsSwapped: Boolean
    ): DirectionEstimate {

        // Gate 1 - WIND / SPEED. First and cheapest: if this fails, nothing else is
        // worth computing. Measured: stationary -39 dBFS (correlation 0.83, usable);
        // riding at 40 km/h -16 dBFS (correlation 0.04, pure noise).
        if (itdBandRmsDbFs > WIND_RMS_DBFS_MAX) return DirectionEstimate.UNKNOWN

        // Gate 2 - PHYSICAL PLAUSIBILITY. 22 samples @ 48 kHz is the largest delay
        // this microphone spacing can produce. The 40 km/h recording returned -46
        // samples; this gate alone rejects that case.
        if (abs(itdLagSamples) > LAG_PHYSICAL_MAX) return DirectionEstimate.UNKNOWN

        // Gate 3 - clear of the ambiguous perpendicular plane.
        if (abs(itdLagSamples) <= LAG_AMBIGUOUS_MIN) return DirectionEstimate.UNKNOWN

        // Gate 4 - the event must stand above the current noise floor in band.
        if (itdBandSnrDb < BAND_SNR_DB_MIN) return DirectionEstimate.UNKNOWN

        // Gate 5 in the original design was `peakRatio > 1.8`. It is GONE, and
        // itdPeakRatio is now telemetry only. Simulation of 3,840 events through this
        // exact decision path (synthetic two-tone horn, known delay, known in-band SNR,
        // 17/08/2026) measured:
        //   - a clean horn at 20 dB SNR reaches peakRatio 1.70 median, 1.87 max;
        //     uncorrelated wind reaches 1.82 max. The two distributions overlap at
        //     every SNR, so NO threshold both admits horns and rejects wind.
        //   - the 1.8 threshold silenced 65-96% of correct events (at 6 dB SNR and a
        //     6-sample lag: 3 shown out of 80, against 51 out of 80 without it).
        //   - removing it cost nothing in safety: wind was rejected 80/80 either way,
        //     and wrong-side output was 0 out of 3,840 in every configuration.
        // Same treatment as ILD: measured, found non-discriminating, removed from the
        // decision instead of left in to manufacture confidence. Do not add it back
        // without new measurements.

        // Gate 6 - the sign must be stable across the clip. This gate plus the
        // correlation floor below is what actually stops a wrong side: at 6 dB SNR the
        // raw lag estimate does flip sign on individual clips, and these two catch it.
        if (itdSignAgreement < SIGN_AGREEMENT_MIN) return DirectionEstimate.UNKNOWN

        val confidence = when {
            itdPeakCorrelation >= CORR_HIGH -> DirectionConfidence.HIGH
            itdPeakCorrelation >= CORR_MEDIUM -> DirectionConfidence.MEDIUM
            else -> return DirectionEstimate.UNKNOWN
        }

        // Sign convention, MEASURED on the target device: positive lag = source on
        // the LEFT (clap left -> +7, clap right -> -8).
        val rawSide = if (itdLagSamples > 0) Side.LEFT else Side.RIGHT
        val side = if (channelsSwapped) {
            if (rawSide == Side.LEFT) Side.RIGHT else Side.LEFT
        } else {
            rawSide
        }

        return DirectionEstimate(side, confidence)
    }
}
