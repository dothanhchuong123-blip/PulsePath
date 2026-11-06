package com.soundsight.audio.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSpikeDetectorTest {

    // Deliberately NOT the production defaults - these tests exercise the co-occurrence/baseline
    // logic itself (does accel+gyro-together count, does either alone not count, does timing
    // matter), which must hold regardless of wherever the real thresholds get calibrated to. Using
    // a fixed, independent pair here means a future recalibration (see testProductionDefaults...
    // below) can't silently invalidate what these tests are actually checking.
    private val detector = MotionSpikeDetector(accelSpikeDeltaMs2 = 20.0f, gyroSpikeMagnitudeRadS = 3.5f)

    @Test
    fun testRestingDeviceProducesNoSpike() {
        // Steady phone: accel magnitude hovers near gravity (9.8), gyro near 0.
        var t = 0L
        repeat(50) {
            detector.submitAccel(9.81f, t)
            detector.submitGyro(0.01f, t)
            t += 20
        }
        assertFalse(detector.hasSpikeNear(t))
    }

    @Test
    fun testAccelAloneDoesNotCountAsSpike() {
        // A pothole: hard accel deviation, but the phone doesn't rotate. Must not fire alone -
        // this is the primary false-positive guard for real riding conditions.
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        detector.submitAccel(45.0f, t) // big deviation from the ~9.8 baseline
        detector.submitGyro(0.02f, t)  // gyro stays essentially at rest
        assertFalse(detector.hasSpikeNear(t))
    }

    @Test
    fun testGyroAloneDoesNotCountAsSpike() {
        // Leaning hard into a turn: real rotation, but no accel transient. Must not fire alone.
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        detector.submitAccel(9.9f, t)
        detector.submitGyro(5.0f, t) // fast rotation, well above threshold
        assertFalse(detector.hasSpikeNear(t))
    }

    @Test
    fun testAccelAndGyroTogetherWithinWindowCountsAsSpike() {
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        detector.submitAccel(45.0f, t)
        detector.submitGyro(5.0f, t + 100) // within the 500ms co-occurrence window
        assertTrue(detector.hasSpikeNear(t + 100))
    }

    @Test
    fun testAccelAndGyroFarApartDoNotCombine() {
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        detector.submitAccel(45.0f, t)
        detector.submitGyro(5.0f, t + 5_000) // well outside the 500ms co-occurrence window
        assertFalse(detector.hasSpikeNear(t + 5_000))
    }

    @Test
    fun testHasSpikeNearRespectsQueryWindow() {
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        detector.submitAccel(45.0f, t)
        detector.submitGyro(5.0f, t)
        val spikeAt = t

        assertTrue(detector.hasSpikeNear(spikeAt + 1_000, windowMs = 3_000))
        assertFalse(detector.hasSpikeNear(spikeAt + 10_000, windowMs = 3_000))
    }

    @Test
    fun testPeakAccelAndGyroTrackLargestMagnitudeSeen() {
        var t = 0L
        detector.submitAccel(9.81f, t) // establishes baseline, no peak yet
        t += 20
        detector.submitAccel(20.0f, t) // delta 10.19 from baseline
        t += 20
        detector.submitAccel(15.0f, t) // smaller delta, must not overwrite the peak
        detector.submitGyro(1.0f, t)
        detector.submitGyro(4.5f, t)
        detector.submitGyro(2.0f, t) // smaller, must not overwrite the peak

        assertTrue(detector.peakAccelDelta > 10.0f && detector.peakAccelDelta < 10.5f)
        assertEquals(4.5f, detector.peakGyroMagnitude, 0.001f)
    }

    @Test
    fun testResetPeaksZeroesBothPeaksButNotSpikeHistory() {
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        detector.submitAccel(45.0f, t)
        detector.submitGyro(5.0f, t)
        assertTrue(detector.peakAccelDelta > 0f)
        assertTrue(detector.peakGyroMagnitude > 0f)
        assertTrue(detector.hasSpikeNear(t))

        detector.resetPeaks()

        assertEquals(0f, detector.peakAccelDelta, 0.001f)
        assertEquals(0f, detector.peakGyroMagnitude, 0.001f)
        // Resetting the calibration readout must not erase the already-recorded combined spike
        // used by AlertPolicy corroboration - these are independent pieces of state.
        assertTrue(detector.hasSpikeNear(t))
    }

    @Test
    fun testProductionDefaultsRejectAggressiveHandlingButAcceptDropTest() {
        // Real device measurements (Xiaomi 2211133C "fuxi", 2026-08-19) - see MotionSpikeDetector's
        // class doc for the full table. Guards the calibration itself: if these constants ever
        // drift back down, this catches it the way testStaleDrop should have caught STALE_MS.
        val handling = MotionSpikeDetector() // production defaults
        var t = 0L
        repeat(20) { handling.submitAccel(9.81f, t).also { t += 20 } }
        // Fast aggressive wrist-twist (e.g. yanking the phone off its mount) - NOT a crash, and
        // must not corroborate. Measured accel delta 24.0 m/s^2, gyro 35.22 rad/s - this exceeded
        // the old placeholder thresholds (20.0/3.5) and would have wrongly corroborated.
        handling.submitAccel(9.81f + 24.0f, t)
        handling.submitGyro(35.22f, t)
        assertFalse(handling.hasSpikeNear(t))

        val drop = MotionSpikeDetector() // production defaults
        var t2 = 0L
        repeat(20) { drop.submitAccel(9.81f, t2).also { t2 += 20 } }
        // Drop test onto a soft surface from waist height - the closest available proxy to a real
        // crash impact. Measured accel delta 92.7 m/s^2, gyro 21.87 rad/s - must still corroborate.
        drop.submitAccel(9.81f + 92.7f, t2)
        drop.submitGyro(21.87f, t2)
        assertTrue(drop.hasSpikeNear(t2))
    }

    @Test
    fun testSustainedShakeIsNotForgottenByBaselineDrift() {
        // A shake lasting several samples must not get folded into the resting baseline and
        // "disappear" - each spiking sample is excluded from the baseline EMA update.
        var t = 0L
        repeat(20) { detector.submitAccel(9.81f, t).also { t += 20 } }
        repeat(10) {
            detector.submitAccel(45.0f, t)
            t += 20
        }
        detector.submitGyro(5.0f, t)
        assertTrue(detector.hasSpikeNear(t))
    }
}
