package com.ardtt.app.ui.components.control

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

private object OverflowMenuDefaults {
    val ItemPadding = PaddingValues(horizontal = ArdttSpacing.LargePlus)

    /** Disabled label / icon opacity, matching the dialog text buttons. */
    const val DisabledAlpha = 0.42f
}

/** Shared ⋮ menu chrome — same sheet on Profiles and Servers. */
@Composable
fun ArdttOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .width(ArdttSize.MenuWidth)
            .padding(vertical = ArdttSpacing.Tiny),
        shape = ArdttShapes.Menu,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = ArdttElevation.Low,
        shadowElevation = ArdttElevation.Raised,
        content = content,
    )
}

@Composable
fun ArdttOverflowMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    val disabled = colors.onSurface.copy(alpha = OverflowMenuDefaults.DisabledAlpha)
    val contentColor = when {
        !enabled -> disabled
        destructive -> colors.error
        else -> colors.onSurface
    }
    DropdownMenuItem(
        modifier = modifier.heightIn(min = ArdttSize.MenuItem),
        contentPadding = OverflowMenuDefaults.ItemPadding,
        text = {
            Text(text, fontWeight = FontWeight.Medium, color = contentColor)
        },
        leadingIcon = leadingIcon?.let { icon ->
            { Icon(icon, contentDescription = null, tint = contentColor) }
        },
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = colors.onSurface,
            leadingIconColor = colors.onSurface,
            disabledTextColor = disabled,
            disabledLeadingIconColor = disabled,
        ),
    )
}
