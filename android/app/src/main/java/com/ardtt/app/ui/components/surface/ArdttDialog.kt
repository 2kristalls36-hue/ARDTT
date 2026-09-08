package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSpacing

data class ArdttDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/** Sheet can hide only when at least one dismiss path is enabled. */
internal fun ardttDialogAllowsHide(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
): Boolean = dismissOnBackPress || dismissOnClickOutside

/**
 * Locked sheets drop the drag handle, which otherwise supplies the top inset.
 * Match the horizontal content padding so the title is not flush with the rim.
 */
internal const val ARDTT_DIALOG_LOCKED_TITLE_TOP_DP = 24

internal fun ardttDialogTitleTopPaddingDp(allowsHide: Boolean): Int =
    if (allowsHide) 0 else ARDTT_DIALOG_LOCKED_TITLE_TOP_DP

/**
 * Shared bottom sheet used for every dialog in the app.
 * Actions always live in one footer row: optional secondary action on the left,
 * cancel and primary on the right.
 *
 * When both dismiss flags are false the sheet cannot be swiped, back-pressed,
 * or scrim-tapped away — otherwise a Hidden sheet left in composition blocks
 * the whole UI until process restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArdttDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmAction: ArdttDialogAction? = null,
    dismissAction: ArdttDialogAction? = null,
    secondaryAction: ArdttDialogAction? = null,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val allowsHide = ardttDialogAllowsHide(dismissOnBackPress, dismissOnClickOutside)
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
        tonalElevation = ArdttElevation.Raised,
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
                .padding(bottom = ArdttLayout.DialogPadding),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(
                    start = ArdttLayout.DialogPadding,
                    top = ardttDialogTitleTopPaddingDp(allowsHide).dp,
                    end = ArdttLayout.DialogPadding,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ArdttLayout.DialogPadding,
                        vertical = ArdttSpacing.Large,
                    ),
                verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
                content = content,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ArdttSpacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                secondaryAction?.let { action -> DialogTextAction(action) }
                Spacer(modifier = Modifier.weight(1f))
                dismissAction?.let { action ->
                    DialogTextAction(action)
                    Spacer(modifier = Modifier.width(ArdttSpacing.Tiny))
                }
                confirmAction?.let { action -> DialogConfirmAction(action) }
            }
        }
    }
}

@Composable
private fun DialogConfirmAction(action: ArdttDialogAction) {
    ArdttButton(
        text = action.text,
        onClick = action.onClick,
        enabled = action.enabled,
        variant = if (action.destructive) ArdttButtonVariant.Danger else ArdttButtonVariant.Primary,
        size = ArdttButtonSize.Compact,
        fillMaxWidth = false,
    )
}

@Composable
private fun DialogTextAction(action: ArdttDialogAction) {
    ArdttButton(
        text = action.text,
        onClick = action.onClick,
        enabled = action.enabled,
        variant = if (action.destructive) ArdttButtonVariant.Danger else ArdttButtonVariant.Text,
        size = ArdttButtonSize.Compact,
        fillMaxWidth = false,
        contentColor = if (action.destructive) {
            MaterialTheme.colorScheme.error
        } else {
            null
        },
    )
}
