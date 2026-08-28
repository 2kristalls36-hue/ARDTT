package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class NvpnDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * Shared opaque modal surface. Actions always live in one footer row:
 * optional secondary action on the left, cancel and primary on the right.
 */
@Composable
fun NvpnDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmAction: NvpnDialogAction? = null,
    dismissAction: NvpnDialogAction? = null,
    secondaryAction: NvpnDialogAction? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(top = 24.dp, bottom = 14.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    secondaryAction?.let { action ->
                        DialogTextAction(action)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    dismissAction?.let { action ->
                        DialogTextAction(action)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    confirmAction?.let { action ->
                        if (action.destructive) {
                            Button(
                                onClick = action.onClick,
                                enabled = action.enabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Text(action.text)
                            }
                        } else {
                            Button(
                                onClick = action.onClick,
                                enabled = action.enabled,
                            ) {
                                Text(action.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTextAction(action: NvpnDialogAction) {
    TextButton(
        onClick = action.onClick,
        enabled = action.enabled,
        colors = if (action.destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(action.text)
    }
}
