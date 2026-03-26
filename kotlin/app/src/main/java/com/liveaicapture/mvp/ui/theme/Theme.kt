package com.liveaicapture.mvp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CamMateLightColors = lightColorScheme(
    primary = CamPrimary,
    onPrimary = Color.White,
    secondary = Color(0xFF536A7A),
    onSecondary = Color.White,
    background = CamBg,
    onBackground = CamText,
    surface = CamSurface,
    onSurface = CamText,
    surfaceVariant = CamSurfaceSoft,
    onSurfaceVariant = CamTextSecondary,
    outline = CamBorder,
    error = CamDanger,
)

@Composable
fun CamMateTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CamMateLightColors,
        typography = Typography,
        content = content,
    )
}
