package com.ardtt.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp

/**
 * Single source of truth for "is this surface dark?" and for the fills derived
 * from that answer.
 *
 * Before, four different rules coexisted — `luminance() < 0.22f` on the
 * background, `isSystemInDarkTheme()`, and two inline copies of the card
 * fill lerp — so a forced light theme on a dark system produced mismatched
 * chrome. Everything now routes through [isDark].
 */
object ArdttSurface {
    /** A background below this luminance is treated as dark chrome. */
    const val DarkThreshold = 0.22f

    /** A container above this luminance needs dark content on top. */
    const val LightContainerThreshold = 0.56f

    /** Content color over a light container. */
    val DarkContent = Color(0xFF1C1B1A)

    /** Content color over a dark container. */
    val LightContent = Color(0xFFF6FAFF)

    /** Secondary content over a light container. */
    val MutedDarkContent = Color(0xFF3A4A5F)

    /** Secondary content over a dark container. */
    val MutedLightContent = Color(0xFFD6E2F0)

    /** Secondary content over the illustrated wallpaper. */
    val SoftLightContent = Color(0xFFE5EDF8)

    /** Mix of `surface` into `surfaceVariant` used by every card fill. */
    private const val CardTintDark = 0.10f
    private const val CardTintLight = 0.28f

    /** Mix used by translucent floating chrome. */
    private const val ShellTintLight = 0.48f

    /** Opacity of translucent floating chrome. */
    const val ShellAlphaDark = 0.88f
    const val ShellAlphaLight = 0.96f

    fun isDark(background: Color): Boolean = background.luminance() < DarkThreshold

    fun contentColorOn(
        container: Color,
        darkContent: Color = DarkContent,
        lightContent: Color = Color.White,
    ): Color = if (container.luminance() > LightContainerThreshold) darkContent else lightContent

    fun mutedContentColorOn(container: Color): Color = contentColorOn(
        container = container,
        darkContent = MutedDarkContent,
        lightContent = MutedLightContent,
    )

    fun cardContainerColor(surface: Color, surfaceVariant: Color, dark: Boolean): Color =
        lerp(surface, surfaceVariant, if (dark) CardTintDark else CardTintLight)

    fun shellContainerColor(surface: Color, surfaceVariant: Color, dark: Boolean): Color =
        if (dark) {
            surface.copy(alpha = ShellAlphaDark)
        } else {
            lerp(surface, surfaceVariant, ShellTintLight).copy(alpha = ShellAlphaLight)
        }

    fun cardShadowElevation(dark: Boolean): Dp =
        if (dark) ArdttElevation.Low else ArdttElevation.Card
}

/** True when the active color scheme renders dark chrome. */
@Composable
@ReadOnlyComposable
fun isDarkSurface(): Boolean = ArdttSurface.isDark(MaterialTheme.colorScheme.background)

/** Default fill of a section / compact card under the active scheme. */
@Composable
@ReadOnlyComposable
fun cardContainerColor(): Color {
    val colors = MaterialTheme.colorScheme
    return ArdttSurface.cardContainerColor(
        surface = colors.surface,
        surfaceVariant = colors.surfaceVariant,
        dark = ArdttSurface.isDark(colors.background),
    )
}

/** Default shadow of a section / compact card under the active scheme. */
@Composable
@ReadOnlyComposable
fun cardShadowElevation(): Dp = ArdttSurface.cardShadowElevation(isDarkSurface())

/**
 * Fill of a selected segmented control — the tab-bar indicator and the selected
 * choice chip, which used to carry two copies of this formula.
 */
@Composable
@ReadOnlyComposable
fun selectedControlContainer(darkAlpha: Float = ArdttAlpha.Fill): Color {
    val colors = MaterialTheme.colorScheme
    return if (isDarkSurface()) {
        colors.primary.copy(alpha = darkAlpha)
    } else {
        lerp(colors.primaryContainer, colors.surface, ArdttAlpha.Fill)
            .copy(alpha = ArdttAlpha.Strong)
    }
}
