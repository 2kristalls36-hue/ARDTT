package com.ardtt.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * True while the illustrated user-mode wallpaper shows through tab content.
 *
 * Cards turn opaque and labels switch to the fixed light palette below, because
 * scheme-derived colors are unreadable over an arbitrary photo.
 */
val LocalIllustratedBackdrop = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun illustratedBackdropActive(): Boolean = LocalIllustratedBackdrop.current

/** Title / primary label over the illustrated wallpaper. */
@Composable
@ReadOnlyComposable
fun backdropTitleColor(): Color =
    if (illustratedBackdropActive()) {
        ArdttSurface.LightContent
    } else {
        MaterialTheme.colorScheme.primary
    }

/** Secondary label over the illustrated wallpaper. */
@Composable
@ReadOnlyComposable
fun backdropMutedTextColor(): Color =
    if (illustratedBackdropActive()) {
        ArdttSurface.MutedLightContent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

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
    if (illustratedBackdropActive()) {
        ArdttSurface.SoftLightContent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
