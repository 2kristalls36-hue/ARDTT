package com.nonamevpn.app.ui.tunnel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.ui.components.AppSectionCard

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
    val isDark = scheme.background.luminance() < 0.22f
    val colors = donateBannerPalette(
        isDark = isDark,
        surface = scheme.surface,
        primary = scheme.primary,
        primaryContainer = scheme.primaryContainer,
        onSurfaceVariant = scheme.onSurfaceVariant,
    )
    AppSectionCard(
        modifier = modifier,
        color = colors.card,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp,
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
                    .padding(end = 8.dp)
                    .size(18.dp),
            )
            Text(
                DonateSupport.TITLE,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Закрыть предложение поддержать автора",
                        tint = colors.dismiss,
                    )
                }
            }
        }
        Text(
            DonateSupport.BODY,
            style = MaterialTheme.typography.bodySmall,
            color = colors.body,
        )
        TextButton(
            onClick = { DonateSupport.openPage(context) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(DonateSupport.ACTION, color = colors.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}
