package com.soundsight.ui.settings.illustration

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Material 3 spot illustration for direction calibration: a rider holding the phone horizontally
 * at arm's length, saying "AH" into it, with the amber dot marking the edge the app is listening
 * on.
 *
 * Ported from the Claude Design handoff bundle at `android-app-illustration-design/`
 * (`project/Mic Calibration Illustration.dc.html`), keeping its 512x512 stage coordinates so a
 * tweak in the design file transcribes across line for line. The bundle's two boolean props
 * survive as [showWaves] and [showMicDot].
 *
 * THE COMPOSITION IS ONE-SIDED AND THAT IS THE POINT. The figure stands on the left, holds the
 * phone out to the right, the waves travel left->right, and the mic dot sits on the phone edge
 * FACING THE FIGURE. Step 2 of calibration asks for the opposite side, so it needs the whole
 * scene mirrored - dot included. [mirrored] therefore flips the entire stage about its centre in
 * one transform rather than repositioning pieces by hand: there is no way for the dot to end up
 * on the far edge, which would teach the rider to stand where the app is not listening.
 * [micDotRect] states the resulting geometry for the test that guards it.
 *
 * COLOURS ARE THE DESIGN FILE'S OWN, both variants, and that is on purpose. A Material 3 spot
 * illustration is an asset with a palette, not chrome: it is not recoloured by the dynamic
 * scheme any more than a photograph or an emoji would be, and an earlier pass that mapped every
 * shape onto surface/secondary roles turned a warm drawing into grey-blue mush. What DOES follow
 * the theme is the choice of variant - see [illustrationIsDark] - so the drawing matches the card
 * it sits in rather than the phone's system setting.
 *
 * The design's single amber (`#E08A00` / `#F0B84B`) covers both the waves and the mic dot, so
 * both read [MicPalette.signal] here. It is close to, but not, the hazard warning amber; nothing
 * in this drawing is a safety signal, and the on-screen direction arrow stays a theme-coloured
 * control rather than borrowing the illustration's paint.
 */

/** The design file's stage is square; nothing is cropped when the canvas is square too. */
internal const val MIC_STAGE = 512f

private const val PHONE_X = 352f
private const val PHONE_Y = 228f
private const val PHONE_W = 152f
private const val PHONE_H = 92f

private const val DOT_X = 353f
private const val DOT_Y = 262f
private const val DOT_W = 8f
private const val DOT_H = 34f

/** Mouth centre, and the shared centre of all three wave arcs. */
private const val MOUTH_X = 124f
private const val MOUTH_Y = 282f

/** A stage-coordinate rectangle, left/top/width/height, in the design file's own units. */
internal data class StageRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
}

/** Mirrors a rectangle's x about the centre of the square stage. */
private fun mirrorX(x: Float, w: Float): Float = MIC_STAGE - x - w

/** Where the phone body lands, so a test can say which of its edges the dot is on. */
internal fun phoneBodyRect(mirrored: Boolean): StageRect =
    if (mirrored) StageRect(mirrorX(PHONE_X, PHONE_W), PHONE_Y, PHONE_W, PHONE_H)
    else StageRect(PHONE_X, PHONE_Y, PHONE_W, PHONE_H)

/** Where the listening dot lands. Always the phone edge nearest the figure - see the class doc. */
internal fun micDotRect(mirrored: Boolean): StageRect =
    if (mirrored) StageRect(mirrorX(DOT_X, DOT_W), DOT_Y, DOT_W, DOT_H)
    else StageRect(DOT_X, DOT_Y, DOT_W, DOT_H)

private class MicPalette(
    val ground: Color,
    val shirt: Color,
    val skin: Color,
    val skinShade: Color,
    val hair: Color,
    val mouth: Color,
    val phone: Color,
    val screen: Color,
    val signal: Color
)

@Composable
fun MicCalibrationIllustration(
    mirrored: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    showWaves: Boolean = true,
    showMicDot: Boolean = true
) {
    val dark = illustrationIsDark()
    val description = contentDescription

    val palette = remember(dark) {
        if (dark) {
            MicPalette(
                ground = Color(0xFF1B1712),
                shirt = Color(0xFFB8871E),
                skin = Color(0xFFA9603F),
                skinShade = Color(0xFF8A4C31),
                hair = Color(0xFF4C3226),
                mouth = Color(0xFF2A1610),
                phone = Color(0xFF4A453E),
                screen = Color(0xFF2A2620),
                signal = Color(0xFFF0B84B)
            )
        } else {
            MicPalette(
                ground = Color(0xFFFFFBF3),
                shirt = Color(0xFFF5B72C),
                skin = Color(0xFFA9603F),
                skinShade = Color(0xFF8E4E33),
                hair = Color(0xFF6B4636),
                mouth = Color(0xFF43241A),
                phone = Color(0xFF3B3730),
                screen = Color(0xFFE8E3DA),
                signal = Color(0xFFE08A00)
            )
        }
    }

    Canvas(
        modifier = modifier
            .testTag(if (mirrored) "illustration_mic_calibration_right" else "illustration_mic_calibration_left")
            .semantics { this.contentDescription = description }
    ) {
        drawRect(palette.ground)
        stage(srcX = 0f, srcY = 0f, srcW = MIC_STAGE, srcH = MIC_STAGE) {
            if (mirrored) {
                withTransform({ scale(-1f, 1f, Offset(MIC_STAGE / 2f, MIC_STAGE / 2f)) }) {
                    drawFigure(palette, showWaves, showMicDot)
                }
            } else {
                drawFigure(palette, showWaves, showMicDot)
            }
        }
    }
}

/**
 * Everything but the ground, in design-file paint order. Drawn once and mirrored as a whole by
 * the caller, which is what keeps the dot on the correct edge.
 */
private fun DrawScope.drawFigure(c: MicPalette, showWaves: Boolean, showMicDot: Boolean) {
    // Torso, mostly off the bottom-left corner of the stage.
    oval(cx = 10f, cy = 600f, rx = 278f, ry = 222f, color = c.shirt)
    // Sleeve, then the bare forearm reaching out to the phone.
    limb(118f, 462f, 226f, 392f, width = 88f, color = c.shirt)
    limb(214f, 400f, 350f, 336f, width = 56f, color = c.skin)
    // Palm, behind the phone.
    oval(cx = 374f, cy = 296f, rx = 52f, ry = 44f, color = c.skinShade, rotation = -25f)

    slab(PHONE_X, PHONE_Y, PHONE_W, PHONE_H, c.phone, radius = 20f)
    slab(366f, 242f, 126f, 64f, c.screen, radius = 12f)
    if (showMicDot) {
        val dot = micDotRect(mirrored = false)
        slab(dot.x, dot.y, dot.w, dot.h, c.signal, radius = 4f)
    }

    // Four fingers curling over the phone's top edge; the far one sits in its own shadow.
    slab(360f, 214f, 24f, 54f, c.skin, radius = 12f, rotation = -9f, pivot = Offset(372f, 241f))
    slab(388f, 216f, 24f, 54f, c.skin, radius = 12f, rotation = -5f, pivot = Offset(400f, 243f))
    slab(416f, 218f, 24f, 52f, c.skin, radius = 12f, rotation = -2f, pivot = Offset(428f, 244f))
    slab(444f, 220f, 24f, 48f, c.skinShade, radius = 12f)

    // Neck, hair, face, nose, open mouth.
    slab(36f, 296f, 70f, 110f, c.skinShade, radius = 35f)
    disc(42f, 232f, 100f, c.hair)
    disc(62f, 238f, 92f, c.skin)
    oval(cx = 156f, cy = 236f, rx = 13f, ry = 15f, color = c.skin)
    oval(cx = 114f, cy = 278f, rx = 16f, ry = 21f, color = c.mouth, rotation = -12f)

    if (showWaves) {
        // Three concentric arcs leaving the mouth, aimed 16 degrees above the horizontal at the
        // phone. Each is the near half of a circle, so they open toward the device.
        withTransform({ rotate(-16f, Offset(MOUTH_X, MOUTH_Y)) }) {
            mouthArc(radius = 54f, halfChord = 38f, tipX = 162f, strokeWidth = 13f, color = c.signal, alpha = 1f)
            mouthArc(radius = 82f, halfChord = 58f, tipX = 182f, strokeWidth = 11f, color = c.signal, alpha = 0.7f)
            mouthArc(radius = 110f, halfChord = 78f, tipX = 202f, strokeWidth = 9f, color = c.signal, alpha = 0.45f)
        }
    }
}

/** An SVG `<ellipse>`: centre, semi-axes, optionally rotated about its own centre. */
private fun DrawScope.oval(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    color: Color,
    rotation: Float = 0f
) {
    withTransform({ rotate(rotation, Offset(cx, cy)) }) {
        drawOval(color = color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f))
    }
}

/** A round-capped stroke between two points - the design file's arm segments. */
private fun DrawScope.limb(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(x1, y1),
        end = Offset(x2, y2),
        strokeWidth = width,
        cap = StrokeCap.Round
    )
}

/**
 * One wave arc, transcribed from `M tipX (282-halfChord) A r r 0 0 1 tipX (282+halfChord)`.
 *
 * The SVG states the arc by its endpoints and radius; Compose wants a centre and a sweep, so the
 * centre is recovered from the chord (it is [halfChord] either side of [MOUTH_Y] at [tipX]) and
 * the sweep from the half-angle it subtends. Sweep flag 1 with y pointing down puts the bulge on
 * the +x side, which is why the centre sits to the LEFT of the endpoints.
 */
private fun DrawScope.mouthArc(
    radius: Float,
    halfChord: Float,
    tipX: Float,
    strokeWidth: Float,
    color: Color,
    alpha: Float
) {
    val toCentre = sqrt(radius * radius - halfChord * halfChord)
    val cx = tipX - toCentre
    val halfAngle = Math.toDegrees(atan2(halfChord.toDouble(), toCentre.toDouble())).toFloat()
    drawArc(
        color = color,
        startAngle = -halfAngle,
        sweepAngle = halfAngle * 2f,
        useCenter = false,
        topLeft = Offset(cx - radius, MOUTH_Y - radius),
        size = Size(radius * 2f, radius * 2f),
        alpha = alpha,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}
