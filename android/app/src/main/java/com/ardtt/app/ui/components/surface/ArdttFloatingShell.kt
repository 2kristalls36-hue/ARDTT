package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

    /** Tinted floating fill for semantic sticky buttons (connect / stop). */
    @Composable
    @ReadOnlyComposable
    fun tintedShell(tint: Color, mix: Float = 0.34f): Color =
        lerp(shellColor(), tint.copy(alpha = 1f), mix.coerceIn(0f, 1f))

    val shadowElevation: Dp
        @Composable @ReadOnlyComposable
        get() = if (isDarkSurface()) ArdttElevation.FloatingDark else ArdttElevation.Floating
}
