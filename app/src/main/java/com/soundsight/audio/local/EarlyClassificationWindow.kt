package com.soundsight.audio.local

/**
 * Trigger-aligned window resolution for spike-triggered early local classification,
 * sharing the fail-closed timing discipline of [com.soundsight.audio.dsp.DirectionWindow].
 *
 * The local classifier consumes a snapshot of the continuously-written mono 16 kHz pre-roll
 * buffer taken ~500 ms after a spike onset. Because the buffer capacity is 1.0 s (16,000 samples /
 * 32,000 bytes), snapshotting at trigger + 500 ms yields an audio window spanning
 * `[onset - 500 ms, onset + 500 ms]` — exactly centered on the spike onset, matching YAMNet's
 * 0.975 s input requirements without requiring any extra buffer.
 *
 * Incomplete snapshots (e.g. immediately after recorder start or restart before the 1.0 s buffer
 * has filled) fail closed to null so that post-restart artifacts are never classified as genuine
 * spikes.
 */
object EarlyClassificationWindow {

    /** Mono 16 kHz sample rate consumed by on-device YAMNet. */
    const val SAMPLE_RATE = 16_000

    /** Bytes per 16-bit mono PCM sample. */
    const val BYTES_PER_SAMPLE = 2

    /** Full pre-roll buffer duration: 1.0 s (1000 ms). */
    const val WINDOW_DURATION_MS = 1_000L

    /** Expected sample count for a complete 1.0 s window: 16,000 samples. */
    const val EXPECTED_SAMPLES = (SAMPLE_RATE * WINDOW_DURATION_MS / 1000L).toInt() // 16_000

    /** Expected byte count for a complete 1.0 s window: 32,000 bytes. */
    const val EXPECTED_BYTES = EXPECTED_SAMPLES * BYTES_PER_SAMPLE // 32_000

    /** Target snapshot delay scheduled after spike detection: 500 ms. */
    const val SNAPSHOT_DELAY_MS = 500L

    /** Expected audio duration preceding the spike onset: 500 ms. */
    const val EXPECTED_PRE_ONSET_MS = WINDOW_DURATION_MS - SNAPSHOT_DELAY_MS // 500L

    /** Expected audio duration following the spike onset: 500 ms. */
    const val EXPECTED_POST_ONSET_MS = SNAPSHOT_DELAY_MS // 500L

    /**
     * A verified, safe early classification window.
     *
     * @property preOnsetMs audio span in ms before the spike onset in this snapshot.
     * @property postOnsetMs audio span in ms after the spike onset in this snapshot.
     * @property sampleCount number of mono 16 kHz samples in the window.
     */
    data class SafeWindow(
        val preOnsetMs: Long,
        val postOnsetMs: Long,
        val sampleCount: Int
    )

    /**
     * Resolves the early classification window for a snapshot, or null when ANY precondition fails:
     *  - snapshotByteCount is not the FULL expected 32,000 bytes (1.0 s);
     *  - delayMs is not within valid bounds (must be > 0 and < WINDOW_DURATION_MS).
     *
     * Fail-closed: incomplete snapshots (e.g. immediately after recorder start/restart) return null
     * so that short post-restart artifacts are not classified as genuine spike events.
     */
    fun resolve(
        snapshotByteCount: Int,
        delayMs: Long = SNAPSHOT_DELAY_MS
    ): SafeWindow? {
        if (snapshotByteCount != EXPECTED_BYTES) return null
        if (delayMs <= 0L || delayMs >= WINDOW_DURATION_MS) return null
        val preOnsetMs = WINDOW_DURATION_MS - delayMs
        val postOnsetMs = delayMs
        return SafeWindow(
            preOnsetMs = preOnsetMs,
            postOnsetMs = postOnsetMs,
            sampleCount = EXPECTED_SAMPLES
        )
    }
}
