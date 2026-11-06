package com.soundsight.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.soundsight.audio.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "soundsight_onboarding")

object OnboardingStore {
    private val LANGUAGE_KEY = stringPreferencesKey("language")
    private val SENSITIVITY_KEY = intPreferencesKey("sensitivity")
    private val T2_ENABLED_KEY = booleanPreferencesKey("t2_enabled")
    private val MOTION_SENSORS_ENABLED_KEY = booleanPreferencesKey("motion_sensors_enabled")
    private val CHANNELS_SWAPPED_KEY = booleanPreferencesKey("channels_swapped")
    private val DIRECTION_DISABLED_KEY = booleanPreferencesKey("direction_disabled")
    // The exact Surface.ROTATION_* integer at which channelsSwapped was measured (A6). Polarity
    // without the rotation it was measured under is incomplete: a 180-degree remount reverses
    // bike-relative LEFT/RIGHT while the saved boolean stays unchanged, so analysis is only
    // permitted at the rotation recorded here. Null on installs that calibrated before this
    // existed - those must recalibrate; the rotation is never inferred from channelsSwapped.
    private val DIRECTION_CALIBRATION_ROTATION_KEY = intPreferencesKey("direction_calibration_rotation")
    private val VIBRATION_ENABLED_KEY = booleanPreferencesKey("vibration_enabled")
    private val DIRECTION_ARROW_ENABLED_KEY = booleanPreferencesKey("direction_arrow_enabled")
    private val TAKEOVER_ENABLED_KEY = booleanPreferencesKey("takeover_enabled")
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val DYNAMIC_COLOR_ENABLED_KEY = booleanPreferencesKey("dynamic_color_enabled")
    // Orphan key: rider toggle removed per H5 IA reorder; fixed wave line (WaveStyle.LINE) is used.
    // Retained for backward compatibility and cleaned in resetToDefaults.
    private val WAVE_STYLE_DOTS_KEY = booleanPreferencesKey("wave_style_dots")
    private val ARROW_STYLE_KEY = stringPreferencesKey("arrow_style")
    private val DIRECTION_LABEL_ENABLED_KEY = booleanPreferencesKey("direction_label_enabled")
    private val CLOCK_STYLE_KEY = intPreferencesKey("clock_style")
    private val LOCATION_MODE_KEY = stringPreferencesKey("location_mode")
    // Orphan key: rider toggle removed per H5 IA reorder; existing 24-hour default is used.
    // Retained for backward compatibility and cleaned in resetToDefaults.
    private val USE_24_HOUR_CLOCK_KEY = booleanPreferencesKey("use_24_hour_clock")
    // Default true: the readout has been visible since round 2 shipped it. See the flow below
    // for why this is deliberately NOT the LocationMode switch.
    private val SPEED_READOUT_VISIBLE_KEY = booleanPreferencesKey("speed_readout_visible")
    private val HAS_COMPLETED_ONBOARDING_KEY = booleanPreferencesKey("has_completed_onboarding")
    private val HAS_ACCEPTED_DISCLAIMER_KEY = booleanPreferencesKey("has_accepted_disclaimer")
    private val KEEP_MONITORING_IN_BACKGROUND_KEY = booleanPreferencesKey("keep_monitoring_in_background")
    private val SIREN_ALERT_IN_BACKGROUND_KEY = booleanPreferencesKey("siren_alert_in_background")
    private val DEMO_PANEL_VISITED_KEY = booleanPreferencesKey("demo_panel_visited")
    private val DEVELOPER_MODE_UNLOCKED_KEY = booleanPreferencesKey("developer_mode_unlocked")
    private val CONTRAST_BOOST_MODE_KEY = stringPreferencesKey("contrast_boost_mode")

    fun hasCompletedOnboardingFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[HAS_COMPLETED_ONBOARDING_KEY] ?: false
        }

    suspend fun setOnboardingCompleted(context: Context, completed: Boolean = true) {
        context.dataStore.edit { preferences ->
            preferences[HAS_COMPLETED_ONBOARDING_KEY] = completed
        }
    }

    fun hasAcceptedDisclaimerFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[HAS_ACCEPTED_DISCLAIMER_KEY] ?: false
        }

    suspend fun setDisclaimerAccepted(context: Context, accepted: Boolean = true) {
        context.dataStore.edit { preferences ->
            preferences[HAS_ACCEPTED_DISCLAIMER_KEY] = accepted
        }
    }

    fun demoPanelVisitedFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[DEMO_PANEL_VISITED_KEY] ?: false
        }

    suspend fun setDemoPanelVisited(context: Context, visited: Boolean = true) {
        context.dataStore.edit { preferences ->
            preferences[DEMO_PANEL_VISITED_KEY] = visited
        }
    }

    fun developerModeUnlockedFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[DEVELOPER_MODE_UNLOCKED_KEY] ?: false
        }

    suspend fun setDeveloperModeUnlocked(context: Context, unlocked: Boolean = true) {
        context.dataStore.edit { preferences ->
            preferences[DEVELOPER_MODE_UNLOCKED_KEY] = unlocked
        }
    }

    fun clockStyleFlow(context: Context): Flow<ClockStyle> =
        context.dataStore.data.map { preferences ->
            val ordinal = preferences[CLOCK_STYLE_KEY] ?: ClockStyle.STANDARD.ordinal
            ClockStyle.entries.getOrNull(ordinal) ?: ClockStyle.STANDARD
        }

    suspend fun setClockStyle(context: Context, style: ClockStyle) {
        context.dataStore.edit { preferences ->
            preferences[CLOCK_STYLE_KEY] = style.ordinal
        }
    }

    fun arrowStyleFlow(context: Context): Flow<ArrowStyle> =
        context.dataStore.data.map { preferences ->
            when (preferences[ARROW_STYLE_KEY]) {
                ArrowStyle.ARROW.name -> ArrowStyle.ARROW
                else -> ArrowStyle.CHEVRON
            }
        }

    suspend fun setArrowStyle(context: Context, style: ArrowStyle) {
        context.dataStore.edit { preferences ->
            preferences[ARROW_STYLE_KEY] = style.name
        }
    }

    fun directionLabelEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[DIRECTION_LABEL_ENABLED_KEY] ?: false
        }

    suspend fun setDirectionLabelEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DIRECTION_LABEL_ENABLED_KEY] = enabled
        }
    }

    fun vibrationEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[VIBRATION_ENABLED_KEY] ?: true
        }

    suspend fun setVibrationEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VIBRATION_ENABLED_KEY] = enabled
        }
    }

    fun directionArrowEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[DIRECTION_ARROW_ENABLED_KEY] ?: false
        }

    suspend fun setDirectionArrowEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DIRECTION_ARROW_ENABLED_KEY] = enabled
        }
    }

    fun takeoverEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[TAKEOVER_ENABLED_KEY] ?: true
        }

    suspend fun setTakeoverEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TAKEOVER_ENABLED_KEY] = enabled
        }
    }

    /**
     * Orphan preference: rider toggle removed per H5 IA reorder; fixed wave line is used.
     * Retained for backward compatibility and cleaned in [resetToDefaults].
     */
    fun waveStyleDotsFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[WAVE_STYLE_DOTS_KEY] ?: false
        }

    suspend fun setWaveStyleDots(context: Context, dots: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WAVE_STYLE_DOTS_KEY] = dots
        }
    }

    fun themeModeFlow(context: Context): Flow<String> =
        context.dataStore.data.map { preferences ->
            preferences[THEME_MODE_KEY] ?: "SYSTEM"
        }

    suspend fun setThemeMode(context: Context, mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode
        }
    }

    /**
     * Rider preference for contrast boost in harsh light: AUTO, ALWAYS_ON, or OFF.
     * Defaults to AUTO.
     */
    fun contrastBoostModeFlow(context: Context): Flow<ContrastBoostMode> =
        context.dataStore.data.map { preferences ->
            when (preferences[CONTRAST_BOOST_MODE_KEY]) {
                ContrastBoostMode.ALWAYS_ON.name -> ContrastBoostMode.ALWAYS_ON
                ContrastBoostMode.OFF.name -> ContrastBoostMode.OFF
                else -> ContrastBoostMode.AUTO
            }
        }

    suspend fun setContrastBoostMode(context: Context, mode: ContrastBoostMode) {
        context.dataStore.edit { preferences ->
            preferences[CONTRAST_BOOST_MODE_KEY] = mode.name
        }
    }

    /**
     * How much location the rider has opted into. Default OFF: a new permission on an app that
     * already works without one must never be switched on silently. See [LocationMode].
     */
    fun locationModeFlow(context: Context): Flow<LocationMode> =
        context.dataStore.data.map { preferences ->
            LocationMode.fromName(preferences[LOCATION_MODE_KEY])
        }

    suspend fun setLocationMode(context: Context, mode: LocationMode) {
        context.dataStore.edit { preferences ->
            preferences[LOCATION_MODE_KEY] = mode.name
        }
    }

    /**
     * Orphan preference: rider toggle removed per H5 IA reorder; existing 24-hour default is used.
     * Retained for backward compatibility and cleaned in [resetToDefaults].
     *
     * The HUD clock format. Default true - the round-1 app has always rendered 24-hour, and this
     * preference changes that only when the rider explicitly flips it. When false the HUD renders
     * "h:mm" (no leading zero) plus a separate, small AM/PM mark localized through [Strings]
     * ("SA"/"CH" in Vietnamese, "AM"/"PM" in English) - the mark never enters the clock's
     * stacking/squeeze width math (see [com.soundsight.ui.hud]). Independent of LocationMode by
     * design; the two settings must never collapse into one control.
     */
    fun use24HourClockFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[USE_24_HOUR_CLOCK_KEY] ?: true
        }

    suspend fun setUse24HourClock(context: Context, use24Hour: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_24_HOUR_CLOCK_KEY] = use24Hour
        }
    }

    /**
     * Whether the HUD's speed number is DRAWN - nothing else. Deliberately independent of
     * [LocationMode]: a rider may want the Always-on GNSS accuracy and the measured speed in
     * collision messages/captures without a number competing for attention on the HUD. When this
     * is false, [com.soundsight.core.SpeedProvider] keeps streaming and every recording path
     * keeps its speed column; only the on-screen readout is affected. The two settings must
     * never collapse into one control.
     */
    fun speedReadoutVisibleFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[SPEED_READOUT_VISIBLE_KEY] ?: true
        }

    suspend fun setSpeedReadoutVisible(context: Context, visible: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SPEED_READOUT_VISIBLE_KEY] = visible
        }
    }

    /** Wallpaper-derived Material You chrome is opt-in; semantic hazard colours never use it. */
    fun dynamicColorEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[DYNAMIC_COLOR_ENABLED_KEY] ?: false
        }

    suspend fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_ENABLED_KEY] = enabled
        }
    }

    /**
     * The full direction-calibration state in one read, so the analyzer can distinguish every
     * case it must treat differently:
     *
     *  - [channelsSwappedPresent] false: never calibrated at all.
     *  - present but [calibrationRotation] null: a LEGACY install (A6 migration) - polarity was
     *    measured, but nobody recorded under which rotation, so it cannot be trusted and the
     *    rider needs the one-time recalibration prompt.
     *  - both present: usable, and only at exactly that display rotation.
     */
    data class DirectionCalibrationState(
        val channelsSwappedPresent: Boolean,
        val channelsSwapped: Boolean,
        val calibrationRotation: Int?
    )

    fun directionCalibrationStateFlow(context: Context): Flow<DirectionCalibrationState> =
        context.dataStore.data.map { preferences ->
            DirectionCalibrationState(
                channelsSwappedPresent = preferences.contains(CHANNELS_SWAPPED_KEY),
                channelsSwapped = preferences[CHANNELS_SWAPPED_KEY] ?: false,
                calibrationRotation = preferences[DIRECTION_CALIBRATION_ROTATION_KEY]
            )
        }

    /**
     * Writes polarity AND the rotation it was measured under in ONE DataStore edit, so a reader
     * can never observe one without the other mid-write. Call only with a successful
     * left/right calibration; an unsupported result goes to [setDirectionDisabled] instead and
     * must not leave a usable rotation/polarity pair behind.
     */
    suspend fun setChannelCalibration(context: Context, swapped: Boolean, rotation: Int) {
        context.dataStore.edit { preferences ->
            preferences[CHANNELS_SWAPPED_KEY] = swapped
            preferences[DIRECTION_CALIBRATION_ROTATION_KEY] = rotation
        }
    }

    /** The exact rotation a successful calibration ran at; null before A6 or before calibrating. */
    fun directionCalibrationRotationFlow(context: Context): Flow<Int?> =
        context.dataStore.data.map { preferences ->
            preferences[DIRECTION_CALIBRATION_ROTATION_KEY]
        }

    fun channelsSwappedFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[CHANNELS_SWAPPED_KEY] ?: false
        }

    suspend fun setChannelsSwapped(context: Context, swapped: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CHANNELS_SWAPPED_KEY] = swapped
        }
    }

    fun directionDisabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[DIRECTION_DISABLED_KEY] ?: false
        }

    suspend fun setDirectionDisabled(context: Context, disabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DIRECTION_DISABLED_KEY] = disabled
        }
    }

    fun t2EnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[T2_ENABLED_KEY] ?: true
        }

    suspend fun setT2Enabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[T2_ENABLED_KEY] = enabled
        }
    }

    fun motionSensorsEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[MOTION_SENSORS_ENABLED_KEY] ?: false
        }

    suspend fun setMotionSensorsEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MOTION_SENSORS_ENABLED_KEY] = enabled
        }
    }

    fun languageFlow(context: Context): Flow<AppLanguage> =
        context.dataStore.data.map { preferences ->
            when (preferences[LANGUAGE_KEY]) {
                "EN" -> AppLanguage.EN
                else -> AppLanguage.VI
            }
        }

    fun sensitivityFlow(context: Context): Flow<Sensitivity> =
        context.dataStore.data.map { preferences ->
            val ordinal = preferences[SENSITIVITY_KEY] ?: Sensitivity.MEDIUM.ordinal
            Sensitivity.entries.getOrNull(ordinal) ?: Sensitivity.MEDIUM
        }

    suspend fun setLanguage(context: Context, language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language.name
        }
    }

    suspend fun setSensitivity(context: Context, sensitivity: Sensitivity) {
        context.dataStore.edit { preferences ->
            preferences[SENSITIVITY_KEY] = sensitivity.ordinal
        }
    }

    fun keepMonitoringInBackgroundFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[KEEP_MONITORING_IN_BACKGROUND_KEY] ?: false
        }

    suspend fun setKeepMonitoringInBackground(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEEP_MONITORING_IN_BACKGROUND_KEY] = enabled
        }
    }

    fun sirenAlertInBackgroundFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[SIREN_ALERT_IN_BACKGROUND_KEY] ?: false
        }

    suspend fun setSirenAlertInBackground(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SIREN_ALERT_IN_BACKGROUND_KEY] = enabled
        }
    }

    /**
     * Puts every rider-facing preference back to the value a fresh install would have.
     *
     * Implemented by *removing* the keys rather than writing the defaults in, so there is exactly
     * one place each default is stated - the `?: value` in its own flow above. Writing them here
     * too would be a second copy that silently drifts the first time one of them changes.
     *
     * Three things are deliberately not touched:
     *  - the chosen language, because a rider who cannot read the current language would have no
     *    way back from a reset that switched it,
     *  - the emergency profile, which is personal data and lives in its own store with its own
     *    explicit "delete all of this" control, and
     *  - the calibration results (channel swap, direction-unsupported), which describe what this
     *    particular handset's microphones can do. Those are measurements, not preferences; a reset
     *    that forgot them would silently re-enable direction on hardware already proven unable to
     *    do it.
     */
    suspend fun resetToDefaults(context: Context) {
        context.dataStore.edit { preferences ->
            preferences.remove(SENSITIVITY_KEY)
            preferences.remove(T2_ENABLED_KEY)
            preferences.remove(MOTION_SENSORS_ENABLED_KEY)
            preferences.remove(VIBRATION_ENABLED_KEY)
            preferences.remove(DIRECTION_ARROW_ENABLED_KEY)
            preferences.remove(TAKEOVER_ENABLED_KEY)
            preferences.remove(THEME_MODE_KEY)
            preferences.remove(DYNAMIC_COLOR_ENABLED_KEY)
            preferences.remove(WAVE_STYLE_DOTS_KEY)
            preferences.remove(ARROW_STYLE_KEY)
            preferences.remove(DIRECTION_LABEL_ENABLED_KEY)
            preferences.remove(CLOCK_STYLE_KEY)
            preferences.remove(LOCATION_MODE_KEY)
            preferences.remove(USE_24_HOUR_CLOCK_KEY)
            preferences.remove(SPEED_READOUT_VISIBLE_KEY)
            preferences.remove(KEEP_MONITORING_IN_BACKGROUND_KEY)
            preferences.remove(SIREN_ALERT_IN_BACKGROUND_KEY)
            preferences.remove(DEVELOPER_MODE_UNLOCKED_KEY)
        }
    }
}
