package com.soundsight.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundsight.ui.theme.AccentLive
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun MicLevelMeter(
    level: StateFlow<Float>,
    modifier: Modifier = Modifier
) {
    val currentLevel by level.collectAsStateWithLifecycle(initialValue = 0f)
    val barCount = 24

    val animatedHeights = (0 until barCount).map { i ->
        val centerNorm = (i - (barCount - 1) / 2f) / ((barCount - 1) / 2f)
        val curve = (1f - 0.4f * abs(centerNorm)).coerceIn(0.2f, 1f)
        val ripple = (0.85f + 0.15f * sin((i * 1.1).toDouble()).toFloat())
        val targetHeight = (currentLevel * curve * ripple).coerceIn(0.06f, 1f)

        animateFloatAsState(
            targetValue = targetHeight,
            animationSpec = tween(durationMillis = 80),
            label = "bar_$i"
        ).value
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mic_level_meter")
    ) {
        val totalWidth = size.width
        val maxHeight = size.height
        val barSpacing = totalWidth / (barCount * 2f - 1f)
        val barWidth = barSpacing
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (i in 0 until barCount) {
            val barHeight = (maxHeight * animatedHeights[i]).coerceAtLeast(barWidth)
            val left = i * (barWidth + barSpacing)
            val top = (maxHeight - barHeight) / 2f

            drawRoundRect(
                color = AccentLive,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}
