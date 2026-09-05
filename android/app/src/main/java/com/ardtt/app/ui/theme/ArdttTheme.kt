package com.ardtt.app.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ardtt.app.ui.themeModeIsDark

/** How strongly the sampled wallpaper color tints the base palette. */
private const val WALLPAPER_TINT = 0.4f

/** Light navigation bars sit between background and surface. */
private const val NAV_BAR_TINT = 0.55f

@Composable
fun ArdttTheme(
    themeMode: String = "system",
    wallpaperAvgColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeModeIsDark(themeMode, isSystemInDarkTheme())
    val baseScheme = if (darkTheme) ArdttDarkColorScheme else ArdttLightColorScheme
    val colorScheme = wallpaperAvgColor?.let { baseScheme.tintedBy(it) } ?: baseScheme

    ApplySystemBars(colorScheme = colorScheme, darkTheme = darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ArdttTypography,
        content = content,
    )
}

/** Soft accent pass so chrome picks up the wallpaper without losing contrast. */
private fun ColorScheme.tintedBy(accent: Color): ColorScheme = copy(
    primary = lerp(primary, accent, WALLPAPER_TINT),
    primaryContainer = lerp(primaryContainer, accent, WALLPAPER_TINT),
    secondary = lerp(secondary, accent, WALLPAPER_TINT * 0.8f),
    secondaryContainer = lerp(secondaryContainer, accent, WALLPAPER_TINT * 0.8f),
    tertiary = lerp(tertiary, accent, WALLPAPER_TINT * 0.6f),
    tertiaryContainer = lerp(tertiaryContainer, accent, WALLPAPER_TINT * 0.6f),
    surfaceTint = lerp(surfaceTint, accent, WALLPAPER_TINT),
)

@Composable
private fun ApplySystemBars(colorScheme: ColorScheme, darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        val navigationBarColor = if (darkTheme) {
            Color.Transparent
        } else {
            lerp(colorScheme.background, colorScheme.surface, NAV_BAR_TINT)
        }
        window.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb()))
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = navigationBarColor.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
