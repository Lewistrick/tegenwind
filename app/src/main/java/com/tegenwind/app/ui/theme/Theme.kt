package com.tegenwind.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark: high contrast on the handlebar and easy on the battery. A bright "sun" theme comes later.
private val NightScheme = darkColorScheme(
    primary = Amber,
    onPrimary = AmberInk,
    secondary = SpeedColor,
    tertiary = HeartColor,
    background = Asphalt,
    onBackground = Ink,
    surface = Asphalt,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Asphalt,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface1,
    surfaceContainerHighest = Surface1,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
)

@Composable
fun TegenwindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightScheme,
        typography = Typography,
        content = content,
    )
}
