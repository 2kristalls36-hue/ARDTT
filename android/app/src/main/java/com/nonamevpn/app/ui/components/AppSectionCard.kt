package com.nonamevpn.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact list cards — Clients tab metrics, reused on Profiles. */
object CompactListCard {
    val ContentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    val ItemSpacing = 6.dp
    val ListSpacing = 8.dp
    val CornerRadius = 18.dp
    val Shape = RoundedCornerShape(CornerRadius)
    val ShadowElevation = 4.dp
    val IconCorner = 12.dp
}

/** Squared leading glyph used on compact identity cards (servers, profiles). */
@Composable
fun CompactListLeadingIcon(
    painter: Painter,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(CompactListCard.IconCorner),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(8.dp)
                .size(18.dp),
        )
    }
}

@Composable
fun CompactListLeadingIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    CompactListLeadingIcon(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/** Soft section card. */
val LocalOpaqueSectionCards = staticCompositionLocalOf { false }

/** User-mode illustrated wallpaper is visible behind tab content. */
@Composable
fun illustratedBackdropActive(): Boolean = LocalOpaqueSectionCards.current

/** Title / primary label over illustrated wallpaper. */
@Composable
fun backdropTitleColor(): Color =
    if (illustratedBackdropActive()) Color(0xFFF6FAFF) else MaterialTheme.colorScheme.primary

/** Secondary label over illustrated wallpaper. */
@Composable
fun backdropMutedTextColor(): Color =
    if (illustratedBackdropActive()) Color(0xFFD6E2F0) else MaterialTheme.colorScheme.onSurfaceVariant

/** Inactive segmented-chip background when tabs sit on wallpaper. */
@Composable
fun backdropSegmentInactiveContainer(): Color {
    if (!illustratedBackdropActive()) return Color.Transparent
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    return if (isDark) {
        lerp(colors.surface, colors.surfaceVariant, 0.10f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.28f)
    }
}

/** Inactive segmented-chip label when tabs sit on wallpaper. */
@Composable
fun backdropSegmentInactiveContent(): Color =
    if (illustratedBackdropActive()) Color(0xFFE5EDF8) else MaterialTheme.colorScheme.onSurfaceVariant

/** Soft section card. */
@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    border: BorderStroke? = null,
    showBorder: Boolean = true,
    color: Color? = null,
    shadowElevation: Dp? = null,
    tonalElevation: Dp? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val cardColor = color ?: if (isDark) {
        lerp(colors.surface, colors.surfaceVariant, 0.10f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.28f)
    }
    val contentColor = if (cardColor.luminance() > 0.56f) {
        Color(0xFF1C1B1A)
    } else {
        Color(0xFFF6FAFF)
    }
    val borderColor = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.26f)
    } else {
        colors.outlineVariant.copy(alpha = 0.24f)
    }

    Surface(
        shape = shape,
        color = cardColor,
        contentColor = contentColor,
        border = border ?: if (showBorder) BorderStroke(1.dp, borderColor) else null,
        shadowElevation = shadowElevation ?: if (isDark) 2.dp else 4.dp,
        tonalElevation = tonalElevation ?: 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
