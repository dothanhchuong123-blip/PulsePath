package com.soundsight.audio.liveness

/**
 * Rider-facing and rendering states under W-H8-3 liveness lease contract.
 *
 * Rider-visible contract (H8 Section 4):
 * - PROTECTED: local capture & local hazard decision path active, cloud may be connected.
 *   Notification: "Protection active · local".
 * - DEGRADED_BUT_LOCAL: local path is alive and passing heartbeats, cloud reconnecting / degraded.
 *   Notification & HUD: "Local protection only".
 * - CLOUD_UNAVAILABLE_BUT_LOCAL: local path alive and passing heartbeats, cloud offline/quota/setup error.
 *   Notification & HUD: "Local protection only".
 * - NOT_PROTECTING: no recent local heartbeat, mic revoked/unavailable, service stopped, or process dead.
 *   Notification: "Protection stopped — hazards may be missed" (high importance warning channel).
 *   HUD: unmissable/blocking red warning state.
 */
enum class LivenessState(val wireName: String) {
    PROTECTED("protected"),
    DEGRADED_BUT_LOCAL("degraded-but-local"),
    CLOUD_UNAVAILABLE_BUT_LOCAL("cloud-unavailable-but-local"),
    NOT_PROTECTING("not-protecting");

    companion object {
        fun fromWireName(name: String): LivenessState {
            return entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: NOT_PROTECTING
        }
    }
}
