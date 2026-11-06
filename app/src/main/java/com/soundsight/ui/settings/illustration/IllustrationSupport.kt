package com.soundsight.ui.settings.illustration

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Shared plumbing for the two settings illustrations.
 *
 * Both are ports of Claude Design compositions authored as absolutely-positioned DOM on a fixed
 * 1200x900 stage, driven by an `animate({from, to, start, end, ease})` helper over a looping clock.
 * Everything here keeps that stage's coordinates and that timing vocabulary rather than
 * re-deriving the motion in Compose idiom, so a tweak made in the design file can be transcribed
 * across line for line instead of reverse-engineered.
 */

internal const val PI_F = Math.PI.toFloat()

internal object Ease {
    val Linear: (Float) -> Float = { it }
    val OutCubic: (Float) -> Float = { 1f - (1f - it).pow(3) }
    val InQuad: (Float) -> Float = { it * it }
    val OutSine: (Float) -> Float = { sin(it * (PI_F / 2f)) }
    val InOutCubic: (Float) -> Float = { k ->
        if (k < 0.5f) 4f * k * k * k else 1f - (-2f * k + 2f).pow(3) / 2f
    }

    /** Overshoots past 1 before settling, which is what gives the warning mark its snap. */
    val OutBack: (Float) -> Float = { k ->
        val c1 = 1.70158f
        val c3 = c1 + 1f
        1f + c3 * (k - 1f).pow(3) + c1 * (k - 1f).pow(2)
    }
}

internal fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

internal fun lerp(a: Float, b: Float, k: Float): Float = a + (b - a) * k

/**
 * The design file's `animate({from, to, start, end, ease})(T)`: a value that holds at [from] until
 * [start], eases across to [to] by [end], and holds there after.
 */
internal fun anim(
    t: Float,
    from: Float,
    to: Float,
    start: Float,
    end: Float,
    ease: (Float) -> Float = Ease.Linear
): Float = when {
    t <= start -> from
    t >= end -> to
    else -> from + (to - from) * ease((t - start) / (end - start))
}

/** Positive modulo, matching the JS `((x % p) + p) % p` idiom the arcs use. */
internal fun wrap(x: Float, period: Float): Float = ((x % period) + period) % period

/**
 * A free-running clock in seconds that restarts at [durationSeconds].
 *
 * Linear on purpose: every ease in these compositions is applied per-property against this clock,
 * exactly as the design file does it. Easing the clock itself would compound with those and drift
 * the scene cues off their authored times.
 */
@Composable
internal fun rememberLoopClock(durationSeconds: Float): State<Float> {
    val transition = rememberInfiniteTransition(label = "illustration_loop")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = durationSeconds,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (durationSeconds * 1000f).toInt(),
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "illustration_time"
    )
}

/**
 * Whether the surrounding surface is dark.
 *
 * Read off the resolved scheme rather than [androidx.compose.foundation.isSystemInDarkTheme],
 * because this app lets the rider force LIGHT or DARK independently of the system, and the
 * illustration has to match the card it is sitting in - not the phone.
 */
@Composable
internal fun illustrationIsDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Maps a rectangle of the 1200x900 design stage onto the whole canvas, cropping whichever axis is
 * over-long. The stage is 4:3 and a settings illustration card is much wider than that, so
 * something has to give; cropping keeps the drawing at a readable size where letterboxing would
 * shrink it into a stripe.
 */
internal inline fun DrawScope.stage(
    srcX: Float,
    srcY: Float,
    srcW: Float,
    srcH: Float,
    block: DrawScope.() -> Unit
) {
    val k = max(size.width / srcW, size.height / srcH)
    val dx = (size.width - srcW * k) / 2f - srcX * k
    val dy = (size.height - srcH * k) / 2f - srcY * k
    withTransform({
        translate(dx, dy)
        scale(k, k, Offset.Zero)
    }) {
        block()
    }
}

/** Rounded rect placed by its centre and rotated about it - the design file's `Bar` primitive. */
internal fun DrawScope.bar(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    color: Color,
    radius: Float = h / 2f,
    rotation: Float = 0f,
    alpha: Float = 1f
) {
    if (alpha <= 0.004f || w <= 0f || h <= 0f) return
    withTransform({ rotate(rotation, Offset(cx, cy)) }) {
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - w / 2f, cy - h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(radius, radius),
            alpha = clamp01(alpha)
        )
    }
}

/** Rounded rect placed by its top-left corner - a plain absolutely-positioned div. */
internal fun DrawScope.slab(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    color: Color,
    radius: Float = 0f,
    alpha: Float = 1f,
    rotation: Float = 0f,
    pivot: Offset = Offset(x + w / 2f, y + h / 2f)
) {
    if (alpha <= 0.004f || w <= 0f || h <= 0f) return
    withTransform({ rotate(rotation, pivot) }) {
        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = CornerRadius(radius, radius),
            alpha = clamp01(alpha)
        )
    }
}

/** Rounded rect with per-corner radii, for the shapes CSS spelled as `border-radius: a b c d`. */
internal fun DrawScope.slabCorners(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    color: Color,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
    alpha: Float = 1f
) {
    if (alpha <= 0.004f || w <= 0f || h <= 0f) return
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = x,
                top = y,
                right = x + w,
                bottom = y + h,
                topLeftCornerRadius = CornerRadius(topLeft, topLeft),
                topRightCornerRadius = CornerRadius(topRight, topRight),
                bottomRightCornerRadius = CornerRadius(bottomRight, bottomRight),
                bottomLeftCornerRadius = CornerRadius(bottomLeft, bottomLeft)
            )
        )
    }
    drawPath(path, color, alpha = clamp01(alpha))
}

internal fun DrawScope.disc(cx: Float, cy: Float, r: Float, color: Color, alpha: Float = 1f) {
    if (alpha <= 0.004f || r <= 0f) return
    drawCircle(color = color, radius = r, center = Offset(cx, cy), alpha = clamp01(alpha))
}

/**
 * A CSS `border: Npx solid` on a circle, which with `box-sizing: border-box` sits *inside* the
 * given diameter - so the stroke centreline is half a border-width in from the edge.
 */
internal fun DrawScope.ringD(
    cx: Float,
    cy: Float,
    diameter: Float,
    border: Float,
    color: Color,
    alpha: Float = 1f
) {
    if (alpha <= 0.004f || diameter <= border) return
    drawCircle(
        color = color,
        radius = (diameter - border) / 2f,
        center = Offset(cx, cy),
        alpha = clamp01(alpha),
        style = Stroke(width = border)
    )
}
