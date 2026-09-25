package com.ardtt.app.ui.components.control

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Full-width sticky CTA. The fill is [com.ardtt.app.ui.components.surface.ArdttFloatingShell.tintedShell]
 * with the tab pill's hairline and shadow. A caller [containerColor] is the
 * tint (connect, stop, warning), not an opaque fill.
 */
@Composable
fun ArdttPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    containerColor: Color? = null,
    contentColor: Color? = null,
    icon: ImageVector? = null,
) {
    ArdttButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = ArdttButtonVariant.Primary,
        enabled = enabled,
        busy = busy,
        fillMaxWidth = true,
        icon = icon,
        containerColor = containerColor,
        contentColor = contentColor,
        floating = true,
    )
}
