package com.soundsight.core

/**
 * Rider-selected location participation. Persisted in [OnboardingStore]; OFF is the default
 * because this is a new permission on an app that already works without one.
 *
 *  - [OFF]: no location at all, no permission requested. The crash message falls back to
 *    today's cached-fix behaviour (which itself yields nothing without permission).
 *  - [COLLISION_ONLY]: displayed to the rider as "Auto" / "Tự động" (see [Strings.locationModeCollisionOnly]) -
 *    the enum name is kept as-is to avoid a wide rename, but the label and behaviour moved past
 *    "collision only": [com.soundsight.core.PeriodicLocationRefresher] requests one fresh fix
 *    every [com.soundsight.core.PeriodicLocationRefresher.REFRESH_INTERVAL_MS] while the listener
 *    service runs, on top of the existing one-shot fix requested at collision time for the
 *    post-collision message. No continuous stream, no live speed readout - that stays
 *    [ALWAYS]-only. The periodic refresh exists so the fix a real collision message falls back on
 *    is minutes old, not hours old, at a fraction of [ALWAYS]'s 1 Hz battery cost.
 *  - [ALWAYS]: 1 Hz GNSS while listening - HUD speed readout, measured speed labels on Stereo
 *    Probe captures, fresh fix available for the crash message. Costs measurable battery.
 */
enum class LocationMode {
    OFF,
    COLLISION_ONLY,
    ALWAYS;

    companion object {
        fun fromName(raw: String?): LocationMode =
            entries.firstOrNull { it.name == raw } ?: OFF
    }
}

/**
 * The measured battery cost of "Always on", stated in the Settings row instead of a vague
 * warning. Null until the paired 30-minute measurement (Off vs Always on, same conditions,
 * `dumpsys batterystats`) has actually been run on the target device - the row then says
 * honestly that it has not been measured yet. Never write an estimated number here.
 */
object LocationBatteryCost {
    @Volatile
    var measuredPctPer30Min: Float? = null
}
