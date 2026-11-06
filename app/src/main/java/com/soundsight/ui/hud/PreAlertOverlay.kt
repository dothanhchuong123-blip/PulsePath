package com.soundsight.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.Motion

/**
 * Visual tuning constants for the Tier-1 pre-alert overlay.
 *
 * Extracted as named constants so the perceptual intensity, attack/decay timing,
 * and display framing are directly reviewable and unit-testable.
 */
object PreAlertVisuals {
    /**
     * Duration in milliseconds the pre-alert overlay remains on screen before
     * transitioning back to IDLE (if not escalated to a full [com.soundsight.domain.Tier] alert).
     */
    const val DURATION_MS: Long = 400L

    /**
     * Entrance animation duration in milliseconds.
     * Fast attack (120ms) so the visual flash matches the ~50–70 ms acoustic onset.
     */
    const val ENTER_DURATION_MS: Int = Motion.DurationAlertIn

    /**
     * Exit animation decay duration in milliseconds.
     * Fast decay (250ms) so the wash promptly clears without lingering if no classified hazard follows.
     */
    const val EXIT_DURATION_MS: Int = Motion.DurationAlertOut

    /**
     * Full-screen wash tint alpha.
     *
     * A rider in motion is looking at the road, not directly at a bar-mounted phone.
     * A full-surface colour wash is detectable in peripheral vision under daylight,
     * while staying well below the intensity of a full [AlertTakeover] so the HUD
     * layout remains legible underneath and escalation is preserved.
     */
    const val WASH_ALPHA: Float = 0.24f

    /**
     * Perimeter border stroke thickness framing the display.
     */
    val BORDER_WIDTH: Dp = 24.dp

    /**
     * Perimeter border alpha. Provides a sharp, high-contrast edge cue along the display perimeter.
     */
    const val BORDER_ALPHA: Float = 0.72f
}

/**
 * Fast-path Tier-1 pre-alert overlay.
 *
 * Renders an immediate full-screen wash and bold perimeter frame when a loud acoustic spike
 * is detected (~50–70 ms from onset), giving the rider an instantaneous peripheral cue before
 * local YAMNet or cloud classification finishes.
 */
@Composable
fun PreAlertOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = LocalHazardColors.current.Warning
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = PreAlertVisuals.ENTER_DURATION_MS,
                easing = Motion.EmphasizedDecelerate
            )
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = PreAlertVisuals.EXIT_DURATION_MS
            )
        ),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color.copy(alpha = PreAlertVisuals.WASH_ALPHA))
                .border(
                    width = PreAlertVisuals.BORDER_WIDTH,
                    color = color.copy(alpha = PreAlertVisuals.BORDER_ALPHA)
                )
                .testTag("hud_pre_alert_overlay")
        )
    }
}
