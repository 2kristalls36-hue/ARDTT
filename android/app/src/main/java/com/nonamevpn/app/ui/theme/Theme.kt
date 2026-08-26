package com.nonamevpn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1B6CA8)
private val Deep = Color(0xFF0B1F33)
private val Mist = Color(0xFFE8F3FA)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Deep,
    background = Mist,
    surface = Color.White,
    onBackground = Deep,
    onSurface = Deep,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6BB3E0),
    onPrimary = Deep,
    secondary = Mist,
    background = Deep,
    surface = Color(0xFF132A40),
    onBackground = Mist,
    onSurface = Mist,
)

@Composable
fun NonameTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
