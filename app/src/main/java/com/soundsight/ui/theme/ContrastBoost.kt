package com.soundsight.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp

/**
 * The continuous form of the harsh-light contrast boost.
 *
 * The boost used to be a boolean: below 10,000 lux the app looked like itself, above it every
 * surface flipped to black and white in a single frame. Two things were wrong with that.
 *
 *  1. **It was a step.** A rider coming out of a tunnel got the entire screen re-painted at once,
 *     in peripheral vision, at speed. A hard palette swap is itself a movement cue - the eye reads
 *     it as *something happened* and looks down. The boost is supposed to make the screen easier
 *     to read, not compete with the road for attention.
 *  2. **It was all-or-nothing in magnitude.** 10,500 lux (a bright overcast noon) and 80,000 lux
 *     (direct tropical sun on a bar mount) got the identical maximum treatment, so the boost was
 *     tuned for the worst case and therefore always overshot the common one.
 *
 * Both are fixed by making the boost a *level* in `0f..1f` instead of a flag, and interpolating
 * every colour toward its boosted counterpart by that level. [HazardColors.Boosted] is hue-matched
 * to [HazardColors.Standard] entry for entry, so an intermediate level is never an illegal colour -
 * a half-boosted critical is simply a deeper red, still unmistakably the same signal.
 *
 * The level is fed through an animation in [PulsePathTheme], so a change in lux arrives as a slow
 * cross-fade rather than a cut. See [HarshLightDetector] for how lux becomes a level, and note that
 * the dwell/hysteresis gate is unchanged and still upstream of all of this: the ramp smooths a
 * transition that has already been judged real, it does not replace the judgement.
 */
object ContrastBoost {

    /**
     * How long a full 0 -> 1 travel takes. Deliberately slow: fast enough that the screen is fully
     * boosted well within the time it takes to clear a tunnel mouth, slow enough that the change is
     * below the threshold where peripheral vision reports it as an event.
     */
    const val RAMP_DURATION_MS = 900

    /** Level at or above which discrete (non-interpolatable) treatments switch over. */
    const val DISCRETE_SWITCH_LEVEL = 0.5f

    /**
     * Alpha for the alert takeover's secondary text at [level].
     *
     * The caption is painted at 82% on purpose in normal light - it is subordinate to the headline
     * and the glyph. Under boost that hierarchy is a luxury: a washed-out caption in direct sun is
     * simply an unreadable caption, and it was the one pair in the palette contract that could not
     * clear AAA. It firms up to fully opaque as the boost comes on.
     */
    fun captionAlpha(level: Float): Float = lerpFloat(0.82f, 1f, level)

    /**
     * Alpha for the takeover's translucent chips (demo badge, dismiss hint, direction pill).
     *
     * These are fills behind text, not text, so they do not need AAA - but at 16-22% over a
     * *deepened* boosted red they stop being visible at all, and the chip's job is to say "this
     * is a distinct thing". They roughly double across the ramp.
     */
    fun chipAlpha(level: Float, base: Float): Float =
        lerpFloat(base, (base * 2.1f).coerceAtMost(0.46f), level)

    /** Interpolates any opacity from its normal-light value to its fully-boosted one. */
    fun alpha(level: Float, normal: Float, boosted: Float): Float = lerpFloat(normal, boosted, level)

    /** Border weight for glanceable outlined elements: heavier as the boost comes on. */
    fun borderWidth(level: Float, normal: Dp, boosted: Dp): Dp = lerp(normal, boosted, clamp(level))

    /** Interpolates a whole hazard palette toward [HazardColors.Boosted]. */
    fun lerp(level: Float): HazardPalette {
        val t = clamp(level)
        if (t <= 0f) return HazardColors.Standard
        if (t >= 1f) return HazardColors.Boosted
        val a = HazardColors.Standard
        val b = HazardColors.Boosted
        return HazardPalette(
            Critical = lerp(a.Critical, b.Critical, t),
            OnCritical = lerp(a.OnCritical, b.OnCritical, t),
            CriticalSoft = lerp(a.CriticalSoft, b.CriticalSoft, t),
            OnCriticalSoft = lerp(a.OnCriticalSoft, b.OnCriticalSoft, t),
            CriticalMuted = lerp(a.CriticalMuted, b.CriticalMuted, t),
            Warning = lerp(a.Warning, b.Warning, t),
            OnWarning = lerp(a.OnWarning, b.OnWarning, t),
            WarningMuted = lerp(a.WarningMuted, b.WarningMuted, t),
            Live = lerp(a.Live, b.Live, t),
            OnLive = lerp(a.OnLive, b.OnLive, t),
            Degraded = lerp(a.Degraded, b.Degraded, t),
            Scrim = lerp(a.Scrim, b.Scrim, t)
        )
    }

    /**
     * Interpolates the chrome scheme from [base] toward [BoostedDarkScheme].
     *
     * [base] may be the brand light scheme, the brand dark scheme, or a wallpaper-derived dynamic
     * one, so the boost is expressed as a blend rather than a substitution: whatever the rider's
     * chrome looked like a moment ago, it walks from there to the boosted scheme instead of being
     * replaced by it.
     */
    fun lerpScheme(base: ColorScheme, level: Float): ColorScheme {
        val t = clamp(level)
        if (t <= 0f) return base
        val b = BoostedDarkScheme
        fun c(from: Color, to: Color) = lerp(from, to, t)
        return darkColorScheme(
            primary = c(base.primary, b.primary),
            onPrimary = c(base.onPrimary, b.onPrimary),
            primaryContainer = c(base.primaryContainer, b.primaryContainer),
            onPrimaryContainer = c(base.onPrimaryContainer, b.onPrimaryContainer),
            inversePrimary = c(base.inversePrimary, b.inversePrimary),
            secondary = c(base.secondary, b.secondary),
            onSecondary = c(base.onSecondary, b.onSecondary),
            secondaryContainer = c(base.secondaryContainer, b.secondaryContainer),
            onSecondaryContainer = c(base.onSecondaryContainer, b.onSecondaryContainer),
            tertiary = c(base.tertiary, b.tertiary),
            onTertiary = c(base.onTertiary, b.onTertiary),
            tertiaryContainer = c(base.tertiaryContainer, b.tertiaryContainer),
            onTertiaryContainer = c(base.onTertiaryContainer, b.onTertiaryContainer),
            error = c(base.error, b.error),
            onError = c(base.onError, b.onError),
            errorContainer = c(base.errorContainer, b.errorContainer),
            onErrorContainer = c(base.onErrorContainer, b.onErrorContainer),
            background = c(base.background, b.background),
            onBackground = c(base.onBackground, b.onBackground),
            surface = c(base.surface, b.surface),
            onSurface = c(base.onSurface, b.onSurface),
            surfaceVariant = c(base.surfaceVariant, b.surfaceVariant),
            onSurfaceVariant = c(base.onSurfaceVariant, b.onSurfaceVariant),
            surfaceTint = c(base.surfaceTint, b.surfaceTint),
            inverseSurface = c(base.inverseSurface, b.inverseSurface),
            inverseOnSurface = c(base.inverseOnSurface, b.inverseOnSurface),
            outline = c(base.outline, b.outline),
            outlineVariant = c(base.outlineVariant, b.outlineVariant),
            scrim = c(base.scrim, b.scrim),
            surfaceBright = c(base.surfaceBright, b.surfaceBright),
            surfaceDim = c(base.surfaceDim, b.surfaceDim),
            surfaceContainerLowest = c(base.surfaceContainerLowest, b.surfaceContainerLowest),
            surfaceContainerLow = c(base.surfaceContainerLow, b.surfaceContainerLow),
            surfaceContainer = c(base.surfaceContainer, b.surfaceContainer),
            surfaceContainerHigh = c(base.surfaceContainerHigh, b.surfaceContainerHigh),
            surfaceContainerHighest = c(base.surfaceContainerHighest, b.surfaceContainerHighest)
        )
    }

    private fun lerpFloat(from: Float, to: Float, level: Float): Float {
        val t = clamp(level)
        return from + (to - from) * t
    }

    private fun clamp(level: Float): Float = level.coerceIn(0f, 1f)
}
