package com.soundsight.ui.theme

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Hazard colours are provided separately from [MaterialTheme.colorScheme] on purpose.
 *
 * The colour scheme may be wallpaper-derived (Material You). Hazard colours may not: an alert's
 * meaning is carried by its colour, and a rider who has learned "red means stop looking at the
 * screen and react" cannot have that remapped by their wallpaper. Read alert colours from here.
 */
val LocalHazardColors = staticCompositionLocalOf<HazardPalette> { HazardColors }

/**
 * Whether the high-contrast boost in harsh light is currently past its halfway point.
 *
 * Kept for treatments that genuinely cannot be interpolated - a font weight, a shape swap. Anything
 * that *can* vary continuously should read [LocalContrastBoostLevel] instead, so it fades with the
 * rest of the screen rather than popping at the midpoint.
 */
val LocalContrastBoosted = staticCompositionLocalOf { false }

/**
 * How strongly the harsh-light boost currently applies, in `0f..1f`, already animated.
 *
 * Read this for border widths, alphas, elevations and anything else that can take an intermediate
 * value: the boost fades in over [ContrastBoost.RAMP_DURATION_MS] and scales with how harsh the
 * light actually is, so a component that branches on a boolean will jump while everything around
 * it is still moving.
 */
val LocalContrastBoostLevel = staticCompositionLocalOf { 0f }

/**
 * Which light/dark treatment to use.
 *
 * [SYSTEM] is the default because a rider's phone is usually already set to switch with daylight,
 * which is exactly the signal this app wants. The explicit overrides exist because a bar-mounted
 * phone in direct sun is a different problem from a phone in a pocket, and the rider is the only
 * one who knows which they have.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * PulsePath theme: Material 3 with a custom glance-optimized motion scheme and selected
 * Expressive-inspired shape/interaction treatments.
 *
 * The accurate description matters more than it sounds: this is **not** spec-accurate Material 3
 * Expressive and should not be documented as such. What is actually here:
 *
 *  - a normal [MaterialTheme] with complete light/dark brand schemes and opt-in dynamic colour
 *    on Android 12+;
 *  - hazard colours provided *alongside* the scheme through [LocalHazardColors], invariant across
 *    wallpaper/theme/device, exactly as Google's custom-design-system and retained-brand-colour
 *    guidance prescribes;
 *  - a rounder-than-stock custom shape scale ([Shapes]) with some Expressive character;
 *  - motion that follows the M3 spatial/effects taxonomy but is a stiffer Standard-like scheme,
 *    not Expressive physics - see [Motion] for the honest breakdown;
 *  - none of the newest Expressive component defaults, flexible app bars, 35-shape library or
 *    emphasized typography roles.
 *
 * Material 3 Expressive's public surface (`MaterialExpressiveTheme`, `MotionScheme`,
 * `ExperimentalMaterial3ExpressiveApi`) only exists in material3 1.5.0-alpha; stable 1.3.0/1.4.0
 * declare it internal or absent. Pulling an alpha into a safety-critical app to reach it is not a
 * trade worth making, so compatible tokens are hand-declared instead.
 *
 * @param themeMode light/dark selection; [ThemeMode.SYSTEM] follows the device.
 * @param dynamicColor opt-in only (default false): when enabled in Appearance and the device is
 * Android 12+, chrome colours are derived from the user's wallpaper. Otherwise the wave, status
 * chip and controls use PulsePath's fixed brand scheme - which since round 2 (2026-08-24) IS the
 * framework's default dynamic palette, so on devices that do no wallpaper extraction the toggle
 * is on/off-identical by construction. Never affects hazard colours - see [LocalHazardColors].
 * @param contrastBoosted convenience for a fully on/off boost; ignored when [contrastBoostLevel]
 * is given explicitly.
 * @param contrastBoostLevel how strongly the harsh-light boost applies, `0f..1f`. The scheme, the
 * hazard palette and the border/alpha treatments are all interpolated by this value and animated
 * over [ContrastBoost.RAMP_DURATION_MS], so the boost arrives as a slow cross-fade whose depth
 * matches the light rather than as a single-frame swap to black and white. Hazard hues are held
 * across the whole ramp - see [HazardColors.Boosted].
 */
@Composable
fun PulsePathTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    contrastBoosted: Boolean = false,
    contrastBoostLevel: Float = if (contrastBoosted) 1f else 0f,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current

    // Animating the level here, once, is what keeps the whole screen moving together: every colour
    // below is derived from `level`, so they share a single clock instead of each component running
    // its own transition and arriving at slightly different times.
    val level by animateFloatAsState(
        targetValue = contrastBoostLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = ContrastBoost.RAMP_DURATION_MS),
        label = "contrastBoostLevel"
    )
    val pastMidpoint = level >= ContrastBoost.DISCRETE_SWITCH_LEVEL

    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDarkScheme
        else -> BrandLightScheme
    }
    val colorScheme = ContrastBoost.lerpScheme(baseScheme, level)

    val hazardPalette = ContrastBoost.lerp(level)
    // Weight and letter spacing cannot be interpolated the way a colour can, so these two swap at
    // the midpoint of the ramp, by which point the surrounding colour change is already well under
    // way and the reflow reads as part of it rather than as its own event.
    val typography = if (pastMidpoint) BoostedTypography else Typography
    val clockTypography = if (pastMidpoint) BoostedClockTypography else ClockTypography

    CompositionLocalProvider(
        LocalHazardColors provides hazardPalette,
        LocalClockTypography provides clockTypography,
        LocalContrastBoosted provides pastMidpoint,
        LocalContrastBoostLevel provides level
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = Shapes,
            content = content
        )
    }
}

/**
 * Compatibility overload for callers that only pass a dark/light boolean, notably the Roborazzi
 * screenshot harness, which renders a fixed theme rather than following a device setting.
 */
@Composable
fun PulsePathTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    contrastBoosted: Boolean = false,
    contrastBoostLevel: Float = if (contrastBoosted) 1f else 0f,
    content: @Composable () -> Unit
) {
    PulsePathTheme(
        themeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT,
        dynamicColor = dynamicColor,
        contrastBoosted = contrastBoosted,
        contrastBoostLevel = contrastBoostLevel,
        content = content
    )
}
