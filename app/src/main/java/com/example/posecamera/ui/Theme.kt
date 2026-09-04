package com.example.posecamera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PoseColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF171717),
    onSurface = Color.White,
)

@Composable
fun PoseCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PoseColors, content = content)
}
