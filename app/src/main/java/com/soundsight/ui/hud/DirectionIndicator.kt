package com.soundsight.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleLeft
import androidx.compose.material.icons.rounded.ArrowCircleRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soundsight.core.ArrowStyle
import com.soundsight.core.SoundSightStrings
import com.soundsight.domain.DirectionConfidence
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.Side
import com.soundsight.ui.theme.Motion

/**
 * Which side an alert came from, when the stereo analyser could tell.
 *
 * ## Nothing is drawn for UNKNOWN
 *
 * UNKNOWN is not an error state, it is the *usual* state: the analyser reports it whenever the
 * phone is in portrait, whenever direction was switched off during calibration, and whenever the
 * correlation was not clean enough for [com.soundsight.domain.DirectionGate] to stand behind a
 * side. A greyed-out arrow or a "direction unavailable" chip would put a permanent piece of
 * furniture on the HUD that says nothing, and worse, would train the rider to filter out the exact
 * region of the screen where a real arrow is going to appear. So the indicator is absent, and its
 * presence is therefore always information.
 *
 * ## Confidence is carried by shape, not only by colour
 *
 * HIGH draws a doubled or filled glyph in a filled container; MEDIUM draws a single or outlined
 * one in an outlined container. Those differ in glyph, in stroke count and in fill, so the
 * distinction survives direct sunlight, a cheap screen and colour vision deficiency - all three of
 * which attack colour first. The same reasoning governs the CRITICAL/WARNING split in
 * [AlertTakeover]. [style] changes which glyph family is used and nothing else; see [glyphFor].
 *
 * ## Entrance motion points
 *
 * The indicator slides in *from* the centre *towards* its own side. The travel is short and
 * happens once, on arrival - there is no looping animation. A rider only ever needs to be told the
 * direction once per alert, and a permanent pulse in peripheral vision is the kind of ambient
 * motion that stops being read within a day of use.
 */
@Composable
fun DirectionIndicator(
    direction: DirectionEstimate,
    slot: Side,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 44.dp,
    showLabel: Boolean = true,
    style: ArrowStyle = ArrowStyle.CHEVRON
) {
    val strings = SoundSightStrings.current

    // [slot] is which side of the layout this instance occupies; it is fixed for the lifetime of
    // the composable. Both slots are always composed and each shows itself only when the estimate
    // names its side. Composing only the matching slot would be simpler, but removing the
    // composable outright destroys it before AnimatedVisibility can run the exit - the arrow would
    // vanish instantly at the end of every alert while everything else on the HUD eased out.
    val visible = direction.side == slot

    // Slide direction: LEFT travels left (negative x), RIGHT travels right.
    val travel = if (slot == Side.LEFT) -1 else 1

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = Motion.defaultSpatial()
        ) { full -> (full * 0.45f).toInt() * travel } + fadeIn(
            animationSpec = Motion.defaultEffects()
        ) + scaleIn(
            animationSpec = Motion.defaultSpatial(),
            initialScale = 0.7f
        ),
        exit = fadeOut(animationSpec = Motion.fastEffects()) + scaleOut(
            animationSpec = Motion.fastSpatial(),
            targetScale = 0.8f
        ),
        modifier = modifier
    ) {
        val isHigh = direction.confidence == DirectionConfidence.HIGH
        // Continuous, not a branch: the ring thickens in step with the palette fade instead of
        // snapping 2dp wider halfway through it.
        val boostLevel = com.soundsight.ui.theme.LocalContrastBoostLevel.current
        val borderWidth = com.soundsight.ui.theme.ContrastBoost.borderWidth(boostLevel, 2.dp, 4.dp)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("hud_direction_indicator")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(iconSize * 1.55f)
                    .then(
                        if (isHigh) {
                            Modifier
                                .background(
                                    color = tint.copy(
                                        alpha = com.soundsight.ui.theme.ContrastBoost.alpha(
                                            boostLevel,
                                            normal = 0.20f,
                                            boosted = 0.35f
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .then(
                                    // The confirming ring grows out of nothing rather than
                                    // appearing at full weight: 0dp is an absent border.
                                    if (boostLevel > 0f) {
                                        Modifier.border(
                                            width = com.soundsight.ui.theme.ContrastBoost
                                                .borderWidth(boostLevel, 0.dp, 3.dp),
                                            color = tint,
                                            shape = CircleShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        } else {
                            Modifier.border(
                                width = borderWidth,
                                color = tint.copy(
                                    alpha = com.soundsight.ui.theme.ContrastBoost.alpha(
                                        boostLevel,
                                        normal = 0.45f,
                                        boosted = 1.0f
                                    )
                                ),
                                shape = CircleShape
                            )
                        }
                    )
            ) {
                Icon(
                    imageVector = glyphFor(slot, isHigh, style),
                    contentDescription = sideLabel(slot, strings),
                    tint = tint,
                    modifier = Modifier
                        .size(iconSize)
                        .testTag(
                            if (isHigh) "direction_chevron_high" else "direction_chevron_medium"
                        )
                )
            }

            if (showLabel) {
                Text(
                    text = sideLabel(slot, strings),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    fontWeight = if (isHigh) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.testTag("direction_label")
                )
            }
        }
    }
}

/**
 * Deliberately **not** the `Icons.AutoMirrored` variants, in either style.
 *
 * Auto-mirrored icons flip in a right-to-left layout, which is correct for a "back" arrow - back
 * follows reading order. It is wrong here. This arrow reports which side of the *motorbike* a
 * sound physically came from, and a lorry on the rider's left is on their left in every locale.
 * Mirroring it would point at the wrong side of the road for RTL users.
 *
 * Every pair below is non-mirroring, and every pair keeps the high/medium distinction *in the
 * glyph itself* - doubled or filled for HIGH, single or outlined for MEDIUM - so switching style
 * changes the look without costing the confidence signal. That signal is carried three ways at
 * once (glyph, container fill, stroke count) precisely so no single style choice can erase it.
 */
private fun glyphFor(side: Side, isHigh: Boolean, style: ArrowStyle): ImageVector =
    when (style) {
        ArrowStyle.CHEVRON -> when {
            side == Side.LEFT && isHigh -> Icons.Rounded.KeyboardDoubleArrowLeft
            side == Side.LEFT -> Icons.Rounded.ChevronLeft
            isHigh -> Icons.Rounded.KeyboardDoubleArrowRight
            else -> Icons.Rounded.ChevronRight
        }
        ArrowStyle.ARROW -> when {
            side == Side.LEFT && isHigh -> Icons.Rounded.ArrowCircleLeft
            side == Side.LEFT -> Icons.Rounded.West
            isHigh -> Icons.Rounded.ArrowCircleRight
            else -> Icons.Rounded.East
        }
    }

private fun sideLabel(side: Side, strings: com.soundsight.core.Strings): String =
    if (side == Side.LEFT) strings.directionLeft else strings.directionRight
