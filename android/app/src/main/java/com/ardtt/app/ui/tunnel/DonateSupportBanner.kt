package com.ardtt.app.ui.tunnel

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.ardtt.app.ui.components.surface.ArdttMessageCard
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.isDarkSurface

private object DonateSupportBannerDefaults {
    const val BodyAlpha = ArdttAlpha.Strong
    const val DismissAlpha = 0.80f
    const val LightBorderAlpha = 0.45f
    const val DarkCardBlend = 0.40f
    const val DarkBorderAlpha = 0.28f
}

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
            card = ArdttColors.SupportSurface,
            accent = ArdttColors.SupportForeground,
            body = ArdttColors.SupportForeground.copy(
                alpha = DonateSupportBannerDefaults.BodyAlpha,
            ),
            border = ArdttColors.SupportAccent.copy(
                alpha = DonateSupportBannerDefaults.LightBorderAlpha,
            ),
            icon = primary,
            dismiss = ArdttColors.SupportForeground.copy(alpha = ArdttAlpha.Subtle),
        )
    }
    return DonateBannerPalette(
        card = lerp(surface, primaryContainer, DonateSupportBannerDefaults.DarkCardBlend),
        accent = primary,
        body = onSurfaceVariant,
        border = primary.copy(alpha = DonateSupportBannerDefaults.DarkBorderAlpha),
        icon = primary,
        dismiss = onSurfaceVariant.copy(
            alpha = DonateSupportBannerDefaults.DismissAlpha,
        ),
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
    ArdttMessageCard(
        title = DonateSupport.TITLE,
        body = DonateSupport.BODY,
        modifier = modifier,
        icon = Icons.Outlined.LocalCafe,
        iconTint = colors.icon,
        bodyColor = colors.body,
        containerColor = colors.card,
        border = BorderStroke(ArdttSectionCardDefaults.ContourWidth, colors.border),
        onDismiss = onDismiss,
        dismissDescription = "Закрыть предложение поддержать автора",
        dismissColor = colors.dismiss,
        actionText = DonateSupport.ACTION,
        actionColor = colors.accent,
        onAction = { DonateSupport.openPage(context) },
    )
}
