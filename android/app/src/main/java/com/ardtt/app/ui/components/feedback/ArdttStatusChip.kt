package com.ardtt.app.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/** Vertical padding of a badge; smaller than the spacing scale on purpose. */
private val BadgeVerticalPadding = 3.dp

/**
 * Tinted label carrying one piece of status: app version, expiry, OS, deploy
 * freshness. Server, client and profile lists each used to inline the same
 * `Surface(ArdttShapes.Badge, color.copy(alpha = 0.18f))` block.
 */
@Composable
fun ArdttStatusChip(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    fillAlpha: Float = ArdttAlpha.Fill,
) {
    Surface(
        modifier = modifier,
        shape = ArdttShapes.Badge,
        color = accent.copy(alpha = fillAlpha),
    ) {
        Text(
            text,
            modifier = Modifier.padding(
                horizontal = ArdttSpacing.Small,
                vertical = BadgeVerticalPadding,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

/**
 * Pill-shaped status label built from a container/content color pair, for
 * states that need a solid fill rather than a tint (recording, blocking).
 */
@Composable
fun ArdttStatusPill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ArdttShapes.Pill,
        color = container,
        contentColor = content,
    ) {
        Text(
            text,
            modifier = Modifier.padding(
                horizontal = ArdttSpacing.Medium,
                vertical = 7.dp,
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Solid presence dot: online client, active hop. */
@Composable
fun ArdttStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = ArdttSize.Dot,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(ArdttShapes.Pill)
            .background(color),
    )
}
