package com.nonamevpn.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Soft section card. */
@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    border: BorderStroke? = null,
    color: Color? = null,
    shadowElevation: Dp? = null,
    tonalElevation: Dp? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val cardColor = color ?: if (isDark) {
        lerp(colors.surface, colors.surfaceVariant, 0.10f).copy(alpha = 0.82f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.28f).copy(alpha = 0.85f)
    }
    val borderColor = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.26f)
    } else {
        colors.outlineVariant.copy(alpha = 0.24f)
    }

    Surface(
        shape = shape,
        color = cardColor,
        contentColor = colors.onSurface,
        border = border ?: BorderStroke(1.dp, borderColor),
        shadowElevation = shadowElevation ?: if (isDark) 2.dp else 10.dp,
        tonalElevation = tonalElevation ?: if (isDark) 0.dp else 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
