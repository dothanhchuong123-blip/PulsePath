package com.soundsight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentLive,
    onPrimary = BgIdle,
    primaryContainer = AccentLive.copy(alpha = 0.2f),
    onPrimaryContainer = AccentLive,
    secondary = WarningBg,
    onSecondary = WarningFg,
    error = CriticalBg,
    onError = CriticalFg,
    background = BgIdle,
    onBackground = FgIdle,
    surface = BgIdle,
    onSurface = FgIdle,
    surfaceVariant = Color(0xFF161E27),
    onSurfaceVariant = Degraded,
    outline = Degraded
)

@Composable
fun SoundSightTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
