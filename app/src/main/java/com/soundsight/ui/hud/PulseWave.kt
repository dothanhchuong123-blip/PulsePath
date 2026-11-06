package com.soundsight.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.ui.theme.Motion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The two visual treatments of the wave. Both share the identical morph behaviour, colour rules
 * and level source - only the mark differs - so switching is purely a look preference and cannot
 * change what the rider is told.
 */
enum class WaveStyle {
    /** Continuous stroked curve. Fluid and quiet. */
    LINE,

    /**
     * A row of shapes that change shape, not just size, with what the microphone hears.
     *
     * Replaced an earlier halftone dot matrix. The dots were legible but inert: a dot can only get
     * brighter or dimmer, so the entire display had one channel and it was the same channel the
     * line style already used better. Material 3 treats shape as a carrier of state in its own
     * right, and that is what this uses - quiet reads as a row of soft circles, loud as tall
     * squared-off bars, and the corner radius travels continuously between the two. A rider sees
     * the difference in silhouette before resolving any single element.
     */
    SHAPES
}

/**
 * The HUD's centrepiece: a flowing waveform that bends into a halo around the hazard glyph.
 *
 * The morph is the idea made literal: every mark is sampled at the same points in both layouts, so
 * the wave does not cross-fade into a circle, it physically bends into one. The thing that was
 * listening becomes the thing that is warning you.
 *
 * Motion is asymmetric on purpose - fast in, slow out. An alert arriving is not a moment for a
 * graceful transition; an alert leaving is.
 */
@Composable
fun PulseWave(
    level: StateFlow<Float>,
    active: Boolean,
    accent: Color,
    alertIcon: ImageVector?,
    alertColor: Color,
    modifier: Modifier = Modifier,
    style: WaveStyle = WaveStyle.LINE,
    columns: Int = 14,
    iconSize: Dp = 72.dp
) {
    PulseWaveImpl(
        level = level,
        active = active,
        accent = accent,
        alertIcon = alertIcon,
        alertColor = alertColor,
        modifier = modifier,
        style = style,
        columns = columns,
        iconSize = iconSize,
        testObserver = null
    )
}

/** Test-only entry point that observes invalidation without adding hooks to the public API. */
@Composable
internal fun PulseWaveForTest(
    level: StateFlow<Float>,
    active: Boolean,
    accent: Color,
    alertIcon: ImageVector?,
    alertColor: Color,
    modifier: Modifier = Modifier,
    style: WaveStyle = WaveStyle.LINE,
    columns: Int = 14,
    iconSize: Dp = 72.dp,
    testObserver: PulseWaveTestObserver
) {
    PulseWaveImpl(
        level = level,
        active = active,
        accent = accent,
        alertIcon = alertIcon,
        alertColor = alertColor,
        modifier = modifier,
        style = style,
        columns = columns,
        iconSize = iconSize,
        testObserver = testObserver
    )
}

internal data class PulseWaveTestObserver(
    val onComposition: () -> Unit = {},
    val onAnimatedDrawLevel: (level: Float) -> Unit = {},
    val onLevelTarget: (level: Float) -> Unit = {},
    val onInfiniteMotion: (running: Boolean) -> Unit = {}
)

@Composable
private fun PulseWaveImpl(
    level: StateFlow<Float>,
    active: Boolean,
    accent: Color,
    alertIcon: ImageVector?,
    alertColor: Color,
    modifier: Modifier,
    style: WaveStyle,
    columns: Int,
    iconSize: Dp,
    testObserver: PulseWaveTestObserver?
) {
    // Keep lifecycle-aware collection, but do not read this State during composition. snapshotFlow
    // observes it from the launched effect, and Animatable is read only from Canvas below, so a
    // microphone update invalidates drawing without recomposing the PulseWave host.
    val rawLevel = level.collectAsStateWithLifecycle(initialValue = 0f)
    val smoothLevel = remember { Animatable(0f) }
    val alertActive = alertIcon != null

    LaunchedEffect(rawLevel, active, alertActive) {
        snapshotFlow {
            if (active && !alertActive) rawLevel.value.coerceIn(0f, 1f) else 0f
        }.collectLatest { target ->
            testObserver?.onLevelTarget?.invoke(target)
            smoothLevel.animateTo(target, animationSpec = Motion.fastSpatial())
        }
    }
    if (testObserver != null) {
        // JVM Compose tests do not rasterize Canvas reliably. Observe the exact same State that
        // Canvas reads so the regression test can still prove that draw state advances while the
        // composition count remains fixed. This branch does not exist in production composition.
        LaunchedEffect(smoothLevel, testObserver) {
            snapshotFlow { smoothLevel.value }.collectLatest(testObserver.onAnimatedDrawLevel)
        }
    }

    // 0 = open waveform, 1 = closed halo around the glyph.
    val morph by animateFloatAsState(
        targetValue = if (alertIcon != null) 1f else 0f,
        // Spatial spring, not a tween: the morph is a shape moving, and if a second alert lands
        // while the first is still unwinding the spring carries that velocity through instead of
        // restarting the arc.
        animationSpec = if (alertIcon != null) Motion.defaultSpatial() else Motion.slowSpatial(),
        label = "wave_morph"
    )

    // Removing this composable branch disposes the infinite transition while an alert is shown.
    // The alert morph itself remains finite; once it settles, the warning is genuinely still.
    val waveMotion = rememberWaveMotion(enabled = !alertActive)

    SideEffect {
        testObserver?.onComposition?.invoke()
        testObserver?.onInfiniteMotion?.invoke(waveMotion != null)
    }

    // The halo and glyph are drawn in a *brightened* alert colour, not the alert colour itself:
    // HudContent washes the whole screen in that same hue when alerting, so drawing the curve in
    // it put red on red and made the halo disappear entirely.
    val alertInk = lerpColor(alertColor, Color.White, 0.45f)
    val drawColor = lerpColor(accent, alertInk, morph)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("pulse_wave")
        ) {
            val phase = waveMotion?.phase?.value ?: 0f
            val idle = waveMotion?.idle?.value ?: 0.10f
            val levelForDraw = if (active && !alertActive) smoothLevel.value else 0f

            val cx = size.width / 2f
            val cy = size.height / 2f
            val ringRadius = min(size.width, size.height) * 0.42f
            val maxHalf = (size.height / 2f) * 0.86f
            val amplitude = if (active) idle + levelForDraw * 0.80f else idle * 0.45f

            // Tonal disc behind the glyph, the same device the takeover uses. It is what makes
            // the alerting state read as a deliberate composition - a mark on a ground - rather
            // than as a thin outline floating on a tinted screen. Drawn before the wave so the
            // ring sits on top of its own edge.
            if (morph > 0.01f) {
                drawCircle(
                    color = drawColor.copy(alpha = drawColor.alpha * 0.14f * morph),
                    radius = ringRadius * (0.94f * morph),
                    center = Offset(cx, cy)
                )
            }

            if (style == WaveStyle.SHAPES) {
                drawShapeWave(
                    cx = cx,
                    cy = cy,
                    color = drawColor,
                    morph = morph,
                    phase = phase,
                    amplitude = amplitude,
                    columns = columns
                )
                return@Canvas
            }

            // One curve, plus a single faint echo behind it. An earlier version layered three
            // curves at different frequencies; it looked intricate standing still and became
            // visual noise in motion, which is the opposite of what a glanceable safety display
            // needs. One line reads instantly at arm's length.
            // Material You treats an indicator as an active stroke on a visible tonal track.
            // The dim layer is that track: same geometry, low opacity, slightly wider, so the
            // active stroke reads as travelling along something rather than floating alone.
            val layers = listOf(
                LayerSpec(freq = 1.8f, ampScale = 1.00f, phaseOff = 0.9f, alpha = 0.22f, width = 8f),
                LayerSpec(freq = 1.8f, ampScale = 1.00f, phaseOff = 0f, alpha = 1.00f, width = 5f)
            )

            for (layer in layers) {
                val path = buildMorphPath(
                    width = size.width,
                    cx = cx,
                    cy = cy,
                    ringRadius = ringRadius,
                    maxHalf = maxHalf,
                    amplitude = amplitude * layer.ampScale,
                    freq = layer.freq,
                    // The track's phase offset is what makes it read as a separate layer trailing
                    // the stroke - but only while the wave is open. Carried into the halo it
                    // rotated the track's lobes away from the stroke's and the ring rendered as a
                    // botched double exposure. Fading the offset out with the morph means the two
                    // arrive concentric, differing only in width, which is the Material You
                    // active-stroke-on-a-track relationship the layer was there to create.
                    phase = phase + layer.phaseOff * (1f - morph),
                    morph = morph
                )

                drawPath(
                    path = path,
                    color = drawColor.copy(alpha = layer.alpha),
                    style = Stroke(
                        width = layer.width.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        if (alertIcon != null) {
            Icon(
                imageVector = alertIcon,
                contentDescription = null,
                tint = alertInk,
                modifier = Modifier
                    .size(iconSize)
                    .scale(0.55f + 0.45f * morph)
                    .alpha(morph)
                    .testTag("pulse_wave_glyph")
            )
        }
    }
}

private data class WaveMotion(
    val phase: State<Float>,
    val idle: State<Float>
)

@Composable
private fun rememberWaveMotion(enabled: Boolean): WaveMotion? {
    if (!enabled) return null

    val transition = rememberInfiniteTransition(label = "wave")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing)
        ),
        label = "wave_phase"
    )

    // Idle breath, so a silent room still looks alive without faking a level reading. Shallow and
    // slow: this loop is on screen for the entire ride, and it is the first thing to start reading
    // as fidgeting if it asks for any attention at all.
    val idle = transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.17f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.DurationBreath, easing = Motion.Standard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave_idle"
    )
    return remember(phase, idle) { WaveMotion(phase = phase, idle = idle) }
}

private data class LayerSpec(
    val freq: Float,
    val ampScale: Float,
    val phaseOff: Float,
    val alpha: Float,
    val width: Float
)

/**
 * Samples one curve in both layouts and interpolates between them.
 *
 * Both layouts are parameterised by the same `t` in 0..1, which is what makes the transition read
 * as bending rather than as a swap: sample k of the line always becomes sample k of the halo.
 */
private fun DrawScope.buildMorphPath(
    width: Float,
    cx: Float,
    cy: Float,
    ringRadius: Float,
    maxHalf: Float,
    amplitude: Float,
    freq: Float,
    phase: Float,
    morph: Float
): Path {
    val path = Path()
    val samples = 128

    for (i in 0..samples) {
        val t = i.toFloat() / samples

        // Taper towards the ends so the open waveform has a shape instead of stopping dead. It
        // has to fade out as the curve closes: a ring has no ends to taper, and leaving it in
        // made one side of the halo flat.
        // coerceAtLeast(0f) is load-bearing, not defensive: at t = 1.0 float sin(PI) evaluates to
        // about -8.7e-8, and a negative base to a fractional power is NaN. A single NaN point
        // invalidates the whole Path, so without this the wave silently renders nothing at all.
        val waveEnvelope = sin(t * PI.toFloat()).coerceAtLeast(0f).pow(1.5f)
        val envelope = waveEnvelope + (1f - waveEnvelope) * morph

        // Layout A: open curve across the full width.
        val wave = sin(t * freq * 2f * PI.toFloat() + phase)
        val ax = t * width
        val ay = cy + amplitude * envelope * wave * maxHalf

        // Layout B: closed halo. A true circle that breathes.
        //
        // Earlier versions modulated the radius as a function of t - a standing wave wrapped
        // around the ring. Every version of that looked wrong for the same reason: a circle whose
        // radius varies with angle is not a rippling circle, it is a lumpy one, and a lumpy circle
        // reads as a drawing mistake next to the perfectly round disc the takeover screen uses.
        // Fewer, shallower lobes only made it a subtler mistake.
        //
        // So the modulation is angle-independent: the whole ring scales together with the audio.
        // The silhouette is exactly circular at every instant, and the size still moves with what
        // the microphone hears. A shape that is round on purpose beats a shape that is nearly
        // round by accident.
        //
        // t still parameterises position around the ring, which is what preserves the morph: the
        // sample that was at x along the open wave lands at the corresponding angle on the halo.
        val angle = t * 2f * PI.toFloat() - (PI.toFloat() / 2f)
        val radius = ringRadius * (1f + amplitude * sin(phase) * 0.07f)
        val bx = cx + cos(angle) * radius
        val by = cy + sin(angle) * radius

        val p = Offset(
            x = ax + (bx - ax) * morph,
            y = ay + (by - ay) * morph
        )

        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }

    return path
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t
    )

/**
 * Shape-wave renderer.
 *
 * Each column is one rounded rectangle whose **height and corner radius both** track its share of
 * the signal. That pairing is the whole point:
 *
 *  - at rest, height equals width and the radius is half the width, so the shape is a circle;
 *  - as level rises the shape grows taller *and* its corners tighten, ending as a hard-edged bar.
 *
 * Because the two move together, loudness is legible from the silhouette alone - the row visibly
 * changes character, not just scale. A pure bar meter changes only in one dimension and needs a
 * baseline to be read against; this does not.
 *
 * Every column keeps a fixed slot and is always drawn, never skipped. That is what makes the alert
 * morph work: slot c travels to its own place on the halo and keeps its identity, so nothing is
 * created or destroyed on the way. It also means the display has a stable extent instead of
 * appearing to grow and shrink with the room's noise floor.
 */
private fun DrawScope.drawShapeWave(
    cx: Float,
    cy: Float,
    color: Color,
    morph: Float,
    phase: Float,
    amplitude: Float,
    columns: Int
) {
    if (columns <= 1) return

    val colSpacing = size.width / (columns + 1f)
    val shapeWidth = (colSpacing * 0.64f).coerceAtMost(size.height * 0.18f)
    val maxHeight = size.height * 0.82f

    val ringRadius = min(size.width, size.height) * 0.42f

    for (c in 0 until columns) {
        val t = c.toFloat() / (columns - 1).toFloat()

        // Taper towards the ends, fading out as the row closes into a ring - a ring has no ends.
        // coerceAtLeast(0f) guards the same float-precision NaN as buildMorphPath: sin(PI) is
        // very slightly negative, and a negative base to a fractional power is NaN.
        val waveEnvelope = sin(t * PI.toFloat()).coerceAtLeast(0f).pow(0.8f)
        val envelope = waveEnvelope + (1f - waveEnvelope) * morph

        // abs() because the row is symmetric about the centre line: it shows magnitude, not signed
        // displacement. Two beating terms at unrelated frequencies give the uneven, audio-like
        // column heights without any randomness, which would flicker frame to frame.
        val carrier = abs(sin(t * 4.5f * PI.toFloat() + phase))
        val beat = 0.55f + 0.45f * abs(sin(t * 7f * PI.toFloat() - phase * 0.6f))
        val energy = (amplitude * envelope * (0.40f + 0.60f * carrier) * beat).coerceIn(0f, 1f)

        // Height runs from a circle (height == width) up to the full column height.
        val openHeight = shapeWidth + energy * (maxHeight - shapeWidth)

        // Corner radius: half the width is a perfect circle/pill; it tightens towards a squircle
        // as the column fills. Never reaches a true square - Material 3's shape scale has no
        // right angles in it, and a sharp corner here would read as a rendering fault.
        val openRadius = shapeWidth * (0.5f - 0.26f * energy)

        // Halo layout: every shape converges on the same small bead, evenly spaced around the
        // ring. Beads rather than bars because a rounded rect would have to rotate to sit
        // correctly on a circle, and a row of shapes all rotating at once reads as a spin rather
        // than as the wave closing.
        val beadSize = shapeWidth * 0.88f
        val angle = t * 2f * PI.toFloat() - (PI.toFloat() / 2f)
        val ringX = cx + cos(angle) * ringRadius
        val ringY = cy + sin(angle) * ringRadius

        val centerX = (colSpacing * (c + 1f)) + (ringX - (colSpacing * (c + 1f))) * morph
        val centerY = cy + (ringY - cy) * morph

        val w = shapeWidth + (beadSize - shapeWidth) * morph
        val h = openHeight + (beadSize - openHeight) * morph
        val r = openRadius + (beadSize * 0.5f - openRadius) * morph

        // A floor on alpha keeps the quiet end of the row visible as a track rather than letting
        // it disappear, so the display's extent stays constant.
        val alpha = (0.24f + 0.76f * energy).coerceIn(0f, 1f)

        drawRoundRect(
            color = color.copy(alpha = color.alpha * alpha),
            topLeft = Offset(centerX - w / 2f, centerY - h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(r, r)
        )
    }
}
