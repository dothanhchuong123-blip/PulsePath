package com.soundsight.audio.liveness

/**
 * Snapshot of liveness lease state containing all validity fields required by W-H8-3:
 * state, heartbeat timestamp, lease expiry, notification text/channel, reason.
 */
data class LivenessSnapshot(
    val state: LivenessState,
    val heartbeatTimestampMs: Long,
    val leaseExpiryMs: Long,
    val notificationText: String,
    val notificationChannel: String,
    val reason: String = "",
    val captureActive: Boolean = false,
    val inferenceVerified: Boolean = false,
    val wallClockMs: Long = System.currentTimeMillis()
) {
    /**
     * NOT_PROTECTING because the rider chose it - never started, tapped Stop, or paused from the
     * notification - as opposed to protection being lost under them.
     *
     * The distinction is the whole rider-facing difference between "paused" and an alarm. H8's
     * NOT_PROTECTING rendering (red chip, red banner, HIGH-importance warning notification) is
     * for the involuntary case: mic revoked, watchdog expiry, process death. Painting it on a
     * deliberate Stop tells the rider something went wrong every time they park the bike, and a
     * warning that fires on every routine stop is a warning nobody reads when it matters.
     */
    val riderIdle: Boolean
        get() = state == LivenessState.NOT_PROTECTING &&
            (reason == REASON_INITIAL || reason.startsWith(RIDER_REASON_PREFIX))

    fun toMap(): Map<String, Any> = mapOf(
        "state" to state.wireName,
        "heartbeatTimestampMs" to heartbeatTimestampMs,
        "leaseExpiryMs" to leaseExpiryMs,
        "notificationText" to notificationText,
        "notificationChannel" to notificationChannel,
        "reason" to reason,
        "captureActive" to captureActive,
        "inferenceVerified" to inferenceVerified,
        "wallClockMs" to wallClockMs,
        "riderIdle" to riderIdle
    )

    companion object {
        /** Nothing has been started yet this process. */
        const val REASON_INITIAL = "initial_unstarted"
        /** Reasons the rider caused on purpose share this prefix: `user_stop`, `user_pause`. */
        const val RIDER_REASON_PREFIX = "user_"
    }
}
