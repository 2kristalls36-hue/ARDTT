package com.ardtt.app.ui.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ardtt.app.core.ConnectionUiAction
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.connectionUiActionLabel
import com.ardtt.app.ui.theme.ArdttSpacing

@Composable
fun ConnectionActionChips(
    actions: List<ConnectionUiAction>,
    onAction: (ConnectionUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            val destructive = action == ConnectionUiAction.Disconnect ||
                action == ConnectionUiAction.CancelWait
            ArdttButton(
                text = connectionUiActionLabel(action),
                onClick = { onAction(action) },
                variant = if (destructive) ArdttButtonVariant.Outlined else ArdttButtonVariant.Tonal,
                size = ArdttButtonSize.Compact,
                fillMaxWidth = false,
            )
        }
    }
}
