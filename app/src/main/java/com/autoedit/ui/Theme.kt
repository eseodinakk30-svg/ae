package com.autoedit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF9B5CFF)
val AccentHot = Color(0xFFFF2E7E)
val Surface1 = Color(0xFF15101F)
val Surface2 = Color(0xFF1E1730)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentHot,
    onSecondary = Color.White,
    background = Color(0xFF0B0710),
    onBackground = Color(0xFFEDE7F6),
    surface = Surface1,
    onSurface = Color(0xFFEDE7F6),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB9AFCC),
    outline = Color(0xFF3A2F52),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = AccentHot,
)

@Composable
fun AutoEditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else DarkColors,
        content = content,
    )
}
