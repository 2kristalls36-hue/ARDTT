package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttSurface
import com.ardtt.app.ui.theme.backdropTitleColor
import com.ardtt.app.ui.theme.cardContainerColor
import com.ardtt.app.ui.theme.cardShadowElevation

/** Hairline along the rounded contour: 2.dp primary at 16%. */
object ArdttSectionCardDefaults {
    val ContourWidth: Dp = ArdttSize.Contour
    const val ContourAlpha = ArdttAlpha.Contour

    fun contourColor(primary: Color, alpha: Float = ContourAlpha): Color =
        primary.copy(alpha = alpha)

    fun contourBorder(
        primary: Color,
        alpha: Float = ContourAlpha,
        width: Dp = ContourWidth,
    ): BorderStroke = BorderStroke(width, contourColor(primary, alpha))
}

@Composable
fun sectionCardContourBorder(
    alpha: Float = ArdttSectionCardDefaults.ContourAlpha,
    width: Dp = ArdttSectionCardDefaults.ContourWidth,
): BorderStroke = ArdttSectionCardDefaults.contourBorder(
    primary = MaterialTheme.colorScheme.primary,
    alpha = alpha,
    width = width,
)

/**
 * The single card surface of the app.
 *
 * Fill, content color and shadow follow the active scheme through
 * [cardContainerColor] / [cardShadowElevation]; pass [color] only for a
 * semantic surface such as the terminal.
 */
@Composable
fun ArdttSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ArdttLayout.CardPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(ArdttLayout.CardSpacing),
    border: BorderStroke? = null,
    showBorder: Boolean = true,
    color: Color? = null,
    shadowElevation: Dp? = null,
    tonalElevation: Dp? = null,
    shape: Shape = ArdttShapes.Section,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColor = color ?: cardContainerColor()
    Surface(
        shape = shape,
        color = cardColor,
        contentColor = ArdttSurface.contentColorOn(
            container = cardColor,
            lightContent = ArdttSurface.LightContent,
        ),
        border = border ?: if (showBorder) sectionCardContourBorder() else null,
        shadowElevation = shadowElevation ?: cardShadowElevation(),
        tonalElevation = tonalElevation ?: ArdttElevation.None,
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

/**
 * [ArdttSectionCard] preset for a settings group: section title, helper
 * text and switch / chip rows. Settings, the tunnel «Параметры подключения»
 * card and the update card used to repeat the same two overrides.
 */
@Composable
fun ArdttSettingsCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    verticalArrangement: Arrangement.Vertical =
        Arrangement.spacedBy(ArdttLayout.SettingsCardSpacing),
    content: @Composable ColumnScope.() -> Unit,
) {
    ArdttSectionCard(
        modifier = modifier,
        contentPadding = ArdttLayout.SettingsCardPadding,
        verticalArrangement = verticalArrangement,
        border = border,
        content = content,
    )
}

/**
 * Denser [ArdttSectionCard] preset for identity rows: servers, clients,
 * profiles. Replaces the five-argument boilerplate those lists used to repeat.
 */
@Composable
fun ArdttCompactCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ArdttLayout.CompactCardPadding,
    verticalArrangement: Arrangement.Vertical =
        Arrangement.spacedBy(ArdttLayout.CompactCardSpacing),
    border: BorderStroke? = null,
    showBorder: Boolean = true,
    color: Color? = null,
    shadowElevation: Dp? = ArdttElevation.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    ArdttSectionCard(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        border = border,
        showBorder = showBorder,
        color = color,
        shadowElevation = shadowElevation,
        shape = ArdttShapes.Card,
        content = content,
    )
}

/** Squared leading glyph of a compact identity card. */
@Composable
fun ArdttLeadingIcon(
    painter: Painter,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ArdttShapes.Icon,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(ArdttSpacing.Small)
                .size(ArdttSize.IconCompact),
        )
    }
}

@Composable
fun ArdttLeadingIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    ArdttLeadingIcon(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/**
 * Heading of a block inside a card.
 *
 * Screens used to repeat `Text(..., titleMedium, FontWeight.SemiBold)` with
 * three different colors; [accent] picks the primary-tinted variant.
 */
@Composable
fun ArdttSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (accent) backdropTitleColor() else Color.Unspecified,
    )
}
