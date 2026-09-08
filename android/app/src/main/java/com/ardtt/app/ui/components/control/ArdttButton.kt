package com.ardtt.app.ui.components.control

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttSurface

enum class ArdttButtonVariant {
    Primary,
    Tonal,
    Outlined,
    Text,
    Danger,
    Icon,
}

enum class ArdttButtonSize {
    Regular,
    Compact,
}

private val PrimaryLabelTracking = 0.2.sp

/**
 * Shared button contract. [ArdttPrimaryButton] stays as the full-width primary
 * wrapper used by feed CTAs.
 */
@Composable
fun ArdttButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ArdttButtonVariant = ArdttButtonVariant.Primary,
    size: ArdttButtonSize = ArdttButtonSize.Regular,
    enabled: Boolean = true,
    busy: Boolean = false,
    fillMaxWidth: Boolean = variant == ArdttButtonVariant.Primary && size == ArdttButtonSize.Regular,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val minHeight = if (size == ArdttButtonSize.Compact) ArdttSize.ButtonCompact else ArdttSize.Button
    val pair = buttonColors(variant, scheme.primary, scheme.onPrimary, containerColor, contentColor)
    val clickable = enabled && !busy
    val semanticsModifier = Modifier.semantics(mergeDescendants = true) {
        this.role = if (variant == ArdttButtonVariant.Icon) Role.Button else Role.Button
        val name = contentDescription ?: text
        this.contentDescription = name
        if (busy) this.stateDescription = "Загрузка"
        if (!enabled) this.disabled()
    }
    val widthModifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    val sized = modifier
        .then(widthModifier)
        .then(semanticsModifier)
        .defaultMinSize(minHeight = minHeight)

    val content: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(
                    if (size == ArdttButtonSize.Compact) ArdttSize.SpinnerSmall else ArdttSize.Spinner,
                ),
                strokeWidth = ArdttSize.Stroke,
                color = pair.content,
            )
        } else {
            if (icon != null && variant != ArdttButtonVariant.Icon) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(ArdttSize.Icon),
                )
                Spacer(modifier = Modifier.width(ArdttSpacing.Small))
            }
            if (variant != ArdttButtonVariant.Icon) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleSmall.copy(
                        letterSpacing = PrimaryLabelTracking,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ArdttSize.Icon))
            }
        }
    }

    when (variant) {
        ArdttButtonVariant.Primary, ArdttButtonVariant.Danger -> {
            Button(
                onClick = onClick,
                enabled = clickable,
                modifier = sized,
                shape = ArdttShapes.Control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = pair.container,
                    contentColor = pair.content,
                    disabledContainerColor = pair.container,
                    disabledContentColor = pair.content.copy(alpha = 0.82f),
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = ArdttElevation.Raised,
                    pressedElevation = ArdttElevation.Low,
                    disabledElevation = ArdttElevation.None,
                ),
                contentPadding = PaddingValues(horizontal = ArdttSpacing.Large, vertical = ArdttSpacing.Small),
            ) { content() }
        }
        ArdttButtonVariant.Tonal -> {
            FilledTonalButton(
                onClick = onClick,
                enabled = clickable,
                modifier = sized,
                shape = ArdttShapes.Control,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = pair.container,
                    contentColor = pair.content,
                    disabledContainerColor = pair.container,
                    disabledContentColor = pair.content.copy(alpha = 0.82f),
                ),
            ) { content() }
        }
        ArdttButtonVariant.Outlined -> {
            OutlinedButton(
                onClick = onClick,
                enabled = clickable,
                modifier = sized,
                shape = if (size == ArdttButtonSize.Compact) ArdttShapes.Icon else ArdttShapes.Control,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = pair.content,
                    disabledContentColor = pair.content.copy(alpha = 0.82f),
                ),
                contentPadding = PaddingValues(horizontal = ArdttSpacing.Medium, vertical = ArdttSpacing.TinyPlus),
            ) { content() }
        }
        ArdttButtonVariant.Text -> {
            TextButton(
                onClick = onClick,
                enabled = clickable,
                modifier = sized.defaultMinSize(minHeight = ArdttSize.TouchTarget),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = pair.content,
                    disabledContentColor = pair.content.copy(alpha = 0.82f),
                ),
            ) { content() }
        }
        ArdttButtonVariant.Icon -> {
            IconButton(
                onClick = onClick,
                enabled = clickable,
                modifier = sized.defaultMinSize(
                    minWidth = ArdttSize.TouchTarget,
                    minHeight = ArdttSize.TouchTarget,
                ),
            ) { content() }
        }
    }
}

private data class ButtonPair(val container: Color, val content: Color)

@Composable
private fun buttonColors(
    variant: ArdttButtonVariant,
    primary: Color,
    onPrimary: Color,
    containerOverride: Color?,
    contentOverride: Color?,
): ButtonPair {
    val scheme = MaterialTheme.colorScheme
    val container = containerOverride ?: when (variant) {
        ArdttButtonVariant.Primary -> primary
        ArdttButtonVariant.Tonal -> scheme.secondaryContainer
        ArdttButtonVariant.Outlined, ArdttButtonVariant.Text, ArdttButtonVariant.Icon -> Color.Transparent
        ArdttButtonVariant.Danger -> scheme.error
    }
    val fallbackContent = when (variant) {
        ArdttButtonVariant.Primary -> onPrimary
        ArdttButtonVariant.Tonal -> scheme.onSecondaryContainer
        ArdttButtonVariant.Outlined, ArdttButtonVariant.Text, ArdttButtonVariant.Icon -> scheme.primary
        ArdttButtonVariant.Danger -> scheme.onError
    }
    val content = when {
        contentOverride != null -> contentOverride
        containerOverride != null && container.alpha > 0.04f ->
            ArdttSurface.contentColorOn(container)
        else -> fallbackContent
    }
    return ButtonPair(container, content)
}
