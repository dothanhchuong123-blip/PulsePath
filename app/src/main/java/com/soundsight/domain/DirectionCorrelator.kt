package com.soundsight.domain

/**
 * Matches a direction estimate to the sound event it belongs to.
 *
 * The stereo analyser and the classifiers consume the *same* capture, so both label their output
 * with that capture's `capturedAtMs`. Matching is therefore exact - no time-window fuzzing, no
 * nearest-neighbour search - and a mismatch means the two really are about different captures.
 *
 * What is *not* fixed is the order they arrive in:
 *  - the DSP usually finishes first, so the estimate is waiting when the event lands;
 *  - the on-device classifier can beat it, so the event is already on screen when the estimate
 *    arrives;
 *  - a cloud round trip loses to it every time.
 *
 * Both orders have to work, which is why this holds a small cache rather than applying whatever
 * estimate happened to arrive last. Attaching the latest estimate to the latest event would be
 * right most of the time and silently wrong exactly when two sounds overlap - which is when a
 * rider most needs to be told the correct side.
 *
 * Not thread-safe: it is confined to the ViewModel's main-safe state updates.
 *
 * @param capacity how many recent captures to remember. Roughly the number that can be in flight
 * across both classifiers at once; an estimate no event has claimed is worthless a second later,
 * and an unbounded map fed by every capture for the length of a ride is a leak.
 */
class DirectionCorrelator(private val capacity: Int = DEFAULT_CAPACITY) {

    private val byCapturedAtMs = LinkedHashMap<Long, DirectionEstimate>()

    /** Records the analyser's verdict for one capture, evicting the oldest beyond [capacity]. */
    fun record(capturedAtMs: Long, estimate: DirectionEstimate) {
        // Remove first so a repeat capture moves back to the newest position rather than keeping
        // its original insertion order and being evicted early.
        byCapturedAtMs.remove(capturedAtMs)
        byCapturedAtMs[capturedAtMs] = estimate
        while (byCapturedAtMs.size > capacity) {
            val oldest = byCapturedAtMs.keys.firstOrNull() ?: break
            byCapturedAtMs.remove(oldest)
        }
    }

    /**
     * The estimate recorded for this capture, or [DirectionEstimate.UNKNOWN] if the analyser has
     * not reported on it - which includes the case where it never will, because direction was
     * suppressed for that capture.
     */
    fun directionFor(capturedAtMs: Long?): DirectionEstimate =
        capturedAtMs?.let { byCapturedAtMs[it] } ?: DirectionEstimate.UNKNOWN

    /** True when this capture is the one currently on screen and so should adopt a late estimate. */
    fun matches(currentCapturedAtMs: Long?, resultCapturedAtMs: Long): Boolean =
        currentCapturedAtMs != null && currentCapturedAtMs == resultCapturedAtMs

    private companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
