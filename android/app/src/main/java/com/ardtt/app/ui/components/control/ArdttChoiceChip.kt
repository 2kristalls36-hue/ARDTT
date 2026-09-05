package com.ardtt.app.ui.components.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.isDarkSurface
import com.ardtt.app.ui.theme.selectedControlContainer

/** One option of a segmented control. */
data class ArdttChoice<T>(
    val value: T,
    val label: String,
    /** Filled accent for the selected state; null keeps the neutral primary fill. */
    val accent: Color? = null,
    /** Dim the option without disabling it (missing prerequisite, not forbidden). */
    val dimmed: Boolean = false,
    /** Runs instead of `onSelect` when the option cannot be applied yet. */
    val onBlocked: (() -> Unit)? = null,
)

private object ChoiceChipDefaults {
    /** Selected fill alpha over dark chrome. */
    const val SelectedFillAlphaDark = ArdttAlpha.Outline
    const val SelectedBorderAlphaDark = 0.35f
    const val SelectedBorderAlphaLight = 0.25f
    const val UnselectedBorderAlpha = 0.45f
    const val DimmedBorderAlpha = ArdttAlpha.Outline
    val ContentPadding = PaddingValues(horizontal = ArdttSpacing.XLarge)
}

/**
 * Full-width row of mutually exclusive chips.
 *
 * Replaces the four hand-written chip rows (path mode, hide IP, dial path,
 * theme mode) that each repeated the same `Row` + `weight(1f)` scaffolding.
 */
@Composable
fun <T> ArdttChoiceChipRow(
    choices: List<ArdttChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipHeight: Dp = ArdttSize.Chip,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ArdttLayout.ControlSpacing),
    ) {
        choices.forEach { choice ->
            ArdttChoiceChip(
                label = choice.label,
                selected = choice.value == selected,
                enabled = enabled,
                onClick = {
                    val blocked = choice.onBlocked
                    if (blocked != null) blocked() else onSelect(choice.value)
                },
                modifier = Modifier.weight(1f),
                selectedContainer = choice.accent,
                dimmed = choice.dimmed,
                height = chipHeight,
            )
        }
    }
}

@Composable
fun ArdttChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContainer: Color? = null,
    dimmed: Boolean = false,
    minWidth: Dp? = null,
    height: Dp = ArdttSize.Chip,
) {
    val colors = MaterialTheme.colorScheme
    val sizeModifier = modifier
        .height(height)
        .then(if (minWidth != null) Modifier.widthIn(min = minWidth) else Modifier)

    if (!selected) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = sizeModifier,
            shape = ArdttShapes.Chip,
            border = BorderStroke(
                ArdttSize.Border,
                colors.outline.copy(
                    alpha = if (dimmed) {
                        ChoiceChipDefaults.DimmedBorderAlpha
                    } else {
                        ChoiceChipDefaults.UnselectedBorderAlpha
                    },
                ),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (dimmed) {
                    colors.onSurface.copy(alpha = ArdttAlpha.Disabled)
                } else {
                    colors.onSurface
                },
            ),
            contentPadding = ChoiceChipDefaults.ContentPadding,
        ) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        return
    }

    val dark = isDarkSurface()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = sizeModifier,
        shape = ArdttShapes.Chip,
        colors = if (selectedContainer != null) {
            ButtonDefaults.buttonColors(
                containerColor = selectedContainer,
                contentColor = Color.White,
                disabledContainerColor = selectedContainer.copy(alpha = ArdttAlpha.Disabled),
                disabledContentColor = Color.White.copy(alpha = ArdttAlpha.Subtle),
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = selectedControlContainer(
                    darkAlpha = ChoiceChipDefaults.SelectedFillAlphaDark,
                ),
                contentColor = colors.primary,
            )
        },
        border = if (selectedContainer == null) {
            BorderStroke(
                ArdttSize.Border,
                colors.primary.copy(
                    alpha = if (dark) {
                        ChoiceChipDefaults.SelectedBorderAlphaDark
                    } else {
                        ChoiceChipDefaults.SelectedBorderAlphaLight
                    },
                ),
            )
        } else {
            null
        },
        contentPadding = ChoiceChipDefaults.ContentPadding,
    ) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = if (dimmed) Color.White.copy(alpha = ArdttAlpha.Subtle) else Color.Unspecified,
        )
    }
}
