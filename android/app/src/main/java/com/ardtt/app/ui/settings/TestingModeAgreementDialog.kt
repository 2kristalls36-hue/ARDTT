package com.ardtt.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ardtt.app.legal.TestingModeAgreement
import com.ardtt.app.ui.components.control.ArdttCheckboxRow
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.theme.ArdttSpacing

@Composable
fun TestingModeAgreementDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    var accepted by remember { mutableStateOf(false) }
    ArdttDialog(
        title = TestingModeAgreement.TITLE,
        onDismissRequest = onDismiss,
        confirmAction = ArdttDialogAction(
            text = "Принимаю",
            onClick = onAccept,
            enabled = accepted,
        ),
        dismissAction = ArdttDialogAction("Отмена", onDismiss),
        dismissOnClickOutside = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    min = AgreementDialogDefaults.MinBodyHeight,
                    max = AgreementDialogDefaults.MaxBodyHeight,
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.MediumPlus),
        ) {
            for (section in TestingModeAgreement.sections) {
                Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny)) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        section.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ArdttCheckboxRow(
            label = "Я прочитал(а) Соглашение полностью и принимаю его условия",
            checked = accepted,
            onCheckedChange = { accepted = it },
        )
    }
}

private object AgreementDialogDefaults {
    /** Scrollable agreement body: tall enough to read, short enough to keep the consent row on screen. */
    val MinBodyHeight = 160.dp
    val MaxBodyHeight = 380.dp
}
