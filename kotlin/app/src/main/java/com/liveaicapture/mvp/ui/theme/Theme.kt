package com.liveaicapture.mvp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CamMateLightColors = lightColorScheme(
    primary = CamPrimary,
    onPrimary = Color(0xFF1E1A14),
    secondary = CamTextSecondary,
    onSecondary = CamText,
    background = CamBg,
    onBackground = CamText,
    surface = CamSurface,
    onSurface = CamText,
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
