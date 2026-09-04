package com.ardtt.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ChoiceChipButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContainer: Color? = null,
    dimmed: Boolean = false,
    minWidth: Dp? = null,
    height: Dp = 44.dp,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = ArdttFloatingShell.isDarkTheme()
    val defaultSelectedBg = if (isDark) {
        colors.primary.copy(alpha = 0.22f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.94f)
    }
    val defaultSelectedContent = colors.primary
    val widthModifier = if (minWidth != null) {
        modifier.height(height).widthIn(min = minWidth)
    } else {
        modifier.height(height)
    }

    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = widthModifier,
            shape = RoundedCornerShape(16.dp),
            colors = if (selectedContainer != null) {
                ButtonDefaults.buttonColors(
                    containerColor = selectedContainer,
                    contentColor = Color.White,
                    disabledContainerColor = selectedContainer.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = defaultSelectedBg,
                    contentColor = defaultSelectedContent,
                )
            },
            border = if (selectedContainer == null) {
                BorderStroke(
                    1.dp,
                    if (isDark) colors.primary.copy(alpha = 0.35f) else colors.primary.copy(alpha = 0.25f),
                )
            } else {
                null
            },
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = if (dimmed) Color.White.copy(alpha = 0.7f) else Color.Unspecified,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = widthModifier,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                colors.outline.copy(alpha = if (dimmed) 0.22f else 0.45f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (dimmed) {
                    colors.onSurface.copy(alpha = 0.45f)
                } else {
                    colors.onSurface
                },
            ),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
