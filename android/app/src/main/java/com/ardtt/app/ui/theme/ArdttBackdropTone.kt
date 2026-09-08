package com.ardtt.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * True while the illustrated user-mode wallpaper shows through tab content.
 *
 * Cards turn opaque so labels use the theme, not a forced light-on-photo color.
 */
val LocalIllustratedBackdrop = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun illustratedBackdropActive(): Boolean = LocalIllustratedBackdrop.current

/** Title / primary label. Chrome sits on an opaque scrim — use the scheme, not a forced light color. */
@Composable
@ReadOnlyComposable
fun backdropTitleColor(): Color =
    if (illustratedBackdropActive()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.primary
    }

/** Secondary label on chrome / cards. */
@Composable
@ReadOnlyComposable
fun backdropMutedTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Inactive segmented-chip fill when tabs sit on the wallpaper. */
@Composable
@ReadOnlyComposable
fun backdropSegmentInactiveContainer(): Color {
    if (!illustratedBackdropActive()) return Color.Transparent
    return cardContainerColor()
}

/** Inactive segmented-chip label when tabs sit on the wallpaper. */
@Composable
@ReadOnlyComposable
fun backdropSegmentInactiveContent(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant
