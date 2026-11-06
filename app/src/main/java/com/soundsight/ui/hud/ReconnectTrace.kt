package com.soundsight.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.soundsight.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Live session's reconnect indicator: a progress ring that fills around the outside of the
 * status pill while the cloud connection is being re-established, and closes into a complete ring
 * when it lands.
 *
 * ## Why this is hand-built
 *
 * The look is Material 3 **Expressive**. The API is not available: material3 is pinned at 1.3.0,
 * and 1.4.0's entire Expressive surface (`LoadingIndicator`, `LinearWavyProgressIndicator`,
 * `MotionScheme`) is `internal`. So the shape is drawn directly, the same way [Motion] declares the
 * spring tokens `MotionScheme` would otherwise have provided.
 *
 * ## Determinate, not a spinner
 *
 * The arc grows from the pill's top-left clockwise, and at 100% it is a closed, full-thickness,
 * perfectly flat ring. That last part is Material's, not an invention: the wavy indicator's
 * amplitude ramps in over the first [AmplitudeRampMin] of progress and back out over the last
 * `1 - `[AmplitudeRampMax], so a completed indicator is always smooth. The wave appearing as the
 * ring fills, then flattening as it closes, is the whole gesture - it is the same "straight at
 * first, then curve" shape, expressed in progress rather than in position along the stroke.
 *
 * A reconnect has no measurable percentage, so the fill is the honest kind of estimate a page-load
 * bar makes: it eases up to [CreepCeiling] and waits there, and the socket actually coming back is
 * what drives it the rest of the way. Nothing here claims to know how far along the handshake is;
 * what it reports truthfully is *started* and *finished*.
 *
 * ## Motion
 *
 * Deliberately curved rather than linear. The climb runs on [Motion.EmphasizedDecelerate] - quick
 * off the mark, then slowing as it approaches the ceiling, which is what a load that is waiting on
 * something else actually feels like. The finish runs on [Motion.Emphasized] and is short. A linear
 * creep at constant speed reads as a machine counting, not as something working.
 */
@Immutable
internal class ReconnectTrace(
    /** 0 when nothing should be drawn. Animated, so both edges are a fade rather than a blink. */
    val alpha: State<Float>,
    /** How much of the perimeter is filled, 0f..1f. */
    val progress: State<Float>,
    /** Slow drift of the wave along the perimeter, in wavelengths. */
    val phase: State<Float>
)

/**
 * Minimum time the indicator stays on screen once it has appeared.
 *
 * A reconnect that resolves in 150ms would otherwise flash the whole thing for four frames, which
 * reads as a rendering glitch rather than as information.
 */
private const val MinVisibleMs = 800L

/**
 * Extra time held after the connection lands, so the ring is actually seen closing and full.
 *
 * Without it the completion is invisible: `held` would drop the instant the socket came back, and
 * the fade-out would run over the top of the fill animation. The moment the ring completes is the
 * one frame in this whole animation that carries real information, so it gets its own beat.
 */
private const val CompletionHoldMs = 520L

/** Where the fill eases up to and waits for the socket. */
private const val CreepCeiling = 0.88f
private const val CreepDurationMs = 2400
private const val FinishDurationMs = 320

/** One wavelength of drift, so the wave crawls instead of sitting still while the fill waits. */
private const val WaveDriftMs = 1800

/**
 * Geometry, taken from Material's own `m3_comp_progress_indicator_*` tokens rather than measured
 * off a picture.
 *
 * A pill perimeter is a closed loop, so the **circular** wavy indicator is the right reference, not
 * the linear one: `circular_active_indicator_wave_amplitude = 1.6dp`,
 * `circular_active_indicator_wave_wavelength = 15dp`, `circular_track_thickness = 4dp`,
 * `indicatorTrackGapSize = 4dp`, and a fully rounded (50%) track corner.
 *
 * The track and the active indicator are the **same thickness**. Material separates them by tone
 * alone - `trackColor` against `indicatorColor` - and the active one only looks heavier because
 * the wave makes it sweep a band 2x[WaveAmplitude] taller than the track. Drawing the track thinner
 * to fake that difference is what made an earlier version read as a border with a bright patch
 * stuck on it.
 */
private val TraceStroke = 4.dp
private val WaveLength = 15.dp
private val WaveAmplitude = 1.6.dp
private val TrackGap = 4.dp
private const val TrackAlpha = 0.24f

/** Material's `waveAmplitudeRampProgressMin` / `Max`. The wave lives only between these. */
private const val AmplitudeRampMin = 0.1f
private const val AmplitudeRampMax = 0.9f

/** Distance the ring sits outside the pill it traces. */
private val RingOffset = 3.dp

/** Samples per wavelength. Enough that the wave reads as a curve, not a polygon. */
private const val SamplesPerWave = 18

private const val TwoPi = (2.0 * PI).toFloat()

/**
 * Latches [active] on for at least [MinVisibleMs], then holds [CompletionHoldMs] longer so the ring
 * can finish filling.
 *
 * Separate from the drawing, and read one level up in [com.soundsight.ui.hud.HudScreen], because
 * the status chip's *label* and *colour* change for the same window the ring runs. If the two were
 * driven by different state the chip would flip back to "Listening" while the ring was still
 * closing, which reads as the indicator lying about what it is doing.
 */
@Composable
internal fun rememberReconnectHold(active: Boolean): Boolean {
    var held by remember { mutableStateOf(false) }
    var shownAtMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        if (active) {
            // Only stamp the clock on the first activation of an episode, so a CONNECTING ->
            // RECONNECTING transition does not extend the hold.
            if (!held) shownAtMs = System.currentTimeMillis()
            held = true
        } else if (held) {
            val elapsed = System.currentTimeMillis() - shownAtMs
            if (elapsed < MinVisibleMs) delay(MinVisibleMs - elapsed)
            delay(CompletionHoldMs)
            held = false
        }
    }
    return held
}

/**
 * Animation state for the ring.
 *
 * [visible] is a [rememberReconnectHold] result - already latched, and still true for a moment
 * after [active] goes false. [active] is the raw "still connecting" flag: the gap between the two
 * is exactly the window in which the ring drives to 100%.
 */
@Composable
internal fun rememberReconnectTrace(visible: Boolean, active: Boolean): ReconnectTrace {
    val alpha = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.defaultEffects(),
        label = "reconnect_trace_alpha"
    )

    val progress = remember { Animatable(0f) }

    // Rising edge of an episode: start empty. Done in its own effect so it cannot be skipped by the
    // driver below restarting, and it runs while alpha is still 0, so the reset is never seen.
    LaunchedEffect(visible) {
        if (visible) progress.snapTo(0f)
    }

    LaunchedEffect(visible, active) {
        if (!visible) return@LaunchedEffect
        if (active) {
            progress.animateTo(
                targetValue = CreepCeiling,
                animationSpec = tween(CreepDurationMs, easing = Motion.EmphasizedDecelerate)
            )
        } else {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(FinishDurationMs, easing = Motion.Emphasized)
            )
        }
    }

    // Unconditional, including while the ring is invisible: PulseWave already runs an infinite
    // transition for the entire time this screen is on top, so the frame clock is never idle here
    // anyway and a second one costs nothing. Conditioning it on visibility would only add a
    // structural recomposition at the exact moment the animation needs to start smoothly.
    val transition = rememberInfiniteTransition(label = "reconnect_trace")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WaveDriftMs, easing = LinearEasing)
        ),
        label = "reconnect_trace_phase"
    )

    return ReconnectTrace(alpha = alpha, progress = progress.asState(), phase = phase)
}

/**
 * Draws the ring around the *outside* of the current draw bounds, which are the status pill's.
 *
 * Deliberately outside: the stroke has to be legible against the pill's own tinted fill, and
 * insetting it would have put the indicator on top of a coloured chip. The furthest the wave can
 * reach is [RingOffset] + [WaveAmplitude] + half of [TraceStroke] = 6.6dp, which is why the gap
 * between the chip and the history button next to it is 10dp rather than the 6dp it was. Compose
 * does not clip a draw modifier to its bounds, so nothing here needs the chip to reserve the space.
 *
 * Track and active indicator are one colour at two brightnesses - the chip's own - rather than two
 * colours. Give the active stroke a hue of its own and the chip stops being one object: what the
 * rider sees is a pill with something unrelated crawling on it, not a pill that is doing something.
 *
 * No-ops on a shape too small to be a pill, and on a fully transparent ring.
 */
internal fun DrawScope.drawReconnectTrace(
    progress: Float,
    phase: Float,
    color: Color,
    alpha: Float
) {
    if (alpha <= 0.001f) return

    val offset = RingOffset.toPx()
    val left = -offset
    val top = -offset
    val width = size.width + 2f * offset
    val height = size.height + 2f * offset
    val r = height / 2f
    val straight = width - 2f * r
    if (r <= 0f || straight <= 0f) return

    val capLength = PI.toFloat() * r
    val perimeter = 2f * straight + 2f * capLength
    val bottom = top + height
    val capY = top + r
    val leftCapX = left + r
    val rightCapX = left + r + straight

    /**
     * Point on the pill's perimeter at arc length [s], walking clockwise from the left end of the
     * top edge, plus its outward normal. Written into [out] as x, y, nx, ny - one array for the
     * whole draw pass rather than an object per sample.
     */
    fun sample(s: Float, out: FloatArray) {
        val t = ((s % perimeter) + perimeter) % perimeter
        when {
            t < straight -> {
                out[0] = leftCapX + t; out[1] = top; out[2] = 0f; out[3] = -1f
            }
            t < straight + capLength -> {
                val theta = -PI.toFloat() / 2f + (t - straight) / r
                out[2] = cos(theta); out[3] = sin(theta)
                out[0] = rightCapX + r * out[2]; out[1] = capY + r * out[3]
            }
            t < 2f * straight + capLength -> {
                out[0] = rightCapX - (t - straight - capLength); out[1] = bottom
                out[2] = 0f; out[3] = 1f
            }
            else -> {
                val theta = PI.toFloat() / 2f + (t - 2f * straight - capLength) / r
                out[2] = cos(theta); out[3] = sin(theta)
                out[0] = leftCapX + r * out[2]; out[1] = capY + r * out[3]
            }
        }
    }

    val strokePx = TraceStroke.toPx()
    val gap = TrackGap.toPx()
    val filled = progress.coerceIn(0f, 1f) * perimeter

    // A whole number of waves around the loop, so the crest at s = 0 is the same crest the arc
    // arrives back at when it closes. Without this the ring meets itself mid-cycle and the join
    // shows as a kink at the one moment the shape is meant to look finished.
    val waves = (perimeter / WaveLength.toPx()).roundToInt().coerceAtLeast(1)
    val waveLengthPx = perimeter / waves

    // Material's amplitude ramp: in over the first tenth of progress, out over the last tenth, so
    // a completed ring is always perfectly smooth. smoothStep rather than a fractional power -
    // this project has lost a whole Path to `sin(t * PI).pow(n)` returning NaN at t = 1, where sin
    // is a hair below zero and a negative base to a fractional power is not a number. There is no
    // pow here at all, and nothing below can go negative.
    val rampIn = smoothStep(progress / AmplitudeRampMin)
    val rampOut = 1f - smoothStep((progress - AmplitudeRampMax) / (1f - AmplitudeRampMax))
    val amplitudePx = WaveAmplitude.toPx() * rampIn * rampOut

    val out = FloatArray(4)

    // The remaining track, inset by a gap at each end. Skipped once the ring is nearly closed:
    // there is no "remaining" left, and a stub of track squeezed into the last few pixels reads as
    // a defect rather than as a gap.
    val trackLength = perimeter - filled - 2f * gap
    if (trackLength > gap) {
        val track = Path()
        val steps = (trackLength / waveLengthPx * SamplesPerWave).toInt().coerceIn(24, 240)
        for (i in 0..steps) {
            sample(filled + gap + trackLength * i / steps, out)
            if (i == 0) track.moveTo(out[0], out[1]) else track.lineTo(out[0], out[1])
        }
        drawPath(
            path = track,
            color = color,
            alpha = alpha * TrackAlpha,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    if (filled <= 0.5f) return

    // The active indicator: one Path stroked once, so it is a single solid rope of uniform weight.
    // Drawn as separate round-capped segments it would bead visibly at every joint the moment
    // [alpha] is anything but 1 during the fade.
    val steps = (filled / waveLengthPx * SamplesPerWave).toInt().coerceIn(16, 320)
    val active = Path()
    for (i in 0..steps) {
        val s = filled * i / steps
        sample(s, out)
        // Anchored to arc length, not to position along the arc, so the wave stays put as the ring
        // grows through it instead of stretching. [phase] slides the whole pattern slowly round.
        val wave = sin((s / waveLengthPx + phase) * TwoPi) * amplitudePx
        val x = out[0] + out[2] * wave
        val y = out[1] + out[3] * wave
        if (i == 0) active.moveTo(x, y) else active.lineTo(x, y)
    }

    drawPath(
        path = active,
        color = color,
        alpha = alpha,
        style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** Hermite smoothstep on 0..1, clamped. */
private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
