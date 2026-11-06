package com.soundsight.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Standard uniform illustration height across all onboarding widgets.
 */
val OnboardingIllustrationHeight = 210.dp

/**
 * Language Selection: Static real Earth icon.
 */
@Composable
fun LanguageGlobeIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .size(108.dp)
            .background(
                color = scheme.primaryContainer.copy(alpha = 0.65f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(68.dp)
        )
    }
}

/**
 * Slide 1: Listening to Traffic.
 * Horizontal phone in the middle showing the microphone, with sound waves directed towards it.
 */
@Composable
fun TrafficListeningIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        modifier = modifier
            .fillMaxWidth()
            .height(OnboardingIllustrationHeight)
            .clip(MaterialTheme.shapes.extraLarge)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                val phoneW = 116.dp.toPx()
                val phoneH = 68.dp.toPx()

                // Sound waves on the LEFT traveling toward the phone
                val leftWaveCenter = Offset(cx - phoneW / 2f - 12.dp.toPx(), cy)
                val leftRadii = listOf(22.dp.toPx(), 44.dp.toPx(), 66.dp.toPx())
                val leftAlphas = listOf(0.85f, 0.55f, 0.25f)
                for (i in leftRadii.indices) {
                    val r = leftRadii[i]
                    drawArc(
                        color = scheme.primary.copy(alpha = leftAlphas[i]),
                        startAngle = 120f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(leftWaveCenter.x - r, leftWaveCenter.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Sound waves on the RIGHT traveling toward the phone
                val rightWaveCenter = Offset(cx + phoneW / 2f + 12.dp.toPx(), cy)
                val rightRadii = listOf(22.dp.toPx(), 44.dp.toPx(), 66.dp.toPx())
                val rightAlphas = listOf(0.85f, 0.55f, 0.25f)
                for (i in rightRadii.indices) {
                    val r = rightRadii[i]
                    drawArc(
                        color = scheme.secondary.copy(alpha = rightAlphas[i]),
                        startAngle = -60f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(rightWaveCenter.x - r, rightWaveCenter.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Horizontal Phone Body in the middle
                val phoneRect = RoundRect(
                    Rect(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f),
                    CornerRadius(14.dp.toPx())
                )
                val phonePath = Path().apply { addRoundRect(phoneRect) }

                // Phone background and border
                drawPath(phonePath, color = scheme.surface)
                drawPath(phonePath, color = scheme.primary, style = Stroke(width = 2.5.dp.toPx()))

                // Camera lens dot on phone edge
                drawCircle(
                    color = scheme.outlineVariant,
                    radius = 3.dp.toPx(),
                    center = Offset(cx - phoneW / 2f + 8.dp.toPx(), cy)
                )

                // Phone Screen inner glow container for mic
                drawCircle(
                    color = scheme.primaryContainer.copy(alpha = 0.65f),
                    radius = 22.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }

            // Microphone icon centered on horizontal phone screen
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * Slide 2: Audio Data & Privacy (Frozen spectrogram to Cloud with security badge, Material You colors).
 */
@Composable
fun AudioAiPrivacyIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        modifier = modifier
            .fillMaxWidth()
            .height(OnboardingIllustrationHeight)
            .clip(MaterialTheme.shapes.extraLarge)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            // Left: Audio Spectrogram Bars (Material You primary / secondary)
            val barCount = 7
            val startX = cx - 110.dp.toPx()
            val staticBarHeights = listOf(22.dp, 36.dp, 48.dp, 32.dp, 44.dp, 28.dp, 18.dp)
            for (i in 0 until barCount) {
                val x = startX + i * 12.dp.toPx()
                val barH = staticBarHeights[i].toPx()
                drawRoundRect(
                    color = if (i % 2 == 0) scheme.primary else scheme.secondary,
                    topLeft = Offset(x, cy - barH / 2f),
                    size = Size(6.dp.toPx(), barH),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }

            // Center Data Stream Arrow / Particle Flow (Material You tertiary)
            val streamStartX = cx - 18.dp.toPx()
            val streamEndX = cx + 26.dp.toPx()
            val streamDots = listOf(0.15f, 0.5f, 0.85f)
            for (p in streamDots) {
                val dotX = streamStartX + (streamEndX - streamStartX) * p
                drawCircle(
                    color = scheme.tertiary.copy(alpha = 0.8f),
                    radius = 3.5.dp.toPx(),
                    center = Offset(dotX, cy)
                )
            }

            // Right: Cloud Icon with Privacy Security Lock (Material You colors)
            val cloudCenter = Offset(cx + 80.dp.toPx(), cy)
            val cloudBgR = 38.dp.toPx()

            // Cloud Container Glow
            drawCircle(
                color = scheme.primaryContainer.copy(alpha = 0.65f),
                radius = cloudBgR,
                center = cloudCenter
            )

            // Cloud Silhouette Path
            val cloudW = 46.dp.toPx()
            val cloudH = 30.dp.toPx()
            val left = cloudCenter.x - cloudW / 2f
            val right = cloudCenter.x + cloudW / 2f
            val base = cloudCenter.y + 10.dp.toPx()
            val top = cloudCenter.y - 14.dp.toPx()

            val cloudPath = Path().apply {
                moveTo(left + 7.dp.toPx(), base)
                lineTo(right - 7.dp.toPx(), base)
                arcTo(
                    Rect(right - 14.dp.toPx(), base - 14.dp.toPx(), right, base),
                    90f,
                    -180f,
                    false
                )
                arcTo(
                    Rect(cloudCenter.x - 2.dp.toPx(), top, right - 2.dp.toPx(), top + 22.dp.toPx()),
                    0f,
                    -140f,
                    false
                )
                arcTo(
                    Rect(left + 2.dp.toPx(), top + 2.dp.toPx(), cloudCenter.x + 2.dp.toPx(), top + 22.dp.toPx()),
                    -40f,
                    -150f,
                    false
                )
                arcTo(
                    Rect(left, base - 14.dp.toPx(), left + 14.dp.toPx(), base),
                    190f,
                    -120f,
                    false
                )
                close()
            }
            drawPath(path = cloudPath, color = scheme.primary)

            // Security Lock Badge attached to Cloud (Material You secondary/surface)
            val lockPos = Offset(cloudCenter.x + 22.dp.toPx(), cloudCenter.y - 18.dp.toPx())
            drawCircle(color = scheme.secondary, radius = 8.dp.toPx(), center = lockPos)
            drawCircle(color = scheme.surface, radius = 4.dp.toPx(), center = lockPos)
        }
    }
}

/**
 * Slide 3: Limitations & Safety (Frozen radar and shield, Material You colors).
 */
@Composable
fun SafetyAwarenessIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        modifier = modifier
            .fillMaxWidth()
            .height(OnboardingIllustrationHeight)
            .clip(MaterialTheme.shapes.extraLarge)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxRadarR = 85.dp.toPx()

            // Concentric radar grid rings (Material You outlineVariant)
            for (i in 1..3) {
                val r = maxRadarR * (i / 3f)
                drawCircle(
                    color = scheme.outlineVariant.copy(alpha = 0.35f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Crosshairs
            drawLine(
                color = scheme.outlineVariant.copy(alpha = 0.3f),
                start = Offset(cx - maxRadarR, cy),
                end = Offset(cx + maxRadarR, cy),
                strokeWidth = 1.5.dp.toPx()
            )
            drawLine(
                color = scheme.outlineVariant.copy(alpha = 0.3f),
                start = Offset(cx, cy - maxRadarR),
                end = Offset(cx, cy + maxRadarR),
                strokeWidth = 1.5.dp.toPx()
            )

            // Static radar awareness field arc (Material You primary)
            drawArc(
                color = scheme.primary.copy(alpha = 0.22f),
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(cx - maxRadarR, cy - maxRadarR),
                size = Size(maxRadarR * 2f, maxRadarR * 2f)
            )

            // Awareness beacon point (Material You secondary)
            val beaconOffset = Offset(cx + 46.dp.toPx(), cy - 40.dp.toPx())
            drawCircle(color = scheme.secondary, radius = 6.dp.toPx(), center = beaconOffset)
            drawCircle(
                color = scheme.secondary.copy(alpha = 0.35f),
                radius = 12.dp.toPx(),
                center = beaconOffset,
                style = Stroke(width = 2.dp.toPx())
            )

            // Central Rider Safety Shield (Material You primaryContainer + primary)
            drawCircle(
                color = scheme.primaryContainer,
                radius = 26.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = scheme.primary,
                radius = 26.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )

            // Shield silhouette in center
            val sPath = Path().apply {
                moveTo(cx, cy - 13.dp.toPx())
                lineTo(cx + 11.dp.toPx(), cy - 7.dp.toPx())
                lineTo(cx + 9.dp.toPx(), cy + 7.dp.toPx())
                lineTo(cx, cy + 13.dp.toPx())
                lineTo(cx - 9.dp.toPx(), cy + 7.dp.toPx())
                lineTo(cx - 11.dp.toPx(), cy - 7.dp.toPx())
                close()
            }
            drawPath(path = sPath, color = scheme.primary)
        }
    }
}
