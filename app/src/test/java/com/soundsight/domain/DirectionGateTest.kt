package com.soundsight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionGateTest {

    /** A clip that passes all six gates comfortably. Each test breaks exactly one thing. */
    private fun evaluate(
        lag: Double = 14.0,
        peakRatio: Double = 3.0,
        corr: Double = 0.83,
        snrDb: Double = 12.0,
        rmsDbFs: Double = -39.0,
        signAgreement: Int = 4,
        swapped: Boolean = false
    ) = DirectionGate.evaluate(lag, peakRatio, corr, snrDb, rmsDbFs, signAgreement, swapped)

    private fun assertUnknown(what: String, e: DirectionEstimate) {
        assertEquals(what, DirectionConfidence.UNKNOWN, e.confidence)
        assertNull("$what must not carry a side", e.side)
    }

    @Test
    fun baselinePasses() {
        val e = evaluate()
        assertEquals("side", Side.LEFT, e.side)
        assertEquals("confidence", DirectionConfidence.HIGH, e.confidence)
    }

    // --- each gate failing in isolation ----------------------------------------------

    @Test
    fun gate1_windRejectsLoudLowBand() {
        assertUnknown("wind gate", evaluate(rmsDbFs = -24.9))
    }

    @Test
    fun gate2_physicallyImpossibleLagRejected() {
        assertUnknown("physical gate, positive", evaluate(lag = 22.1))
        assertUnknown("physical gate, negative", evaluate(lag = -22.1))
    }

    @Test
    fun gate3_ambiguousPerpendicularRejected() {
        assertUnknown("ambiguous gate", evaluate(lag = 4.0))
        assertUnknown("ambiguous gate, negative", evaluate(lag = -3.5))
    }

    @Test
    fun gate4_lowSnrRejected() {
        assertUnknown("snr gate", evaluate(snrDb = 5.9))
    }

    /**
     * peakRatio must NOT gate anything. It was measured (17/08/2026, 3,840 simulated
     * events) to overlap completely between real horns and wind, while silencing up to
     * 96% of correct events. A clean horn at 20 dB SNR only reaches ~1.7, so the
     * original `> 1.8` gate would have made the whole feature permanently silent - and
     * nothing would have reported an error. This test is the tripwire for anyone
     * putting it back.
     */
    @Test
    fun peakRatioIsTelemetryOnlyAndDoesNotGate() {
        assertEquals("peakRatio 1.0 must still pass", DirectionConfidence.HIGH, evaluate(peakRatio = 1.0).confidence)
        assertEquals("peakRatio 0.2 must still pass", DirectionConfidence.HIGH, evaluate(peakRatio = 0.2).confidence)
    }

    @Test
    fun gate6_signDisagreementRejected() {
        assertUnknown("sign agreement gate", evaluate(signAgreement = 2))
    }

    // --- boundaries stay on the permissive side where the contract says so -----------

    @Test
    fun gateBoundariesAreInclusiveAsSpecified() {
        assertEquals("rms exactly at -25 passes", DirectionConfidence.HIGH, evaluate(rmsDbFs = -25.0).confidence)
        assertEquals("lag exactly 22 passes", DirectionConfidence.HIGH, evaluate(lag = 22.0).confidence)
        assertEquals("snr exactly 6 passes", DirectionConfidence.HIGH, evaluate(snrDb = 6.0).confidence)
    }

    // --- the three confidence branches ------------------------------------------------

    @Test
    fun confidenceHigh() {
        assertEquals(DirectionConfidence.HIGH, evaluate(corr = 0.5).confidence)
    }

    @Test
    fun confidenceMedium() {
        assertEquals(DirectionConfidence.MEDIUM, evaluate(corr = 0.3).confidence)
        assertEquals(DirectionConfidence.MEDIUM, evaluate(corr = 0.38).confidence)
    }

    @Test
    fun confidenceUnknownBelowMedium() {
        assertUnknown("correlation below 0.3", evaluate(corr = 0.29))
    }

    // --- side mapping -----------------------------------------------------------------

    @Test
    fun negativeLagIsRight() {
        assertEquals(Side.RIGHT, evaluate(lag = -14.0).side)
    }

    @Test
    fun channelsSwappedInvertsBothSides() {
        assertEquals(Side.RIGHT, evaluate(lag = 14.0, swapped = true).side)
        assertEquals(Side.LEFT, evaluate(lag = -14.0, swapped = true).side)
    }

    // --- the two real measured cases, 15/08/2026, Xiaomi 13 Pro ----------------------

    @Test
    fun stationary_big_road() {
        val e = DirectionGate.evaluate(
            itdLagSamples = 14.0,
            itdPeakRatio = 3.0,
            itdPeakCorrelation = 0.826,
            itdBandSnrDb = 12.0,
            itdBandRmsDbFs = -39.18,
            itdSignAgreement = 4,
            channelsSwapped = false
        )
        assertEquals("side", Side.LEFT, e.side)
        assertEquals("confidence", DirectionConfidence.HIGH, e.confidence)
    }

    @Test
    fun riding_40kmh() {
        // Gate 1 alone must kill this: everything else is deliberately set to values
        // that would otherwise pass, so a missing wind gate shows up here.
        val e = DirectionGate.evaluate(
            itdLagSamples = -46.0,
            itdPeakRatio = 3.0,
            itdPeakCorrelation = 0.041,
            itdBandSnrDb = 12.0,
            itdBandRmsDbFs = -16.07,
            itdSignAgreement = 4,
            channelsSwapped = false
        )
        assertUnknown("riding at 40 km/h", e)
    }
}
