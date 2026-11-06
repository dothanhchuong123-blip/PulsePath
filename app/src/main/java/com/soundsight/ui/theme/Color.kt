package com.soundsight.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// HAZARD PALETTE — CONSTANT, NEVER DYNAMIC
//
// These are safety signals, not decoration. A Deaf rider at 40km/h identifies an
// alert by its colour before reading a single word, so the mapping
// red = CRITICAL / amber = WARNING / teal = listening must be identical on every
// device regardless of wallpaper, theme, or light/dark mode. Material You dynamic
// colour is deliberately NOT applied to anything in this block.
//
// Measured sRGB WCAG contrast ratios (see ColorContrastTest for the live contract):
//   HazardCritical  #E5484D / HazardOnCritical #FFFFFF = 3.91:1
//     - clears the 3:1 large-text / essential non-text floor only. NOT a reliable
//       4.5:1 normal-text pair: caption-sized white-on-red must either be treated as
//       large text or moved onto a fixed opaque dark text container.
//   HazardWarning   #F5A524 / HazardOnWarning  #10131A = 9.10:1
//   HazardCriticalSoft #FF8A8F / HazardOnCriticalSoft #4A0E10 = 6.82:1
//   HazardDegraded #64748B on a dark sheet surface ≈ 3:1 - acceptable for large or
//     non-text use only; ordinary-size degraded text belongs on theme surfaces.
//   BoostMode Idle  #000000 / OnBackground     #FFFFFF = 21.00:1 (AAA >= 7:1)
//   BoostCritical   #A80009 / OnCritical       #FFFFFF =  7.87:1 (AAA >= 7:1)
//   BoostWarning    #FFC400 / OnWarning        #000000 = 13.15:1 (AAA >= 7:1)
//   BoostCriticalSoft #FFC2C4 / OnCriticalSoft #3D0004 = 11.47:1 (AAA >= 7:1)
//   BoostLive       #3DEBBE / OnLive           #00120C = 12.66:1 (AAA >= 7:1)
// The boost row deepens or brightens each hazard hue along its own axis - it does NOT
// collapse to black and white. Red stays red, amber stays amber, teal stays teal; only
// the distance to their foreground grows. A monochrome boost buys contrast by spending
// the one cue a rider reads before any word, and is therefore a net safety loss.
// Sunlight on a bar-mounted phone is harder than any desktop calculation here:
// these numbers are a floor to build on, not the field acceptance criterion.
// ─────────────────────────────────────────────────────────────────────────────

open class HazardPalette(
    open val Critical: Color,
    open val OnCritical: Color,
    open val CriticalSoft: Color,
    open val OnCriticalSoft: Color,
    open val CriticalMuted: Color,
    open val Warning: Color,
    open val OnWarning: Color,
    open val WarningMuted: Color,
    open val Live: Color,
    open val OnLive: Color,
    open val Degraded: Color,
    open val Scrim: Color
)

object HazardColors : HazardPalette(
    Critical = Color(0xFFE5484D),
    OnCritical = Color(0xFFFFFFFF),
    CriticalSoft = Color(0xFFFF8A8F),
    OnCriticalSoft = Color(0xFF4A0E10),
    CriticalMuted = Color(0xFF8E1F22),
    Warning = Color(0xFFF5A524),
    OnWarning = Color(0xFF10131A),
    WarningMuted = Color(0xFF7A5210),
    Live = Color(0xFF22D3A6),
    OnLive = Color(0xFF0B0F14),
    Degraded = Color(0xFF64748B),
    Scrim = Color(0xCC000000)
) {
    val Standard: HazardPalette get() = this

    /**
     * High-contrast hazard palette for the far end of the harsh-light boost ramp.
     *
     * Every foreground/background pair satisfies WCAG AAA >= 7:1, and every hazard keeps its
     * hue. This is the whole point of the palette: boost raises contrast *within* red, amber
     * and teal rather than replacing them. Red can only reach 7:1 against white by getting
     * darker (a saturated red bottoms out near 5.25:1 against black, so black ink on a bright
     * red is not reachable at AAA), so [Critical] deepens; amber and teal are already light,
     * so they brighten and take pure-black ink instead.
     *
     * Because standard and boosted entries are the same hue, [ContrastBoost.lerp] can be run
     * at any intermediate level and every step in between is still a legal hazard colour -
     * that is what lets the boost fade in gradually instead of snapping.
     *
     * The muted entries inverted direction on purpose: they mark history rows and tier dots on
     * a near-black surface, where "dimmer" means "invisible". Under boost they read as lighter
     * tints of the same hue, still clearly subordinate to a full-bleed takeover.
     */
    val Boosted: HazardPalette = HazardPalette(
        Critical = Color(0xFFA80009),
        OnCritical = Color(0xFFFFFFFF),
        CriticalSoft = Color(0xFFFFC2C4),
        OnCriticalSoft = Color(0xFF3D0004),
        CriticalMuted = Color(0xFFFF7A7E),
        Warning = Color(0xFFFFC400),
        OnWarning = Color(0xFF000000),
        WarningMuted = Color(0xFFFFB300),
        Live = Color(0xFF3DEBBE),
        OnLive = Color(0xFF00120C),
        Degraded = Color(0xFFCBD5E1),
        Scrim = Color(0xF2000000)
    )
}

object BoostedColors {
    val Background = Color(0xFF000000)
    val OnBackground = Color(0xFFFFFFFF)
    val Surface = Color(0xFF000000)
    val OnSurface = Color(0xFFFFFFFF)
    val Outline = Color(0xFFFFFFFF)
}

// ─────────────────────────────────────────────────────────────────────────────
// LEGACY ALIASES
//
// Ten existing UI files import these eight names directly. They are kept so that
// this design-system swap touches no screen code. New code should read
// LocalHazardColors.current or MaterialTheme.colorScheme instead.
// ─────────────────────────────────────────────────────────────────────────────
val BgIdle = Color(0xFF0B0F14)
val FgIdle = Color(0xFFE6EDF3)
val AccentLive = HazardColors.Live
val WarningBg = HazardColors.Warning
val WarningFg = HazardColors.OnWarning
val CriticalBg = HazardColors.Critical
val CriticalFg = HazardColors.OnCritical
val Degraded = HazardColors.Degraded

// ─────────────────────────────────────────────────────────────────────────────
// BRAND SCHEME
//
// Used on every API level by default. Dynamic colour is an explicit opt-in only.
//
// Round 2 (2026-08-24): the brand identity IS the AOSP default dynamic palette.
// Every value below was transcribed from a debug-build dump of
// dynamicLightColorScheme()/dynamicDarkColorScheme() on the target device
// (Xiaomi 13, 2211133C "fuxi", Android 16 / API 36), whose Settings.Secure
// `theme_customization_overlay_packages` carries no
// `android.theme.customization.system_palette` - HyperOS does not populate the
// dynamic palette from the wallpaper, so on this device the framework always
// returns this exact blue. Making the brand scheme BE that palette means the
// wallpaper toggle is on/off-identical here by construction, and the default
// look is the blue the user already had and prefers. On a Pixel that does
// extract, the toggle still swaps in a real wallpaper-derived palette.
// Cross-checks from the same session's screenshot sampling: light primary
// #475D92 and background #FAF8FF match; the status pill's #DDDFED is the
// translucent chip (primary @ 16% over background), not a scheme role; the
// history button's #DCE2F9 is secondaryContainer.
//
// Live remains an invariant safety signal; these roles are
// decorative chrome and can be replaced by wallpaper colours when the rider opts in.
// Dark scheme is the primary design target: this is a night-riding app on mostly-OLED phones.
// ─────────────────────────────────────────────────────────────────────────────
internal val BrandDarkScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF152E60),
    primaryContainer = Color(0xFF2F4578),
    onPrimaryContainer = Color(0xFFD9E2FF),
    inversePrimary = Color(0xFF475D92),
    secondary = Color(0xFFC0C6DC),
    onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF404659),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFE0BBDD),
    onTertiary = Color(0xFF412742),
    tertiaryContainer = Color(0xFF593D59),
    onTertiaryContainer = Color(0xFFFDD7FA),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFB0C6FF),
    inverseSurface = Color(0xFFFAF8FF),
    inverseOnSurface = Color(0xFF1A1B20),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33343A)
)

internal val BrandLightScheme = lightColorScheme(
    primary = Color(0xFF475D92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF2F4578),
    inversePrimary = Color(0xFFB0C6FF),
    secondary = Color(0xFF575E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF404659),
    tertiary = Color(0xFF725572),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDD7FA),
    onTertiaryContainer = Color(0xFF593D59),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceTint = Color(0xFF475D92),
    inverseSurface = Color(0xFF121318),
    inverseOnSurface = Color(0xFFE2E2E9),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9)
)

/**
 * Chrome scheme for the far end of the boost ramp.
 *
 * Round 3 (2026-09-07): this used to be every role forced to #FFFFFF or #000000, which made the
 * whole app - alerts included - read as a monochrome fax at exactly the moment a rider most needs
 * to tell one signal from another. It is now the brand's blue identity pushed to AAA against a
 * near-black ground rather than erased: primary is a brighter blue, error is drawn from the same
 * boosted red family as [HazardColors.Boosted], and only the surface stack goes fully black, which
 * is what actually buys the contrast on an OLED panel in the sun.
 *
 * Every foreground/background role pair here clears 7:1; outlineVariant is a divider, held at the
 * 3:1 non-text floor so it stays a hairline instead of a second border.
 */
internal val BoostedDarkScheme = darkColorScheme(
    primary = Color(0xFFC7D7FF),
    onPrimary = Color(0xFF001945),
    primaryContainer = Color(0xFF0F2A5C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    inversePrimary = Color(0xFF0F2A5C),
    secondary = Color(0xFFD6DCF0),
    onSecondary = Color(0xFF101526),
    secondaryContainer = Color(0xFF1C2438),
    onSecondaryContainer = Color(0xFFE6EBFF),
    tertiary = Color(0xFFF3CFF0),
    onTertiary = Color(0xFF2A0F2B),
    tertiaryContainer = Color(0xFF3A1F3B),
    onTertiaryContainer = Color(0xFFFBDDF8),
    error = Color(0xFFFF7A7E),
    onError = Color(0xFF2B0002),
    errorContainer = Color(0xFFA80009),
    onErrorContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF0E0E12),
    onSurfaceVariant = Color(0xFFE8E9F2),
    surfaceTint = Color(0xFFC7D7FF),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    outline = Color(0xFFB9BCC8),
    outlineVariant = Color(0xFF6E7280),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1A1B22),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF06070A),
    surfaceContainer = Color(0xFF0B0C11),
    surfaceContainerHigh = Color(0xFF121319),
    surfaceContainerHighest = Color(0xFF1A1B22)
)
