package com.nonamevpn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class NvpnDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/** Sheet can hide only when at least one dismiss path is enabled. */
internal fun nvpnDialogAllowsHide(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
): Boolean = dismissOnBackPress || dismissOnClickOutside

/**
 * Locked sheets drop the drag handle, which otherwise supplies the top inset.
 * Match the horizontal content padding so the title is not flush with the rim.
 */
internal const val NVPN_DIALOG_LOCKED_TITLE_TOP_DP = 24

internal fun nvpnDialogTitleTopPaddingDp(allowsHide: Boolean): Int =
    if (allowsHide) 0 else NVPN_DIALOG_LOCKED_TITLE_TOP_DP

/**
 * Shared bottom sheet replacing the old opaque modal surface.
 * Actions always live in one footer row:
 * optional secondary action on the left, cancel and primary on the right.
 *
 * When both dismiss flags are false the sheet cannot be swiped, back-pressed,
 * or scrim-tapped away — otherwise a Hidden sheet left in composition blocks
 * the whole UI until process restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val allowsHide = nvpnDialogAllowsHide(dismissOnBackPress, dismissOnClickOutside)
    val allowsHideState = rememberUpdatedState(allowsHide)
    val onDismissState = rememberUpdatedState(onDismissRequest)
    val confirmValueChange = remember {
        { value: SheetValue ->
            value != SheetValue.Hidden || allowsHideState.value
        }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = confirmValueChange,
    )
    ModalBottomSheet(
        onDismissRequest = {
            if (allowsHideState.value) onDismissState.value()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        dragHandle = if (allowsHide) {
            { BottomSheetDefaults.DragHandle() }
        } else {
            null
        },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = dismissOnBackPress,
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(
                    start = 24.dp,
                    top = nvpnDialogTitleTopPaddingDp(allowsHide).dp,
                    end = 24.dp,
                ),
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
