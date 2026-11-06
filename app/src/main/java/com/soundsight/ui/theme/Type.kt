package com.soundsight.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.soundsight.core.ClockStyle

// ─────────────────────────────────────────────────────────────────────────────
// Display font family for the clock.
//
// Google Sans Flex is requested by *device family name* rather than bundled or
// downloaded. Bundling the variable font would add ~1.7MB of binary that the AI
// Studio project cannot receive (prompts are text), and the downloadable-fonts
// provider would put a network fetch in front of the one element a rider reads
// to check the time. Naming the installed family costs neither.
//
// Each Font here is a candidate, tried in order; Compose falls through to the
// next when a family is not installed, and to the platform default when none
// are. Typeface.create - what this resolves to underneath - returns the default
// typeface for an unknown family rather than throwing, so on a device with none
// of these the clock simply renders in the system font at the same size.
//
// The names differ by OEM and Android version, which is why several are listed
// rather than one: "google-sans-flex" is the Android 16 Pixel family, and the
// two older names cover Pixels that shipped Google Sans before the variable
// font replaced it.
// ─────────────────────────────────────────────────────────────────────────────

private fun deviceFamily(name: String, weight: FontWeight) =
    Font(DeviceFontFamilyName(name), weight)

val DisplayFontFamily = FontFamily(
    deviceFamily("google-sans-flex", FontWeight.W500),
    deviceFamily("google-sans-flex", FontWeight.W700),
    deviceFamily("google-sans-text", FontWeight.W500),
    deviceFamily("google-sans-text", FontWeight.W700),
    deviceFamily("google-sans", FontWeight.W500),
    deviceFamily("google-sans", FontWeight.W700)
)

// ─────────────────────────────────────────────────────────────────────────────
// Named styles kept from the pre-Material-You theme. Ten screens reference these
// directly; they stay, and are also mapped into the M3 scale below so that stock
// M3 components (ListItem, TopAppBar, Switch labels) pick up the same type.
//
// The scale is a deliberate customization: compressed at the top, enlarged and
// heavy at the bottom. Alert text is NOT "larger than M3 default displayLarge" -
// displayLarge here is 44sp against M3's 57sp baseline - and no rationale should
// claim otherwise; the ramp trades top-end size for more distinct mid roles and
// bigger small roles (body/label) so glanceable controls read in sunlight. That
// trade is validated on the device, not by comparison with stock tokens.
//
// Known cost of the compression: some adjacent roles are close (headlineSmall
// and titleLarge both render 20sp), so hierarchy must be carried by weight and
// layout as much as by size.
// ─────────────────────────────────────────────────────────────────────────────

val AlertLabel = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W800,
    fontSize = 44.sp,
    lineHeight = 48.sp,
    letterSpacing = 0.sp
)

val AlertSub = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W500,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp
)

val IdleStatus = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W500,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp
)

val HistoryRow = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W400,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp
)

/**
 * The HUD clock.
 *
 * Deliberately the largest thing on the screen after the wave. On a bar-mounted phone the clock is
 * the element read most often and for the shortest time - a glance at speed, not a look - so it is
 * sized to be legible without focusing rather than sized to fit a header row.
 *
 * `tnum` selects tabular figures: without it the digits are proportionally spaced and the whole
 * clock shifts sideways every time a 1 appears or leaves, which at this size is a visible twitch
 * in peripheral vision once a minute.
 */
val ClockDisplay = TextStyle(
    fontFamily = DisplayFontFamily,
    fontWeight = FontWeight.W500,
    fontSize = 46.sp,
    lineHeight = 50.sp,
    letterSpacing = (-1).sp,
    fontFeatureSettings = "tnum"
)

/**
 * The other three clock presets a tap on [ClockDisplay] cycles through, plus [clockStyleFor] which
 * resolves an [AlertConfig.clockStyle][com.soundsight.core.AlertConfig.clockStyle] value to its
 * style. Same 24h content in every one - `tnum` is non-negotiable on all four so the swap never
 * introduces the digit-twitch [ClockDisplay]'s own doc warns about.
 */
/**
 * The THIN preset. A 300 weight may be unavailable on some OEMs and synthesized (faked) from the
 * nearest installed weight - and a hairline clock is a direct-sun risk regardless. STANDARD is and
 * stays the default; if field testing fails THIN in sunlight, its effective weight is raised here
 * or it is removed from riding-mode choices - not by shrinking the whole scale.
 */
private val ClockDisplayThin = TextStyle(
    fontFamily = DisplayFontFamily,
    fontWeight = FontWeight.W300,
    fontSize = 46.sp,
    lineHeight = 50.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum"
)

private val ClockDisplayMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.W600,
    fontSize = 42.sp,
    lineHeight = 46.sp,
    letterSpacing = 1.sp,
    fontFeatureSettings = "tnum"
)

private val ClockDisplayBoxed = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.W800,
    fontSize = 38.sp,
    lineHeight = 42.sp,
    letterSpacing = 4.sp,
    fontFeatureSettings = "tnum"
)

fun clockStyleFor(preset: ClockStyle): TextStyle = ClockTypography.forStyle(preset)

/**
 * The four clock presets as one extended, product-specific data-display role.
 *
 * They are four variants of a single "clock" role - not four competing app-wide type systems -
 * and this is the type-level statement of that. The 15-role M3 [Typography] above stays a normal,
 * stock-shaped scale; the clock variants hang off it through [LocalClockTypography] instead of
 * being smuggled in as extra top-bar/display tokens. Organizational only: nothing here changes
 * sizes, lowers THIN's weight, or bundles a font. Those remain field-test decisions.
 */
@Immutable
data class PulseClockTypography(
    val standard: TextStyle,
    val thin: TextStyle,
    val mono: TextStyle,
    val boxed: TextStyle
) {
    fun forStyle(style: ClockStyle): TextStyle = when (style) {
        ClockStyle.STANDARD -> standard
        ClockStyle.THIN -> thin
        ClockStyle.MONO -> mono
        ClockStyle.BOXED -> boxed
    }
}

val ClockTypography = PulseClockTypography(
    standard = ClockDisplay,
    thin = ClockDisplayThin,
    mono = ClockDisplayMono,
    boxed = ClockDisplayBoxed
)

val BoostedClockTypography = PulseClockTypography(
    standard = ClockDisplay.copy(fontWeight = FontWeight.ExtraBold),
    thin = ClockDisplayThin.copy(fontWeight = FontWeight.SemiBold),
    mono = ClockDisplayMono.copy(fontWeight = FontWeight.ExtraBold),
    boxed = ClockDisplayBoxed.copy(fontWeight = FontWeight.Black)
)

/** Provided by [PulsePathTheme]; read with `LocalClockTypography.current.forStyle(style)`. */
val LocalClockTypography = staticCompositionLocalOf { ClockTypography }

/** Settings section headers and card titles. */
val SectionLabel = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W600,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.6.sp
)

/** Small supporting text under a settings row. */
val SupportingText = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W400,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = AlertLabel,
    displayMedium = AlertLabel.copy(fontSize = 36.sp, lineHeight = 42.sp),
    displaySmall = AlertLabel.copy(fontSize = 30.sp, lineHeight = 36.sp),
    headlineLarge = AlertSub.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.W600),
    headlineMedium = AlertSub,
    headlineSmall = AlertSub.copy(fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = IdleStatus.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.W600),
    titleMedium = IdleStatus,
    titleSmall = IdleStatus.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = HistoryRow.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = HistoryRow,
    bodySmall = SupportingText,
    labelLarge = IdleStatus.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600),
    labelMedium = SectionLabel,
    labelSmall = SectionLabel.copy(fontSize = 12.sp, lineHeight = 16.sp)
)

/**
 * Heavier type weights for harsh-light contrast boost mode.
 * Thickens all type roles to remain punchy and glanceable against intense daylight glare.
 */
val BoostedTypography = Typography(
    displayLarge = AlertLabel.copy(fontWeight = FontWeight.Black),
    displayMedium = AlertLabel.copy(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black),
    displaySmall = AlertLabel.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black),
    headlineLarge = AlertSub.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black),
    headlineMedium = AlertSub.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = AlertSub.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = IdleStatus.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = IdleStatus.copy(fontWeight = FontWeight.ExtraBold),
    titleSmall = IdleStatus.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = HistoryRow.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    bodyMedium = HistoryRow.copy(fontWeight = FontWeight.Bold),
    bodySmall = SupportingText.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = IdleStatus.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold),
    labelMedium = SectionLabel.copy(fontWeight = FontWeight.ExtraBold),
    labelSmall = SectionLabel.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
)
