package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.theme.ArdttButtonLabelStyle
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/** Dismiss sits on the title line, not in a 48 dp hole above the paragraph. */
internal fun messageCardDismissUsesCompactIcon(): Boolean = true

/**
 * Title, paragraph and optional link on one inset.
 *
 * The leading icon and the dismiss control share the title line. The paragraph
 * and the link start at the same edge, so the card does not grow a second
 * column or a full-height button under the text.
 */
@Composable
fun ArdttMessageCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = Color.Unspecified,
    bodyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color? = null,
    border: BorderStroke? = null,
    onDismiss: (() -> Unit)? = null,
    dismissDescription: String = "Закрыть",
    dismissColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actionText: String? = null,
    actionColor: Color = MaterialTheme.colorScheme.primary,
    onAction: (() -> Unit)? = null,
) {
    ArdttSectionCard(
        modifier = modifier,
        color = containerColor,
        contentPadding = ArdttLayout.NoteCardPadding,
        verticalArrangement = Arrangement.spacedBy(ArdttLayout.NoteCardSpacing),
        shape = ArdttShapes.Control,
        border = border,
        shadowElevation = ArdttElevation.None,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(ArdttSize.IconCompact),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDismiss != null) {
                ArdttButton(
                    onClick = onDismiss,
                    variant = ArdttButtonVariant.Icon,
                    size = if (messageCardDismissUsesCompactIcon()) {
                        ArdttButtonSize.Compact
                    } else {
                        ArdttButtonSize.Regular
                    },
                    icon = Icons.Outlined.Close,
                    contentDescription = dismissDescription,
                    contentColor = dismissColor,
                )
            }
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = bodyColor,
        )
        if (!actionText.isNullOrBlank() && onAction != null) {
            Text(
                actionText,
                style = ArdttButtonLabelStyle,
                color = actionColor,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClick = onAction),
            )
        }
    }
}
