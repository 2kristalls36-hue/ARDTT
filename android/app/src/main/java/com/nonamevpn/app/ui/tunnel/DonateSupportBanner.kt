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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.ui.components.AppSectionCard

private val DonateYellow = Color(0xFFFFF8D6)
private val DonateAccent = Color(0xFFB8860B)

@Composable
fun DonateSupportBanner(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val isDark = surface.luminance() < 0.22f
    // Blend yellow into the theme surface so the card looks like a tinted variant,
    // not a harsh foreign element.
    val cardColor = lerp(surface, DonateYellow, if (isDark) 0.10f else 0.55f)
    val accentColor = DonateAccent
    AppSectionCard(
        modifier = modifier,
        color = cardColor,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LocalCafe,
                contentDescription = null,
                tint = accentColor,
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
                        tint = accentColor.copy(alpha = 0.72f),
                    )
                }
            }
        }
        Text(
            DonateSupport.BODY,
            style = MaterialTheme.typography.bodySmall,
            color = accentColor.copy(alpha = 0.80f),
        )
        TextButton(
            onClick = { DonateSupport.openPage(context) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(DonateSupport.ACTION, color = accentColor, fontWeight = FontWeight.SemiBold)
        }
    }
}
