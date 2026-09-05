package com.ardtt.app.ui.components.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSpacing

/**
 * Scrollable modal sheet for content that is not a confirm/cancel dialog —
 * use [ArdttDialog] for those. Shared so the corner radius, navigation-bar
 * inset and trailing padding cannot drift between sheets.
 *
 * Children get no horizontal padding: rows that must reach the rim (dividers,
 * device lists) opt out, the rest apply [ArdttSheetDefaults.HorizontalPadding].
 */
object ArdttSheetDefaults {
    val HorizontalPadding: Dp = ArdttSpacing.XLargePlus
    val ItemSpacing: Dp = ArdttSpacing.Large
    val BottomPadding: Dp = ArdttSpacing.XXXLarge
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArdttBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    verticalArrangement: Arrangement.Vertical =
        Arrangement.spacedBy(ArdttSheetDefaults.ItemSpacing),
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = ArdttShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = ArdttSheetDefaults.BottomPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
