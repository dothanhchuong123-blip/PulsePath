package com.soundsight.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsight.core.AppLanguage
import com.soundsight.core.Strings
import com.soundsight.domain.AlertPresentation
import com.soundsight.domain.GlyphId
import com.soundsight.domain.Presentation
import com.soundsight.domain.SoundEvent
import com.soundsight.domain.Tier
import com.soundsight.ui.theme.AlertLabel
import com.soundsight.ui.theme.AlertSub
import com.soundsight.ui.theme.CriticalBg
import com.soundsight.ui.theme.CriticalFg
import com.soundsight.ui.theme.WarningBg
import com.soundsight.ui.theme.WarningFg

@Composable
fun AlertTakeover(
    event: SoundEvent,
    presentation: Presentation,
    language: AppLanguage,
    strings: Strings,
    onDismiss: () -> Unit
) {
    val isCritical = event.tier == Tier.CRITICAL
    val bgColor = if (isCritical) CriticalBg else WarningBg
    val fgColor = if (isCritical) CriticalFg else WarningFg

    val tierLabel = when (event.soundId) {
        else -> if (language == AppLanguage.VI) {
            AlertPresentation.getLabelVi(event.soundId)
        } else {
            AlertPresentation.getLabelEn(event.soundId)
        }
    }

    val caption = if (language == AppLanguage.VI) {
        if (event.captionVi.isNotBlank()) event.captionVi else event.captionEn
    } else {
        if (event.captionEn.isNotBlank()) event.captionEn else event.captionVi
    }

    val iconVector: ImageVector = when (presentation.glyph) {
        GlyphId.SHIELD -> Icons.Filled.Shield
        GlyphId.MEGAPHONE -> Icons.Filled.Campaign
        GlyphId.BELL -> Icons.Filled.Notifications
        GlyphId.GAUGE -> Icons.Filled.Speed
        GlyphId.ARROW_DOWN -> Icons.Filled.ArrowDownward
        GlyphId.TRUCK -> Icons.Filled.LocalShipping
        GlyphId.SIREN -> Icons.Filled.Emergency
        GlyphId.TRAIN -> Icons.Filled.Train
        GlyphId.BURST -> Icons.Filled.Warning
        GlyphId.QUESTION -> Icons.Filled.QuestionMark
    }

    val infiniteTransition = rememberInfiniteTransition(label = "critical_breathe")
    val glyphAlpha by if (isCritical) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glyph_breathe"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    val entranceKey = remember(event.soundId, event.capturedAtMs) {
        "${event.soundId}_${event.capturedAtMs}"
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(durationMillis = 120)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 24.dp, vertical = 36.dp)
                .testTag("alert_takeover_screen")
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Spacer for status bar clearance and top layout
                Spacer(modifier = Modifier.height(24.dp))

                // Main content: Icon, Tier Label (+ badge), and Caption
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = tierLabel,
                        tint = fgColor,
                        modifier = Modifier
                            .size(140.dp)
                            .alpha(glyphAlpha)
                            .testTag("alert_takeover_glyph")
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = tierLabel.uppercase(),
                            style = AlertLabel,
                            color = fgColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("alert_takeover_label")
                        )

                        if (event.repeatCount > 1) {
                            Surface(
                                color = if (isCritical) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .testTag("alert_repeat_badge")
                            ) {
                                Text(
                                    text = "×${event.repeatCount}",
                                    color = fgColor,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = caption,
                        style = AlertSub,
                        color = fgColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("alert_takeover_caption")
                    )
                }

                // Bottom wide dismiss bar
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCritical) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f),
                        contentColor = fgColor
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .testTag("alert_dismiss_button")
                ) {
                    Text(
                        text = strings.dismissLabel,
                        style = AlertSub,
                        fontWeight = FontWeight.Bold,
                        color = fgColor
                    )
                }
            }
        }
    }
}
