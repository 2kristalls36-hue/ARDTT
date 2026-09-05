package com.ardtt.app.ui.components.control

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/** Letter spacing of the primary CTA label. */
private val PrimaryLabelTracking = 0.2.sp

/** Disabled label opacity of the primary CTA. */
private const val DisabledLabelAlpha = 0.82f

/**
 * The full-width primary action: sticky CTA on feeds, footer button on
 * standalone screens. Height and shape are fixed so every screen matches.
 */
@Composable
fun ArdttPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .height(ArdttSize.Button),
        shape = ArdttShapes.Control,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = DisabledLabelAlpha),
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = ArdttElevation.Raised,
            pressedElevation = ArdttElevation.Low,
            disabledElevation = ArdttElevation.None,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(ArdttSize.Spinner),
                strokeWidth = ArdttSize.Stroke,
                color = contentColor,
            )
            return@Button
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ArdttSize.Icon))
            Spacer(modifier = Modifier.width(ArdttSpacing.Small))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall.copy(
                letterSpacing = PrimaryLabelTracking,
            ),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
