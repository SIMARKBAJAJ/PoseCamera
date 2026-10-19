package com.example.posecamera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.example.posecamera.ui.design.SahiColors

private val PoseColors = darkColorScheme(
    primary = SahiColors.Haldi,
    onPrimary = SahiColors.Ink,
    background = SahiColors.Ink,
    onBackground = SahiColors.Chalk,
    surface = SahiColors.Slate,
    onSurface = SahiColors.Chalk,
    surfaceVariant = SahiColors.Slate2,
    onSurfaceVariant = SahiColors.Dust,
    outline = SahiColors.Hairline,
    error = SahiColors.Sindoor,
    secondary = SahiColors.Neel,
    tertiary = SahiColors.Mehendi,
)

@Composable
fun PoseCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PoseColors, content = content)
}
