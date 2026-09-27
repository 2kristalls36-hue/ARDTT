package com.ardtt.app.ui.components.control

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Full-width sticky CTA. The fill is the semantic color at
 * [com.ardtt.app.ui.components.surface.ArdttFloatingShell.ButtonAlpha] in both
 * themes, with the tab pill's hairline and no shadow. A caller [containerColor]
 * is that hue (connect, stop, warning).
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
