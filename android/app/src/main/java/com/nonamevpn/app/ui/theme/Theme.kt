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

/** Light — soft sky-blue defaults. */
private val EspressoLight = lightColorScheme(
    primary = Color(0xFF4A90E2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0D3B66),
    secondary = Color(0xFF5E7FA6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7F1FF),
    onSecondaryContainer = Color(0xFF1A3654),
    tertiary = Color(0xFF5CA9E6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9EEFF),
    onTertiaryContainer = Color(0xFF123956),
    background = Color(0xFFF3F8FF),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFAFCFF),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFE8EFF8),
    onSurfaceVariant = Color(0xFF4C5E74),
    outline = Color(0xFFB2C2D7),
    outlineVariant = Color(0xFFD2DDEC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF232A33),
    inverseOnSurface = Color(0xFFE9F1FB),
    inversePrimary = Color(0xFFA8D0FF),
    surfaceTint = Color(0xFF4A90E2),
)

/** Dark — blue-toned contrast palette. */
private val EspressoDark = darkColorScheme(
    primary = Color(0xFFA8D0FF),
    onPrimary = Color(0xFF0B355D),
    primaryContainer = Color(0xFF1B4A75),
    onPrimaryContainer = Color(0xFFD9EBFF),
    secondary = Color(0xFFB5CBE6),
    onSecondary = Color(0xFF223955),
    secondaryContainer = Color(0xFF334A67),
    onSecondaryContainer = Color(0xFFE2EDFB),
    tertiary = Color(0xFFA8DFFF),
    onTertiary = Color(0xFF153450),
    tertiaryContainer = Color(0xFF24506E),
    onTertiaryContainer = Color(0xFFD8F0FF),
    background = Color(0xFF0F1722),
    onBackground = Color(0xFFE5EDF8),
    surface = Color(0xFF16202C),
    onSurface = Color(0xFFE5EDF8),
    surfaceVariant = Color(0xFF233141),
    onSurfaceVariant = Color(0xFFB9C9DB),
    outline = Color(0xFF7F95AF),
    outlineVariant = Color(0xFF374B63),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE5EDF8),
    inverseOnSurface = Color(0xFF1A2431),
    inversePrimary = Color(0xFF4A90E2),
    surfaceTint = Color(0xFFA8D0FF),
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

private fun mutedOnColorFor(background: Color): Color =
    if (background.luminance() > 0.56f) Color(0xFF3A4A5F) else Color(0xFFD6E2F0)

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
    val surfaceTint = lerp(primary, accent, 0.45f)
    val surface = lerp(base.surface, surfaceTint, if (darkTheme) 0.22f else 0.18f)
    val surfaceVariant = lerp(base.surfaceVariant, surfaceTint, if (darkTheme) 0.30f else 0.26f)
    val background = lerp(base.background, surfaceTint, if (darkTheme) 0.18f else 0.14f)
    val outline = lerp(base.outline, surfaceTint, if (darkTheme) 0.16f else 0.14f)
    val outlineVariant = lerp(base.outlineVariant, surfaceTint, if (darkTheme) 0.22f else 0.18f)
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
        surface = surface,
        onSurface = onColorFor(surface),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = mutedOnColorFor(surfaceVariant),
        background = background,
        onBackground = onColorFor(background),
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceTint = surfaceTint,
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
