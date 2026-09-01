package com.nonamevpn.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nonamevpn.app.R

/** Inter (SIL OFL). */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val NvpnTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/** Light «Раф на кокосовом молоке» */
private val EspressoLight = lightColorScheme(
    primary = Color(0xFF6D4C41),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7CCC8),
    onPrimaryContainer = Color(0xFF3E2723),
    secondary = Color(0xFF8D6E63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFEBE9),
    onSecondaryContainer = Color(0xFF4E342E),
    tertiary = Color(0xFF795548),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCAAA4),
    onTertiaryContainer = Color(0xFF3E2723),
    background = Color(0xFFF2F0EC),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFAF8F4),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFEFEBE9),
    onSurfaceVariant = Color(0xFF5D4037),
    outline = Color(0xFFBCAAA4),
    outlineVariant = Color(0xFFD7CCC8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF322F2D),
    inverseOnSurface = Color(0xFFF5F0EB),
    inversePrimary = Color(0xFFD7CCC8),
    surfaceTint = Color(0xFF6D4C41),
)

/** Dark «Эспрессо» */
private val EspressoDark = darkColorScheme(
    primary = Color(0xFFD7CCC8),
    onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFF5D4037),
    onPrimaryContainer = Color(0xFFEFEBE9),
    secondary = Color(0xFFBCAAA4),
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFF4E342E),
    onSecondaryContainer = Color(0xFFEFEBE9),
    tertiary = Color(0xFFA1887F),
    onTertiary = Color(0xFF3E2723),
    tertiaryContainer = Color(0xFF5D4037),
    onTertiaryContainer = Color(0xFFEFEBE9),
    background = Color(0xFF1A1614),
    onBackground = Color(0xFFEDE0D4),
    surface = Color(0xFF211D1B),
    onSurface = Color(0xFFEDE0D4),
    surfaceVariant = Color(0xFF2C2624),
    onSurfaceVariant = Color(0xFFD7CCC8),
    outline = Color(0xFF8D6E63),
    outlineVariant = Color(0xFF4E342E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFEDE0D4),
    inverseOnSurface = Color(0xFF322F2D),
    inversePrimary = Color(0xFF6D4C41),
    surfaceTint = Color(0xFFD7CCC8),
)

object NvpnColors {
    val connected = Color(0xFF4CAF50)
    val warning = Color(0xFFFFA726)
    /** Direct path accent (green). */
    val pathDirect = Color(0xFF2E7D32)
    /** Bypass path accent (blue). */
    val pathBypass = Color(0xFF1565C0)
    val terminalBg = Color(0xFF1A1A2E)
    val terminalBgDark = Color(0xFF0D0D1A)
    val terminalText = Color(0xFFE0E0E0)
    val terminalGreen = Color(0xFF4CAF50)
    val terminalBlue = Color(0xFF42A5F5)
    val terminalRed = Color(0xFFEF5350)
    val terminalCounter = Color(0xFF1E88E5)
}

private fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.56f) Color(0xFF1C1B1A) else Color.White

fun wallpaperAdaptedColorScheme(
    base: ColorScheme,
    accent: Color,
    darkTheme: Boolean,
): ColorScheme {
    val primary = lerp(base.primary, accent, if (darkTheme) 0.62f else 0.68f)
    val secondary = lerp(base.secondary, accent, if (darkTheme) 0.35f else 0.40f)
    val tertiary = lerp(base.tertiary, accent, if (darkTheme) 0.30f else 0.36f)
    val primaryContainer = lerp(base.primaryContainer, accent, if (darkTheme) 0.30f else 0.26f)
    val secondaryContainer = lerp(base.secondaryContainer, accent, if (darkTheme) 0.22f else 0.24f)
    val tertiaryContainer = lerp(base.tertiaryContainer, accent, if (darkTheme) 0.20f else 0.22f)
    return base.copy(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onColorFor(primaryContainer),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onColorFor(secondaryContainer),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onColorFor(tertiaryContainer),
        surfaceTint = primary,
    )
}

@Composable
fun ArdttTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) EspressoDark else EspressoLight

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val navigationBarColor = if (darkTheme) {
                Color.Transparent
            } else {
                lerp(colorScheme.background, colorScheme.surface, 0.55f)
            }
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(colorScheme.background.toArgb()),
            )
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NvpnTypography,
        content = content,
    )
}
