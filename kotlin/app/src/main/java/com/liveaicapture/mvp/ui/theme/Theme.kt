package com.liveaicapture.mvp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CamMateLightColors = lightColorScheme(
    primary = CamPrimary,
    onPrimary = CamSurface,
    secondary = CamTextSecondary,
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
