package com.liveaicapture.mvp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Sky300,
    secondary = Slate100,
    tertiary = Coral300,
    background = Ocean900,
    surface = Ocean700,
)

private val ColorWhite = androidx.compose.ui.graphics.Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Ocean500,
    secondary = Ocean700,
    tertiary = Coral300,
    background = Ice100,
    surface = ColorWhite,
)

@Composable
fun LiveAICaptureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
