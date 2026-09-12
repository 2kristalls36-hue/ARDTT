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

/** Light navigation bars sit between background and surface. */
private const val NAV_BAR_TINT = 0.55f

/**
 * Base theme: light / dark scheme by [themeMode] plus system bars.
 *
 * Wallpaper-driven tinting is not done here — `AppRoot` applies
 * [wallpaperAdaptedColorScheme] on top of this scheme, so there is exactly one
 * accent-blend formula in the app.
 */
@Composable
fun ArdttTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = themeModeIsDark(themeMode, isSystemInDarkTheme())
    val colorScheme = if (darkTheme) ArdttDarkColorScheme else ArdttLightColorScheme

    ApplySystemBars(colorScheme = colorScheme, darkTheme = darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ArdttTypography,
        content = content,
    )
}

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
