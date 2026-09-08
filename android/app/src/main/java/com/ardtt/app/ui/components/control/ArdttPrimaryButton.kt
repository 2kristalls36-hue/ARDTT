package com.ardtt.app.ui.components.control

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Compatibility wrapper: full-width primary CTA. Implementation lives in
 * [ArdttButton].
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
    )
}
