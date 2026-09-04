package com.ardtt.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared translucent shell for floating bottom chrome (tab pill, sticky CTAs).
 * Slightly more transparent than opaque cards so content reads underneath.
 */
object ArdttFloatingShell {
    const val DarkShellAlpha = 0.88f
    const val LightShellAlpha = 0.96f

    @Composable
    fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.22f

    @Composable
    fun shellColor(): Color {
        val colors = MaterialTheme.colorScheme
        return if (isDarkTheme()) {
            colors.surface.copy(alpha = DarkShellAlpha)
        } else {
            lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = LightShellAlpha)
        }
    }

    @Composable
    fun shellBorder(): BorderStroke {
        val colors = MaterialTheme.colorScheme
        val stroke = if (isDarkTheme()) {
            colors.outlineVariant.copy(alpha = 0.42f)
        } else {
            colors.outline.copy(alpha = 0.16f)
        }
        return BorderStroke(1.dp, stroke)
    }

    /** Tinted floating fill for semantic sticky buttons (connect / stop). */
    @Composable
    fun tintedShell(tint: Color, mix: Float = 0.34f): Color =
        lerp(shellColor(), tint.copy(alpha = 1f), mix.coerceIn(0f, 1f))

    val shadowElevation: Dp
        @Composable get() = if (isDarkTheme()) 10.dp else 8.dp
}
