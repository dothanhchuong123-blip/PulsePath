package com.soundsight.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundsight.core.AlertConfig
import com.soundsight.core.AppLanguage
import com.soundsight.core.Strings
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.DirectionEstimate
import com.soundsight.domain.GlyphId
import com.soundsight.domain.Presentation
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.Side
import com.soundsight.domain.SoundId
import com.soundsight.domain.Tier
import com.soundsight.ui.theme.ContrastBoost
import com.soundsight.ui.theme.LocalContrastBoostLevel
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.Motion

/**
 * Full-screen hazard takeover.
 *
 * Rebuilt around one idea: **the glyph is the message.** The previous version gave the icon and the
 * label roughly equal weight, which made a WARNING read as a paragraph to be parsed rather than a
 * shape to be recognised. Here the glyph sits in a large tonal disc that occupies the optical
 * centre, the label sits under it, and the caption is demoted to supporting text. A rider
 * recognises the disc-and-glyph silhouette before reading a single character.
 *
 * WARNING and CRITICAL are deliberately different *shapes*, not just different colours:
 *  - CRITICAL breathes (the disc pulses) and fills the screen edge to edge.
 *  - WARNING is static and inset, sitting on a rounded card with the background still visible
 *    around it.
 * Colour alone is not enough - it is the one channel that sunlight, a cheap screen and colour
 * vision deficiency all attack at once.
 */
@Composable
fun AlertTakeover(
    event: SoundEvent,
    presentation: Presentation,
    language: AppLanguage,
    strings: Strings,
    direction: DirectionEstimate = DirectionEstimate.UNKNOWN,
    onDismiss: () -> Unit
) {
    val hazard = LocalHazardColors.current
    // The takeover's translucent layers are the first thing sunlight erases, so they firm up
    // with the boost instead of staying at their normal-light weights on a deepened background.
    val boostLevel = LocalContrastBoostLevel.current
    val isCritical = event.tier == Tier.CRITICAL
    val bgColor = if (isCritical) hazard.Critical else hazard.Warning
    val fgColor = if (isCritical) hazard.OnCritical else hazard.OnWarning

    val tierLabel = if (language == AppLanguage.VI) {
        AlertPresentation.getLabelVi(event)
    } else {
        AlertPresentation.getLabelEn(event)
    }

    // VOICE_SHOUT overrides the classifier's descriptive caption with a fixed instruction. Every
    // other alert's caption describes what was heard, which is right when the rider has to judge
    // the hazard themselves; here there is exactly one correct response and no judgement to make,
    // so the caption says it instead of describing the shout.
    val caption = if (event.soundId == SoundId.VOICE_SHOUT) {
        strings.voiceHailAction
    } else if (language == AppLanguage.VI) {
        event.captionVi.ifBlank { event.captionEn }
    } else {
        event.captionEn.ifBlank { event.captionVi }
    }

    val iconVector: ImageVector = glyphIcon(presentation.glyph)

    val infiniteTransition = rememberInfiniteTransition(label = "critical_breathe")
    val discScale by if (isCritical) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = Motion.DurationBreath, easing = Motion.Standard),
                repeatMode = RepeatMode.Reverse
            ),
            label = "disc_breathe"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = Motion.DurationAlertIn,
                easing = Motion.EmphasizedDecelerate
            )
        ) + scaleIn(
            initialScale = 0.92f,
            animationSpec = tween(
                durationMillis = Motion.DurationAlertIn,
                easing = Motion.EmphasizedDecelerate
            )
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isCritical) bgColor else MaterialTheme.colorScheme.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(if (isCritical) 0.dp else 20.dp)
                .testTag("alert_takeover_screen"),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = bgColor,
                contentColor = fgColor,
                // CRITICAL is edge to edge; WARNING is an inset card. The silhouette differs
                // before any colour is read.
                shape = if (isCritical) RoundedCornerShape(0.dp) else RoundedCornerShape(36.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                BoxWithConstraints {
                    val compact = maxHeight < 460.dp
                    // Sized off height first, then clamped by width. The width clamp is what
                    // keeps the flanked row on screen: disc + two flanks comes to just over twice
                    // the disc, so a disc chosen purely from height overflowed the row on a narrow
                    // phone, and an overflowing Row stops centring - the hazard glyph drifted off
                    // to one side exactly when a direction estimate arrived.
                    val flankRatio = 0.52f
                    val discSize = (maxHeight * if (compact) 0.44f else 0.34f)
                        .coerceIn(120.dp, 240.dp)
                        .coerceAtMost((maxWidth - 48.dp) / (1f + 2f * flankRatio))
                    // Wide enough for the chevron's own tonal disc (iconSize * 1.55) plus a gap.
                    val flankWidth = discSize * flankRatio

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // The direction chevrons flank the disc rather than sitting above or
                        // below it: on this screen the disc *is* the hazard, so putting the mark
                        // to its left or right says "from that side of the hazard" without any
                        // text. Both slots are always composed so each can animate out; see
                        // DirectionIndicator.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            // Both flanks reserve the same width whether or not they are showing
                            // anything. An AnimatedVisibility that is hidden occupies no space, so
                            // without this the disc slides off-centre the moment one side has an
                            // arrow - and it would slide back mid-alert if the estimate arrived
                            // late. The hazard glyph must not move.
                            Box(
                                modifier = Modifier.size(flankWidth),
                                contentAlignment = Alignment.Center
                            ) {
                                DirectionIndicator(
                                    direction = direction,
                                    slot = Side.LEFT,
                                    tint = fgColor,
                                    iconSize = (discSize * 0.30f),
                                    showLabel = AlertConfig.directionLabelEnabled,
                                    style = AlertConfig.arrowStyle
                                )
                            }

                            // Tonal disc behind the glyph. Gives the icon a defined silhouette
                            // instead of letting it float on a flat field, which is what made the
                            // old WARNING look unfinished next to the red CRITICAL.
                            Box(
                                modifier = Modifier
                                    .size(discSize)
                                    .scale(discScale)
                                    .background(
                                        color = fgColor.copy(alpha = ContrastBoost.chipAlpha(boostLevel, 0.16f)),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = iconVector,
                                    contentDescription = tierLabel,
                                    tint = fgColor,
                                    modifier = Modifier
                                        .size(discSize * 0.52f)
                                        .testTag("alert_takeover_glyph")
                                )
                            }

                            Box(
                                modifier = Modifier.size(flankWidth),
                                contentAlignment = Alignment.Center
                            ) {
                                DirectionIndicator(
                                    direction = direction,
                                    slot = Side.RIGHT,
                                    tint = fgColor,
                                    iconSize = (discSize * 0.30f),
                                    showLabel = AlertConfig.directionLabelEnabled,
                                    style = AlertConfig.arrowStyle
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(if (compact) 16.dp else 28.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = tierLabel.uppercase(),
                                style = if (compact) {
                                    MaterialTheme.typography.displaySmall
                                } else {
                                    MaterialTheme.typography.displayMedium
                                },
                                color = fgColor,
                                fontWeight = FontWeight.W800,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("alert_takeover_label")
                            )

                            if (event.repeatCount > 1) {
                                Surface(
                                    color = fgColor.copy(alpha = ContrastBoost.chipAlpha(boostLevel, 0.22f)),
                                    contentColor = fgColor,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .padding(start = 10.dp)
                                        .testTag("alert_repeat_badge")
                                ) {
                                    Text(
                                        text = "×${event.repeatCount}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 4.dp
                                        )
                                    )
                                }
                            }

                            if (event.isDemo) {
                                Surface(
                                    color = fgColor.copy(alpha = ContrastBoost.chipAlpha(boostLevel, 0.22f)),
                                    contentColor = fgColor,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .padding(start = 10.dp)
                                        .testTag("alert_demo_badge")
                                ) {
                                    Text(
                                        text = strings.demoAlertBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 4.dp
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = caption,
                            style = MaterialTheme.typography.titleMedium,
                            color = fgColor.copy(alpha = ContrastBoost.captionAlpha(boostLevel)),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .testTag("alert_takeover_caption")
                        )

                        Spacer(modifier = Modifier.height(if (compact) 18.dp else 32.dp))

                        // Dismiss is a hint, not a button: the alert clears itself on a timer and
                        // tapping anywhere works. A full-width button here competed with the glyph
                        // for attention and implied the rider had to act on the phone, which is
                        // exactly the wrong instinct at speed.
                        Surface(
                            color = fgColor.copy(alpha = ContrastBoost.chipAlpha(boostLevel, 0.16f)),
                            contentColor = fgColor,
                            shape = CircleShape,
                            modifier = Modifier.testTag("alert_dismiss_button")
                        ) {
                            Text(
                                text = strings.dismissLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single source of truth for which icon a [GlyphId] draws as.
 *
 * Shared by the takeover and by the HUD's wave-to-glyph morph, so the shape the wave becomes is
 * always the shape the takeover then shows - the morph would read as a glitch if the two mappings
 * ever drifted apart.
 *
 * Rounded rather than Filled: the rounded set is Material You's own icon style, and the softer
 * terminals stay legible when the glyph is scaled up to fill a takeover screen.
 */
fun glyphIcon(glyph: GlyphId): ImageVector = when (glyph) {
    GlyphId.SHIELD -> Icons.Rounded.Shield
    GlyphId.MEGAPHONE -> Icons.Rounded.Campaign
    GlyphId.PERSON_HAIL -> Icons.Rounded.RecordVoiceOver
    GlyphId.BELL -> Icons.Rounded.NotificationsActive
    GlyphId.GAUGE -> Icons.Rounded.Speed
    GlyphId.ARROW_DOWN -> Icons.Rounded.ArrowDownward
    GlyphId.TRUCK -> Icons.Rounded.LocalShipping
    GlyphId.SIREN -> Icons.Rounded.Emergency
    GlyphId.TRAIN -> Icons.Rounded.Train
    GlyphId.BURST -> Icons.Rounded.Warning
    GlyphId.QUESTION -> Icons.Rounded.QuestionMark
}
