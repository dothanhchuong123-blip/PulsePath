package com.soundsight.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.theme.AlertLabel
import com.soundsight.ui.theme.PulsePathTheme
import com.soundsight.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * High-fidelity PulsePath App Icon representation without surrounding container.
 *
 * Renders the 3 sound wave bars (Low, Mid, High) and the forward path chevron emblem
 * based on the canonical geometry from the launcher icon vector, cleanly floating
 * on the background.
 */
@Composable
fun PulsePathLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    animated: Boolean = true,
    barColor: Color = MaterialTheme.colorScheme.primary,
    chevronColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_pulse")
    val pulseScale by if (animated) {
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(pulseScale)
            .testTag("splash_logo"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val scaleFactor = this.size.minDimension / 108f

            // Vector viewport 108x108 stroke widths, scaled slightly for standout visibility
            val barStroke = 6.8f * scaleFactor
            val chevronStroke = 7.4f * scaleFactor

            // Scale artwork about center (55.68, 54.0) so it comfortably fills the footprint
            scale(scale = 1.25f, pivot = Offset(55.68f * scaleFactor, 54f * scaleFactor)) {
                // Bar 1: Low
                drawLine(
                    color = barColor,
                    start = Offset(36.08f * scaleFactor, 48.4f * scaleFactor),
                    end = Offset(36.08f * scaleFactor, 59.6f * scaleFactor),
                    strokeWidth = barStroke,
                    cap = StrokeCap.Round
                )

                // Bar 2: Mid
                drawLine(
                    color = barColor,
                    start = Offset(45.6f * scaleFactor, 41.68f * scaleFactor),
                    end = Offset(45.6f * scaleFactor, 66.32f * scaleFactor),
                    strokeWidth = barStroke,
                    cap = StrokeCap.Round
                )

                // Bar 3: High
                drawLine(
                    color = barColor,
                    start = Offset(55.12f * scaleFactor, 36.08f * scaleFactor),
                    end = Offset(55.12f * scaleFactor, 71.92f * scaleFactor),
                    strokeWidth = barStroke,
                    cap = StrokeCap.Round
                )

                // Path Arrow / Chevron
                val chevronPath = Path().apply {
                    moveTo(65.2f * scaleFactor, 39.44f * scaleFactor)
                    lineTo(75.28f * scaleFactor, 54.0f * scaleFactor)
                    lineTo(65.2f * scaleFactor, 68.56f * scaleFactor)
                }
                drawPath(
                    path = chevronPath,
                    color = chevronColor,
                    style = Stroke(
                        width = chevronStroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * PulsePath Splash Screen.
 *
 * Displays:
 *  - The App Icon ([PulsePathLogo])
 *  - App Name: "PulsePath"
 *  - Quote: "Feel the pulse. Own the path"
 *
 * Supports both Light and Dark mode seamlessly via [MaterialTheme.colorScheme].
 *
 * @param modifier modifier for root layout
 * @param durationMillis duration in milliseconds before invoking [onSplashFinished] (default 2000ms)
 * @param onSplashFinished optional callback invoked when the splash duration finishes
 */
@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    durationMillis: Long = 2000L,
    onSplashFinished: (() -> Unit)? = null
) {
    val strings = SoundSightStrings.current
    val appName = strings.appName
    val quote = strings.splashQuote

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.9f) }
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        }
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
        }
        launch {
            contentAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        }
        if (onSplashFinished != null) {
            delay(durationMillis)
            onSplashFinished()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("splash_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                // App Icon (cleanly floating, no bounding box)
                Box(
                    modifier = Modifier
                        .alpha(logoAlpha.value)
                        .scale(logoScale.value)
                ) {
                    PulsePathLogo(
                        size = 120.dp,
                        animated = true
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // App Name
                Text(
                    text = appName,
                    style = AlertLabel.copy(
                        fontSize = 38.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(contentAlpha.value)
                        .testTag("splash_app_name")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quote
                Text(
                    text = quote,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Italic,
                        letterSpacing = 0.4.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .alpha(contentAlpha.value)
                        .testTag("splash_quote")
                )
            }
        }
    }
}

@Preview(name = "Splash Screen - Dark Mode", showBackground = true)
@Composable
private fun SplashScreenDarkPreview() {
    PulsePathTheme(themeMode = ThemeMode.DARK) {
        SplashScreen()
    }
}

@Preview(name = "Splash Screen - Light Mode", showBackground = true)
@Composable
private fun SplashScreenLightPreview() {
    PulsePathTheme(themeMode = ThemeMode.LIGHT) {
        SplashScreen()
    }
}
