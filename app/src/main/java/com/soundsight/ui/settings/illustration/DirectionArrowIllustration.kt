package com.soundsight.ui.settings.illustration

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import com.soundsight.ui.theme.LocalHazardColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Looping spot illustration for the Direction Arrow setting: rider's view over the handlebar, with
 * the mounted phone showing which side a sound came from. A moderate sound arrives from the left
 * (amber, motorbike), then a much louder one from the right (red, truck), then it settles back to
 * listening.
 *
 * Ported from the "Android Animation Vehicle Direction" Claude Design project
 * (`Vehicle Alert Animation.dc.html` / `vehicle-alert-scene.jsx`), keeping its 1200x900 stage
 * coordinates and scene cues - see [IllustrationSupport].
 *
 * Loudness is what separates the two events, and it reads through four channels at once: arc
 * count, arc size, travel speed and how hard the phone shakes. That is the design file's own
 * choice and it is the point of the illustration - the arrow is only half the information, the
 * other half is how urgent the thing behind it is.
 */

private const val W = 1200f
private const val H = 900f
private const val HORIZON = 210f

// Scene cues in seconds, accumulated from OM_SCENES: Idle 0.7, Bike 1.1, Truck 1.3, Settle 0.7.
private const val CUE_BIKE = 0.7f
private const val CUE_TRUCK = 1.8f
private const val CUE_SETTLE = 3.1f
internal const val DIRECTION_LOOP_SECONDS = 3.8f

private const val PHONE_CX = 600f
private const val PHONE_CY = 440f
private const val SCREEN_X = 355f
private const val SCREEN_Y = 314f
private const val SCREEN_W = 490f
private const val SCREEN_H = 252f

private class RoadPalette(
    val sky: Color,
    val road: Color,
    val dash: Color,
    val verge: Color,
    val bar: Color,
    val barDeep: Color,
    val grip: Color,
    val phone: Color,
    val bezel: Color,
    val screenIdle: Color,
    val shadow: Color,
    val arc: Color,
    val amber: Color,
    val amberInk: Color,
    val red: Color,
    val redInk: Color,
    val listen: Color
)

@Composable
fun DirectionArrowIllustration(modifier: Modifier = Modifier) {
    val dark = illustrationIsDark()
    val hazard = LocalHazardColors.current
    val scheme = MaterialTheme.colorScheme

    val palette = remember(dark, hazard, scheme) {
        val common = { sky: Color,
                       road: Color,
                       dash: Color,
                       verge: Color,
                       bar: Color,
                       barDeep: Color,
                       grip: Color,
                       phone: Color,
                       bezel: Color,
                       screenIdle: Color,
                       shadow: Color ->
            RoadPalette(
                sky = sky,
                road = road,
                dash = dash,
                verge = verge,
                bar = bar,
                barDeep = barDeep,
                grip = grip,
                phone = phone,
                bezel = bezel,
                screenIdle = screenIdle,
                shadow = shadow,
                arc = scheme.onSurface,
                amber = hazard.Warning,
                amberInk = hazard.OnWarning,
                red = hazard.Critical,
                redInk = hazard.OnCritical,
                // The idle waveform follows the Material You accent, same as the HUD's own
                // listening state does - the two are drawings of the same screen.
                listen = scheme.primary
            )
        }
        if (dark) {
            common(
                scheme.surfaceContainerHigh,
                Color(0xFF2B2930),
                Color(0xFF5E5769),
                Color(0xFF1E1C23),
                Color(0xFF6F6779),
                Color(0xFF544D5C),
                Color(0xFF3A3540),
                Color(0xFFE6E0E9),
                Color(0xFFCFC8D4),
                Color(0xFF26242B),
                Color(0x80000000)
            )
        } else {
            common(
                scheme.surfaceContainerHigh,
                Color(0xFFD2CBDC),
                Color(0xFFFCFAFF),
                Color(0xFFE7E2EE),
                Color(0xFFA39DAF),
                Color(0xFF857E92),
                Color(0xFF5B5568),
                Color(0xFFFFFFFF),
                Color(0xFFE4DFEA),
                Color(0xFFEBE7F1),
                Color(0x3A28233C)
            )
        }
    }

    val t by rememberLoopClock(DIRECTION_LOOP_SECONDS)

    Canvas(modifier = modifier.testTag("illustration_direction_arrow")) {
        drawDirectionFrame(t, palette)
    }
}

/**
 * A sound's envelope: ramps up around [start], holds, ramps down around [end]. The design file
 * calls this a trapezoid, and using one rather than a plain fade is what keeps the alert on screen
 * long enough to be read at the size a settings card gives it.
 */
private fun trapezoid(t: Float, start: Float, end: Float, edge: Float): Float {
    val rise = anim(t, 0f, 1f, start - edge, start + edge, Ease.InOutCubic)
    val fall = anim(t, 1f, 0f, end - edge, end + edge, Ease.InOutCubic)
    return minOf(rise, fall)
}

private fun DrawScope.drawDirectionFrame(t: Float, c: RoadPalette) {
    val bikeEnv = trapezoid(t, CUE_BIKE, CUE_TRUCK - 0.14f, 0.22f)
    val truckEnv = trapezoid(t, CUE_TRUCK + 0.16f, CUE_SETTLE, 0.22f)
    val alertEnv = maxOf(bikeEnv, truckEnv)
    val loud = bikeEnv * 0.42f + truckEnv * 1f

    val screenColor = lerp(lerp(c.screenIdle, c.amber, bikeEnv), c.red, truckEnv)
    val ink = if (truckEnv > bikeEnv) c.redInk else c.amberInk
    val idleOpacity = clamp01(
        1f - maxOf(alertEnv * 1.5f, trapezoid(t, CUE_BIKE, CUE_SETTLE, 0.22f))
    )

    // The phone trembles in its mount, harder for the louder sound.
    val amp = bikeEnv * 0.7f + truckEnv * 1.3f
    val vibX = amp * sin(t * 44f)
    val vibY = amp * 0.6f * sin(t * 44f * 1.3f + 1.1f)
    val vibRot = amp * 0.1f * sin(t * 44f * 0.8f + 2.2f)

    drawRect(c.sky)

    // Framed from just above the horizon down to just below the handlebar: the sky is empty and
    // so is the bottom of the stem, so the pixels go to the road, the phone and the arcs. Paired
    // with the panel's 200dp height this is an exact aspect match, so nothing is cropped at all.
    stage(srcX = 0f, srcY = 165f, srcW = W, srcH = 665f) {
        drawRoad(t, c)

        drawSoundArcs(t, side = -1, env = bikeEnv, loud = 0.42f, color = c.arc)
        drawSoundArcs(t, side = 1, env = truckEnv, loud = 1f, color = c.arc)

        drawHandlebar(c)

        withTransform({
            translate(vibX, vibY)
            rotate(vibRot, Offset(PHONE_CX, PHONE_CY))
            // Stand-in for the design file's `perspective(1100px) rotateX(19deg)`: the phone is
            // tilted away from the rider, which foreshortens it vertically by cos(19deg).
            scale(1f, cos(19f * PI_F / 180f), Offset(PHONE_CX, PHONE_CY))
        }) {
            drawPhone(t, c, screenColor, ink, bikeEnv, truckEnv, alertEnv, idleOpacity, loud)
        }
    }
}

private fun DrawScope.drawRoad(t: Float, c: RoadPalette) {
    slab(0f, HORIZON, W, H - HORIZON, c.verge)

    // Offset is a value class, so these take a List rather than a vararg.
    fun wedge(points: List<Offset>, color: Color, alpha: Float = 1f) {
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }
        clipRect(0f, HORIZON, W, H) {
            drawPath(path, color, alpha = alpha)
        }
    }

    wedge(
        listOf(Offset(576f, HORIZON), Offset(624f, HORIZON), Offset(1584f, H), Offset(-384f, H)),
        color = c.road
    )
    wedge(
        listOf(Offset(584.4f, HORIZON), Offset(588f, HORIZON), Offset(24f, H), Offset(-96f, H)),
        color = c.dash, alpha = 0.5f
    )
    wedge(
        listOf(Offset(612f, HORIZON), Offset(615.6f, HORIZON), Offset(1296f, H), Offset(1176f, H)),
        color = c.dash, alpha = 0.5f
    )

    // Centre dashes, rushing at the rider. u is squared so they accelerate as they approach,
    // which is what sells the horizon as distance rather than a gradient.
    for (i in 0..6) {
        val u = wrap(t * 0.5f + i / 7f, 1f)
        val d = u * u
        val w = 9f + 60f * d
        slab(
            x = 600f - w / 2f,
            y = HORIZON + 810f * d,
            w = w,
            h = 12f + 95f * d,
            color = c.dash,
            radius = w / 2f,
            alpha = 0.3f + 0.7f * d
        )
    }
}

/**
 * Arcs travelling in from one edge of the frame. [side] is -1 for the left edge, +1 for the right.
 *
 * Each arc is the near half of an ellipse outline, which is how the design file draws them: a
 * bordered ellipse with only one side's border colour set.
 */
private fun DrawScope.drawSoundArcs(t: Float, side: Int, env: Float, loud: Float, color: Color) {
    if (env <= 0.01f) return
    val count = (3f + loud * 2f).roundToInt()
    val period = 1.05f - loud * 0.45f
    val baseH = 82f + loud * 86f
    val stroke = 5f + loud * 5f
    val travel = 96f + loud * 34f

    for (i in 0 until count) {
        val u = wrap(t - (i * period) / count, period) / period
        val h = baseH * (0.55f + 0.75f * u)
        val w = h * 0.44f
        val alpha = env * sin(u * PI_F) * (0.5f + 0.5f * loud)
        if (alpha <= 0.02f) continue

        val edge = 40f + u * travel
        val left = if (side < 0) edge else W - edge - w
        // The border sits inside the box, so the stroke centreline is half a width in.
        val rect = Rect(
            left + stroke / 2f,
            400f - h / 2f + stroke / 2f,
            left + w - stroke / 2f,
            400f + h / 2f - stroke / 2f
        )
        drawArc(
            color = color,
            // Left-edge arcs show the ellipse's right side (opening away from the rider) and
            // vice versa, so both read as travelling inward.
            startAngle = if (side < 0) -90f else 90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = rect.topLeft,
            size = rect.size,
            alpha = clamp01(alpha),
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
    }
}

private fun DrawScope.drawHandlebar(c: RoadPalette) {
    slab(544f, 690f, 112f, 210f, c.grip, radius = 56f)
    slab(534f, 580f, 132f, 190f, c.barDeep, radius = 26f)
    slab(452f, 570f, 34f, 210f, c.bar, radius = 17f, rotation = 9f)
    slab(714f, 570f, 34f, 210f, c.bar, radius = 17f, rotation = -9f)

    slab(218f, 612f, 764f, 44f, c.barDeep, radius = 22f)
    slab(78f, 618f, 186f, 44f, c.grip, radius = 22f, rotation = 9f, pivot = Offset(264f, 640f))
    slab(936f, 618f, 186f, 44f, c.grip, radius = 22f, rotation = -9f, pivot = Offset(936f, 640f))
    slab(254f, 668f, 136f, 18f, c.barDeep, radius = 9f, rotation = 14f, pivot = Offset(390f, 677f))
    slab(810f, 668f, 136f, 18f, c.barDeep, radius = 9f, rotation = -14f, pivot = Offset(810f, 677f))
    slab(582f, 550f, 36f, 90f, c.barDeep, radius = 18f)
}

private fun DrawScope.drawPhone(
    t: Float,
    c: RoadPalette,
    screenColor: Color,
    ink: Color,
    bikeEnv: Float,
    truckEnv: Float,
    alertEnv: Float,
    idleOpacity: Float,
    loud: Float
) {
    slab(318f, 303f, 564f, 318f, c.shadow, radius = 44f)
    slab(318f, 281f, 564f, 318f, c.bezel, radius = 44f)
    slab(320f, 283f, 560f, 314f, c.phone, radius = 42f)
    slab(342f, PHONE_CY - 27f, 7f, 54f, c.bezel, radius = 4f)
    slab(SCREEN_X, SCREEN_Y, SCREEN_W, SCREEN_H, screenColor, radius = 28f)
    // Mount clamp, gripping the phone from below.
    slab(546f, 587f, 108f, 26f, c.barDeep, radius = 13f)

    clipRect(SCREEN_X, SCREEN_Y, SCREEN_X + SCREEN_W, SCREEN_Y + SCREEN_H) {
        drawWaveform(t, c.listen, idleOpacity)
        if (alertEnv > 0.01f) {
            drawChevrons(t, pointsLeft = true, x = 474f, alpha = bikeEnv * alertEnv, color = ink)
            drawChevrons(t, pointsLeft = false, x = 680f, alpha = truckEnv * alertEnv, color = ink)
            withTransform({ translate(544f, 384f) }) {
                drawBikeIcon(bikeEnv * alertEnv, c.amberInk)
                drawTruckIcon(truckEnv * alertEnv, c.redInk, c.red)
            }
        }
    }

    // Live level meter is part of the same screen in the design file; it only means anything while
    // something is actually being heard, so it rides the alert envelope too.
    if (alertEnv > 0.01f) drawLevelMeter(t, loud, alertEnv, ink)
}

/** The HUD's own listening treatment: a symmetric bar spectrum over a status pill. */
private fun DrawScope.drawWaveform(t: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    val rowTop = 404.5f
    val barsLeft = 518.5f
    for (i in 0..12) {
        val d = abs(i - 6) / 6f
        val freq = 2.1f + (i % 3) * 0.4f
        val h = 10f + (34f - 22f * d) * (0.55f + 0.45f * sin(t * freq + i * 0.62f))
        slab(barsLeft + i * 13f, rowTop + (46f - h) / 2f, 7f, h, color, radius = 4f, alpha = alpha)
    }
    disc(542f, 471f, 4.5f, color, alpha)
    slab(554.5f, 468f, 108f, 6f, color, radius = 3f, alpha = alpha * 0.45f)
}

private fun DrawScope.drawLevelMeter(t: Float, loud: Float, alpha: Float, color: Color) {
    val left = SCREEN_X + (SCREEN_W - (14 * 6f + 13 * 6f)) / 2f
    val baseline = SCREEN_Y + SCREEN_H - 24f
    for (i in 0..13) {
        val wobble = 0.55f + 0.45f * sin(t * (7f + (i % 4)) + i * 0.9f)
        val h = 5f + (6f + 26f * loud) * wobble
        slab(
            x = left + i * 12f,
            y = baseline - h,
            w = 6f,
            h = h,
            color = color,
            radius = 3f,
            alpha = alpha * (0.35f + 0.5f * loud)
        )
    }
}

/**
 * Two stacked triangles pointing at the side the sound came from, drifting slightly that way. The
 * leading one is at full strength and the trailing one dimmed, so the pair reads as a direction
 * rather than as two equal marks.
 */
private fun DrawScope.drawChevrons(t: Float, pointsLeft: Boolean, x: Float, alpha: Float, color: Color) {
    if (alpha <= 0.01f) return
    val drift = sin(t * 4f) * 5f * (if (pointsLeft) -1f else 1f)
    val top = 426f
    val strengths = if (pointsLeft) listOf(0.4f, 1f) else listOf(1f, 0.4f)
    strengths.forEachIndexed { i, strength ->
        val left = x + drift + i * 27f
        val path = Path().apply {
            if (pointsLeft) {
                moveTo(left, top + 14f)
                lineTo(left + 19f, top)
                lineTo(left + 19f, top + 28f)
            } else {
                moveTo(left, top)
                lineTo(left + 19f, top + 14f)
                lineTo(left, top + 28f)
            }
            close()
        }
        drawPath(path, color, alpha = clamp01(alpha * strength))
    }
}

/** 112x112 glyph, drawn in local coordinates; the caller translates it into the icon slot. */
private fun DrawScope.drawBikeIcon(alpha: Float, color: Color) {
    if (alpha <= 0.01f) return
    val a = clamp01(alpha)
    val stroke = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    drawCircle(color, radius = 19f, center = Offset(26f, 82f), alpha = a, style = stroke)
    drawCircle(color, radius = 19f, center = Offset(86f, 82f), alpha = a, style = stroke)

    fun polyline(points: List<Offset>, width: Float = 8f) {
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        drawPath(
            path,
            color,
            alpha = a,
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    polyline(
        listOf(Offset(36f, 66f), Offset(44f, 58f), Offset(66f, 56f), Offset(74f, 50f), Offset(82f, 64f))
    )
    polyline(listOf(Offset(22f, 55f), Offset(46f, 57f)), width = 9f)
    polyline(listOf(Offset(74f, 50f), Offset(77f, 42f)))
    polyline(listOf(Offset(68f, 40f), Offset(92f, 38f)))

    slab(48f, 42f, 26f, 17f, color, radius = 8f, alpha = a, rotation = -6f, pivot = Offset(61f, 50f))
    slab(49f, 60f, 21f, 15f, color, radius = 4f, alpha = a)
}

/** 112x112 glyph, drawn in local coordinates; [bg] is the screen behind it, showing as the cab window. */
private fun DrawScope.drawTruckIcon(alpha: Float, color: Color, bg: Color) {
    if (alpha <= 0.01f) return
    val a = clamp01(alpha)
    slab(2f, 20f, 58f, 46f, color, radius = 5f, alpha = a)
    slab(62f, 34f, 34f, 32f, color, radius = 7f, alpha = a)
    slab(68f, 40f, 22f, 14f, bg, radius = 3f, alpha = a)
    slab(2f, 66f, 96f, 9f, color, radius = 5f, alpha = a)
    ringD(26f, 80f, 20f, 6f, color, a)
    ringD(78f, 80f, 20f, 6f, color, a)
}
