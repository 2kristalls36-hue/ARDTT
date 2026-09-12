package com.ardtt.app.ui.tunnel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.isDarkSurface

private val DonateCardLight = Color(0xFFFFFBE6)
private val DonateAccentLight = Color(0xFFB8860B)

internal data class DonateBannerPalette(
    val card: Color,
    val accent: Color,
    val body: Color,
    val border: Color,
    val icon: Color,
    val dismiss: Color,
)

/** Light keeps the cream/gold coffee card; dark follows the blue-navy theme. */
internal fun donateBannerPalette(
    isDark: Boolean,
    surface: Color,
    primary: Color,
    primaryContainer: Color,
    onSurfaceVariant: Color,
): DonateBannerPalette {
    if (!isDark) {
        return DonateBannerPalette(
            card = DonateCardLight,
            accent = DonateAccentLight,
            body = DonateAccentLight.copy(alpha = 0.80f),
            border = DonateAccentLight.copy(alpha = 0.45f),
            icon = primary,
            dismiss = DonateAccentLight.copy(alpha = 0.72f),
        )
    }
    return DonateBannerPalette(
        card = lerp(surface, primaryContainer, 0.40f),
        accent = primary,
        body = onSurfaceVariant,
        border = primary.copy(alpha = 0.28f),
        icon = primary,
        dismiss = onSurfaceVariant.copy(alpha = 0.80f),
    )
}

@Composable
fun DonateSupportBanner(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val colors = donateBannerPalette(
        isDark = isDarkSurface(),
        surface = scheme.surface,
        primary = scheme.primary,
        primaryContainer = scheme.primaryContainer,
        onSurfaceVariant = scheme.onSurfaceVariant,
    )
    ArdttSectionCard(
        modifier = modifier,
        color = colors.card,
        contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        shape = ArdttShapes.Control,
        border = BorderStroke(ArdttSectionCardDefaults.ContourWidth, colors.border),
        shadowElevation = ArdttElevation.None,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LocalCafe,
                contentDescription = null,
                tint = colors.icon,
                modifier = Modifier
                    .padding(end = ArdttSpacing.Small)
                    .size(ArdttSize.IconCompact),
            )
            Text(
                DonateSupport.TITLE,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                ArdttButton(
                    onClick = onDismiss,
                    variant = ArdttButtonVariant.Icon,
                    icon = Icons.Outlined.Close,
                    contentDescription = "Закрыть предложение поддержать автора",
                    contentColor = colors.dismiss,
                )
            }
        }
        Text(
            DonateSupport.BODY,
            style = MaterialTheme.typography.bodySmall,
            color = colors.body,
        )
        ArdttButton(
            text = DonateSupport.ACTION,
            onClick = { DonateSupport.openPage(context) },
            variant = ArdttButtonVariant.Text,
            fillMaxWidth = false,
            contentColor = colors.accent,
        )
    }
}
