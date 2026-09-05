package com.ardtt.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Semantic colors that Material's [ColorScheme] has no slot for.
 *
 * Anything role-based (surfaces, outlines, error) must come from
 * `MaterialTheme.colorScheme`; only product meanings live here.
 */
object ArdttColors {
    /** Healthy tunnel, online client, fresh deploy. */
    val Connected = Color(0xFF4CAF50)

    /** Degraded but working: stale version, slow ping. */
    val Warning = Color(0xFFFFA726)

    /** Direct path accent (AmneziaWG over UDP). */
    val PathDirect = Color(0xFF2E7D32)

    /** Bypass path accent (RAW Dial via TURN). */
    val PathBypass = Color(0xFF1565C0)

    val TerminalBg = Color(0xFF1A1A2E)
    val TerminalBgDark = Color(0xFF0D0D1A)
    val TerminalText = Color(0xFFE0E0E0)
    val TerminalGreen = Color(0xFF4CAF50)
    val TerminalBlue = Color(0xFF42A5F5)
    val TerminalRed = Color(0xFFEF5350)
    val TerminalCounter = Color(0xFF1E88E5)
}

/** Light — soft sky-blue defaults. */
val ArdttLightColorScheme = lightColorScheme(
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
val ArdttDarkColorScheme = darkColorScheme(
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

/** Blend factors used when the illustrated wallpaper drives the palette. */
private object WallpaperBlend {
    fun primary(dark: Boolean) = if (dark) 0.62f else 0.68f
    fun secondary(dark: Boolean) = if (dark) 0.35f else 0.40f
    fun tertiary(dark: Boolean) = if (dark) 0.30f else 0.36f
    fun primaryContainer(dark: Boolean) = if (dark) 0.30f else 0.26f
    fun secondaryContainer(dark: Boolean) = if (dark) 0.22f else 0.24f
    fun tertiaryContainer(dark: Boolean) = if (dark) 0.20f else 0.22f
    fun surface(dark: Boolean) = if (dark) 0.22f else 0.18f
    fun surfaceVariant(dark: Boolean) = if (dark) 0.30f else 0.26f
    fun background(dark: Boolean) = if (dark) 0.18f else 0.14f
    fun outline(dark: Boolean) = if (dark) 0.16f else 0.14f
    fun outlineVariant(dark: Boolean) = if (dark) 0.22f else 0.18f
    const val SurfaceTint = 0.45f
}

/** Pull the wallpaper accent through every tinted role of [base]. */
fun wallpaperAdaptedColorScheme(
    base: ColorScheme,
    accent: Color,
    darkTheme: Boolean,
): ColorScheme {
    val primary = lerp(base.primary, accent, WallpaperBlend.primary(darkTheme))
    val secondary = lerp(base.secondary, accent, WallpaperBlend.secondary(darkTheme))
    val tertiary = lerp(base.tertiary, accent, WallpaperBlend.tertiary(darkTheme))
    val primaryContainer =
        lerp(base.primaryContainer, accent, WallpaperBlend.primaryContainer(darkTheme))
    val secondaryContainer =
        lerp(base.secondaryContainer, accent, WallpaperBlend.secondaryContainer(darkTheme))
    val tertiaryContainer =
        lerp(base.tertiaryContainer, accent, WallpaperBlend.tertiaryContainer(darkTheme))
    val surfaceTint = lerp(primary, accent, WallpaperBlend.SurfaceTint)
    val surface = lerp(base.surface, surfaceTint, WallpaperBlend.surface(darkTheme))
    val surfaceVariant =
        lerp(base.surfaceVariant, surfaceTint, WallpaperBlend.surfaceVariant(darkTheme))
    val background = lerp(base.background, surfaceTint, WallpaperBlend.background(darkTheme))
    val outline = lerp(base.outline, surfaceTint, WallpaperBlend.outline(darkTheme))
    val outlineVariant =
        lerp(base.outlineVariant, surfaceTint, WallpaperBlend.outlineVariant(darkTheme))
    return base.copy(
        primary = primary,
        onPrimary = ArdttSurface.contentColorOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = ArdttSurface.contentColorOn(primaryContainer),
        secondary = secondary,
        onSecondary = ArdttSurface.contentColorOn(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = ArdttSurface.contentColorOn(secondaryContainer),
        tertiary = tertiary,
        onTertiary = ArdttSurface.contentColorOn(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = ArdttSurface.contentColorOn(tertiaryContainer),
        surface = surface,
        onSurface = ArdttSurface.contentColorOn(surface),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = ArdttSurface.mutedContentColorOn(surfaceVariant),
        background = background,
        onBackground = ArdttSurface.contentColorOn(background),
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceTint = surfaceTint,
    )
}
