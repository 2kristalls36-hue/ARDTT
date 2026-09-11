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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import com.ardtt.app.ui.theme.ArdttAlpha
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
    text: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ArdttButtonVariant = ArdttButtonVariant.Primary,
    size: ArdttButtonSize = ArdttButtonSize.Regular,
    enabled: Boolean = true,
    busy: Boolean = false,
    fillMaxWidth: Boolean = ardttButtonFillsWidth(variant, size, text),
    icon: ImageVector? = null,
    contentDescription: String? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val minHeight = ardttButtonMinHeight(variant, size)
    val pair = buttonColors(variant, scheme.primary, scheme.onPrimary, containerColor, contentColor)
    val disabledPair = ardttDisabledButtonColors(
        variant = variant,
        pair = pair,
        containerOverridden = containerColor != null,
    )
    val clickable = enabled && !busy
    val showsText = ardttButtonShowsText(variant, text)
    val semanticsModifier = Modifier.semantics(mergeDescendants = true) {
        this.role = Role.Button
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
                if (showsText) {
                    Spacer(modifier = Modifier.width(ArdttSpacing.Small))
                }
            }
            if (showsText) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleSmall.copy(
                        letterSpacing = PrimaryLabelTracking,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (variant == ArdttButtonVariant.Icon && icon != null) {
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
                    disabledContainerColor = disabledPair.container,
                    disabledContentColor = disabledPair.content,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = ArdttElevation.Raised,
                    pressedElevation = ArdttElevation.Low,
                    disabledElevation = ArdttElevation.None,
                ),
                contentPadding = ardttButtonContentPadding(variant, showsText),
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
                    disabledContainerColor = disabledPair.container,
                    disabledContentColor = disabledPair.content,
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
                    disabledContentColor = disabledPair.content,
                ),
                contentPadding = ardttButtonContentPadding(variant, showsText),
            ) { content() }
        }
        ArdttButtonVariant.Text -> {
            TextButton(
                onClick = onClick,
                enabled = clickable,
                modifier = sized.defaultMinSize(minHeight = ArdttSize.TouchTarget),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = pair.content,
                    disabledContentColor = disabledPair.content,
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
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = pair.content,
                    disabledContentColor = disabledPair.content,
                ),
            ) { content() }
        }
    }
}

/**
 * Disabled look shared by every variant.
 *
 * Filled buttons keep their hue but recede ([ArdttAlpha.DisabledContainer]);
 * a caller that passed its own `containerColor` (profile switcher lock) already
 * chose the disabled fill, so that override is kept as is. Labels of every
 * variant drop to [ArdttAlpha.Disabled] — the same step as menu items and
 * dimmed chips, so «отключено» reads the same on every control.
 */
internal fun ardttDisabledButtonColors(
    variant: ArdttButtonVariant,
    pair: ButtonPair,
    containerOverridden: Boolean,
): ButtonPair {
    val filled = variant == ArdttButtonVariant.Primary ||
        variant == ArdttButtonVariant.Tonal ||
        variant == ArdttButtonVariant.Danger
    val container = when {
        !filled || containerOverridden -> pair.container
        else -> pair.container.copy(alpha = pair.container.alpha * ArdttAlpha.DisabledContainer)
    }
    // Filled labels sit on a still-tinted container: Subtle keeps them legible
    // against that fill, Disabled is for labels over the plain surface.
    val contentAlpha = if (filled) ArdttAlpha.Subtle else ArdttAlpha.Disabled
    return ButtonPair(container, pair.content.copy(alpha = pair.content.alpha * contentAlpha))
}

internal fun ardttButtonMinHeight(variant: ArdttButtonVariant, size: ArdttButtonSize) = when {
    variant == ArdttButtonVariant.Icon -> ArdttSize.TouchTarget
    size == ArdttButtonSize.Compact -> ArdttSize.ButtonCompact
    else -> ArdttSize.Button
}

internal fun ardttButtonShowsText(variant: ArdttButtonVariant, text: String): Boolean =
    variant != ArdttButtonVariant.Icon && text.isNotBlank()

internal fun ardttButtonFillsWidth(
    variant: ArdttButtonVariant,
    size: ArdttButtonSize,
    text: String,
): Boolean = variant == ArdttButtonVariant.Primary &&
    size == ArdttButtonSize.Regular &&
    ardttButtonShowsText(variant, text)

internal fun ardttButtonContentPadding(
    variant: ArdttButtonVariant,
    showsText: Boolean,
): PaddingValues = when (variant) {
    ArdttButtonVariant.Primary, ArdttButtonVariant.Danger ->
        if (showsText) {
            PaddingValues(horizontal = ArdttSpacing.Large, vertical = ArdttSpacing.Small)
        } else {
            PaddingValues(ArdttSpacing.None)
        }
    ArdttButtonVariant.Outlined ->
        PaddingValues(horizontal = ArdttSpacing.Medium, vertical = ArdttSpacing.TinyPlus)
    else -> PaddingValues(ArdttSpacing.Small)
}

internal data class ButtonPair(val container: Color, val content: Color)

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
        ArdttButtonVariant.Outlined, ArdttButtonVariant.Text -> scheme.primary
        // A bare glyph inherits the surrounding text color (header, card, banner);
        // `contentColor` overrides it explicitly.
        ArdttButtonVariant.Icon -> LocalContentColor.current
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
