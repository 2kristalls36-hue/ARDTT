package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSurface
import com.ardtt.app.ui.theme.isDarkSurface

/**
 * Translucent shell for floating bottom chrome (tab pill, sticky CTAs, search).
 * Slightly more transparent than a card so content reads underneath.
 */
object ArdttFloatingShell {
    /** Border alpha over dark chrome. */
    private const val BorderAlphaDark = 0.42f

    @Composable
    @ReadOnlyComposable
    fun shellColor(): Color {
        val colors = MaterialTheme.colorScheme
        return ArdttSurface.shellContainerColor(
            surface = colors.surface,
            surfaceVariant = colors.surfaceVariant,
            dark = isDarkSurface(),
        )
    }

    @Composable
    @ReadOnlyComposable
    fun shellBorder(): BorderStroke {
        val colors = MaterialTheme.colorScheme
        val stroke = if (isDarkSurface()) {
            colors.outlineVariant.copy(alpha = BorderAlphaDark)
        } else {
            colors.outline.copy(alpha = ArdttAlpha.Contour)
        }
        return BorderStroke(ArdttSize.Border, stroke)
    }

    /**
     * Glass sticky buttons keep the semantic hue (primary, stop, warning).
     * Mixing that hue into the shell turned red brown. Opacity is the same
     * in light and dark themes.
     */
    const val ButtonAlpha = 0.80f

    /** Semantic fill at [ButtonAlpha]. An incoming alpha is ignored. */
    fun tintedShell(tint: Color): Color = tint.copy(alpha = ButtonAlpha)

    /**
     * Opaque fill that reads as [tintedShell] sitting on [behind].
     * Solid primary and danger buttons use this so they match the glass CTAs.
     */
    fun opaqueGlassFill(tint: Color, behind: Color): Color =
        ArdttSurface.compositeOver(tintedShell(tint), behind.copy(alpha = 1f))

    val shadowElevation: Dp
        @Composable @ReadOnlyComposable
        get() = if (isDarkSurface()) ArdttElevation.FloatingDark else ArdttElevation.Floating
}
