package com.ardtt.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp

private val OverflowMenuWidth = 216.dp
private val OverflowMenuShape = RoundedCornerShape(22.dp)
private val OverflowItemMinHeight = 54.dp
private val OverflowItemPadding = PaddingValues(horizontal = 18.dp)

/** Shared ⋮ menu chrome — same sheet on Profiles and Servers. */
@Composable
fun OverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .width(OverflowMenuWidth)
            .padding(vertical = 4.dp),
        shape = OverflowMenuShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        content = content,
    )
}

@Composable
fun OverflowMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    val disabled = colors.onSurface.copy(alpha = 0.42f)
    val contentColor = when {
        !enabled -> disabled
        destructive -> colors.error
        else -> colors.onSurface
    }
    DropdownMenuItem(
        modifier = modifier.heightIn(min = OverflowItemMinHeight),
        contentPadding = OverflowItemPadding,
        text = {
            Text(
                text,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
        },
        leadingIcon = leadingIcon?.let { icon ->
            {
                Icon(icon, contentDescription = null, tint = contentColor)
            }
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
