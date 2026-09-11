package com.ardtt.app.ui.components.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing

/**
 * Title + optional subtitle + trailing [Switch].
 *
 * Settings, the deploy form and the tunnel quick-settings card each had their
 * own copy of this row with different typography and hit targets; this is the
 * one implementation.
 */
@Composable
fun ArdttSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    // One toggleable node: the label and the switch are announced together and
    // the whole row (≥ 48 dp tall) is the hit target. The Switch itself gets no
    // click handler, so TalkBack does not see two controls for one setting.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ArdttSize.TouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelAlpha = if (enabled) 1f else ArdttAlpha.Disabled
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = ArdttSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = LocalContentColor.current.copy(alpha = labelAlpha),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = labelAlpha),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Title + optional subtitle + arbitrary control below (chips, sliders).
 * The block form of [ArdttSwitchRow] for controls that do not fit on one line.
 */
@Composable
fun ArdttSettingBlock(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(
            if (compact) ArdttSpacing.TinyPlus else ArdttSpacing.Small,
        ),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}
