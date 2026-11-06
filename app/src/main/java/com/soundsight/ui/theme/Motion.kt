package com.soundsight.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * Material 3 motion for PulsePath: the M3 spatial/effects taxonomy with a custom,
 * glance-optimized, Standard-like spring scheme.
 *
 * This is deliberately **not** spec-accurate Expressive physics and must not be described as
 * such. The taxonomy is current M3 (spatial springs move things; effects springs change colour
 * and alpha), and the easing curves match the published AndroidX tokens, but every damping/
 * stiffness pair below is a stiffer variant of Google's *Standard* scheme - not a retuned
 * Expressive scheme. That distinction matters because published Expressive spatial motion is
 * softer and substantially bouncier, tuned for prominent/hero interactions looked at directly;
 * PulsePath's HUD is a recurring/utilitarian surface read in half-second glances, exactly the
 * context Google recommends Standard motion for.
 *
 * `androidx.compose.material3.MotionScheme` exists in material3 1.4.0 but is `internal` - it only
 * becomes public API in 1.5.0-alpha - so `MaterialTheme.motionScheme` cannot be read here. The
 * Standard-scheme reference values cited below are from Google's generated `StandardMotionTokens`.
 *
 * The distinction that matters:
 *  - **Spatial** springs move things (position, size, corner radius, scale). They may overshoot
 *    slightly; that overshoot is what reads as physical rather than mechanical.
 *  - **Effects** springs change non-spatial properties (colour, alpha). They are critically damped
 *    at 1.0 - a colour that overshoots looks like a bug, not like physics.
 *
 * Springs rather than tweens because a spring carries velocity through an interruption. When the
 * mic level jumps while the previous animation is still running, a tween restarts from wherever it
 * happened to be; a spring continues, which is the whole reason the wave does not stutter.
 *
 * ## Why these diverge from the published Standard defaults
 *
 * Google's Standard reference values (0.9 / 700 default spatial, 0.9 / 300 slow) are tuned for a
 * phone held still and looked at directly. On a bar-mounted phone read in half-second glances
 * they were wrong in two ways at once: the low stiffness meant a transition was still settling
 * when the rider had already looked back at the road, and any visible secondary oscillation after
 * arrival read as wobbling rather than as physics. Both are retuned below - stiffness raised so
 * motion is finished, not merely started, within a glance, and damping raised so overshoot is a
 * hint rather than a bounce.
 *
 * The one exception is [snapSpatial], which is deliberately bouncy. Because it is the *only*
 * bouncy spring in the app, bounce means something specific wherever it appears: a value has
 * snapped to a discrete stop. Spread across every animation, that same bounce read as ambient
 * fidgeting - which is the "heavy and repetitive" failure this scale is tuned to avoid.
 *
 * ## Measurable acceptance criteria
 *
 * Any retune of these values must preserve all of the following:
 *  - an alert is fully present on screen within its fixed 120ms entrance ([DurationAlertIn]);
 *  - no recurring HUD spatial transition shows a visible secondary oscillation during a
 *    half-second glance;
 *  - effects springs never overshoot;
 *  - the sensitivity slider's snap remains the only bouncy interaction in the app.
 */
object Motion {

    // ── Spatial: position, size, scale, shape ────────────────────────────────

    /**
     * Small, immediate movements - icon swaps, chip state, anything under about 24dp of travel.
     * Critically damped: at this size an overshoot is not read as physicality, only as jitter.
     */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1600f)

    /** The default for most movement on screen. A trace of overshoot, gone within a glance. */
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.92f, stiffness = 900f)

    /**
     * Large or full-screen movement that should feel weighty. Reserve for things that actually
     * occupy the screen - used on small elements this is what makes a UI feel sluggish.
     */
    fun <T> slowSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 450f)

    /**
     * The app's only deliberately bouncy spring: a value landing on a discrete stop.
     *
     * Used by the sensitivity slider's step snap and nothing else. See the class doc for why the
     * exclusivity is the point.
     */
    fun <T> snapSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = 900f)

    // ── Effects: colour, alpha ───────────────────────────────────────────────
    /** Critically damped; never overshoots. */
    fun <T> fastEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 3800f)

    fun <T> defaultEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1800f)

    fun <T> slowEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 900f)

    /**
     * Spatial spring for [Dp] and [IntSize] animations, which need an explicit visibility
     * threshold to know when they have settled finely enough to stop.
     */
    fun dpSpatial(): FiniteAnimationSpec<Dp> =
        spring(dampingRatio = 0.92f, stiffness = 900f, visibilityThreshold = Dp.VisibilityThreshold)

    /** [dpSpatial]'s snap counterpart, for a [Dp] landing on a discrete stop. */
    fun dpSnap(): FiniteAnimationSpec<Dp> =
        spring(dampingRatio = 0.55f, stiffness = 900f, visibilityThreshold = Dp.VisibilityThreshold)

    /**
     * The one place a spring is wrong: an alert arriving.
     *
     * A spring's settle time is not something the caller controls, and a warning must be fully
     * on screen at a predictable moment. Alert entrances therefore stay on a fixed, deliberately
     * short duration; only alert *exits* get comfortable motion.
     */
    const val DurationAlertIn = 120
    const val DurationAlertOut = 250

    /**
     * One breath of an idle pulse.
     *
     * Long on purpose. An idle animation is on screen continuously, so it is the one loop a rider
     * sees thousands of times - anything quicker stops reading as breathing and starts reading as
     * something demanding attention it does not deserve.
     */
    const val DurationBreath = 2600

    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** Retained for the few callers still on duration-based specs. */
    const val DurationShort = 150
    const val DurationMedium = 300
    const val DurationLong = 450
}

private val Dp.Companion.VisibilityThreshold: Dp
    get() = Dp(0.1f)
