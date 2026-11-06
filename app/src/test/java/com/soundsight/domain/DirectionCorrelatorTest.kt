package com.soundsight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direction arrow's correctness lives here.
 *
 * The rendering half is covered by screenshots, but which side the arrow points is decided by this
 * matching, and getting it wrong is worse than showing nothing: an arrow pointing at the empty
 * side of the road is a rider looking the wrong way.
 */
class DirectionCorrelatorTest {

    private val left = DirectionEstimate(Side.LEFT, DirectionConfidence.HIGH)
    private val right = DirectionEstimate(Side.RIGHT, DirectionConfidence.MEDIUM)

    // 1. The common order: the DSP finishes before the classifier, so the estimate is already
    // waiting when the event arrives.
    @Test
    fun estimateRecordedBeforeEvent_isFoundByCaptureTime() {
        val correlator = DirectionCorrelator()
        correlator.record(1_000L, left)

        assertEquals(left, correlator.directionFor(1_000L))
    }

    // 2. The other order: the on-device classifier can beat the DSP, so the event is already on
    // screen when the estimate lands. matches() is what lets the ViewModel adopt it late.
    @Test
    fun estimateArrivingAfterEvent_matchesTheEventOnScreen() {
        val correlator = DirectionCorrelator()

        assertTrue(correlator.matches(currentCapturedAtMs = 2_000L, resultCapturedAtMs = 2_000L))
    }

    // 3. A late estimate for a *different* capture must not be adopted by whatever is on screen.
    // This is the overlapping-sounds case, and the reason latest-wins would be wrong.
    @Test
    fun estimateForADifferentCapture_doesNotMatchCurrentEvent() {
        val correlator = DirectionCorrelator()

        assertFalse(correlator.matches(currentCapturedAtMs = 2_000L, resultCapturedAtMs = 2_500L))
    }

    // 4. Nothing on screen: a late estimate has nothing to attach to.
    @Test
    fun noCurrentEvent_neverMatches() {
        val correlator = DirectionCorrelator()

        assertFalse(correlator.matches(currentCapturedAtMs = null, resultCapturedAtMs = 2_000L))
    }

    // 5. An event whose capture the analyser never reported on resolves to UNKNOWN, which is what
    // draws no arrow at all. This is the normal outcome in portrait or with direction disabled.
    @Test
    fun unreportedCapture_resolvesToUnknown() {
        val correlator = DirectionCorrelator()
        correlator.record(1_000L, left)

        assertEquals(DirectionEstimate.UNKNOWN, correlator.directionFor(9_999L))
        assertEquals(DirectionEstimate.UNKNOWN, correlator.directionFor(null))
    }

    // 6. Two captures in flight keep their own estimates; neither overwrites the other.
    @Test
    fun concurrentCaptures_keepSeparateEstimates() {
        val correlator = DirectionCorrelator()
        correlator.record(1_000L, left)
        correlator.record(1_400L, right)

        assertEquals(left, correlator.directionFor(1_000L))
        assertEquals(right, correlator.directionFor(1_400L))
    }

    // 7. The cache is bounded: the oldest entries fall out, the newest survive. Without this a
    // ride-long session accumulates one entry per capture forever.
    @Test
    fun cacheEvictsOldestBeyondCapacity() {
        val correlator = DirectionCorrelator(capacity = 3)
        correlator.record(1L, left)
        correlator.record(2L, left)
        correlator.record(3L, left)
        correlator.record(4L, right)

        assertEquals(DirectionEstimate.UNKNOWN, correlator.directionFor(1L))
        assertEquals(left, correlator.directionFor(2L))
        assertEquals(left, correlator.directionFor(3L))
        assertEquals(right, correlator.directionFor(4L))
    }

    // 8. Re-reporting the same capture refreshes both the value and its position in the eviction
    // order - otherwise a capture that got a corrected estimate would still be evicted on its
    // original schedule.
    @Test
    fun reRecordingSameCapture_updatesValueAndRefreshesAge() {
        val correlator = DirectionCorrelator(capacity = 2)
        correlator.record(1L, left)
        correlator.record(2L, left)
        correlator.record(1L, right)
        correlator.record(3L, left)

        // 2 was the oldest once 1 was refreshed, so 2 is the one evicted.
        assertEquals(right, correlator.directionFor(1L))
        assertEquals(DirectionEstimate.UNKNOWN, correlator.directionFor(2L))
        assertEquals(left, correlator.directionFor(3L))
    }
}
