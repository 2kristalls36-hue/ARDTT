package com.ardtt.app.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ardtt.app.deploy.DeployHopTrack
import com.ardtt.app.deploy.DeployIssue
import com.ardtt.app.deploy.DeployProgressCopy
import com.ardtt.app.deploy.DeployRuntimeBundle
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.feedback.ArdttLinearProgress
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.components.surface.ArdttTerminalCard
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.connectedStatusColor
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.shareText


@Composable
internal fun DeployProgressSheet(
    busy: Boolean,
    isUpdate: Boolean,
    status: String?,
    step: String,
    progress: Float,
    log: List<String>,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    isUninstall: Boolean = false,
    isPreflight: Boolean = false,
    hopTrack: DeployHopTrack = DeployHopTrack(),
    failure: DeployIssue? = null,
    onRetryPreflight: (() -> Unit)? = null,
    onRetryInstall: (() -> Unit)? = null,
    onDiskCleanupRetry: (() -> Unit)? = null,
    retryInstallEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val failed = deployProgressFailed(busy, status, failure)
    val finishedOk = deployProgressFinishedSuccess(busy, status, failure)
    val slots = cascadeDeploySlots(
        hopTrack,
        failed = failed,
        finishedSuccess = finishedOk,
    )
    var showLog by remember { mutableStateOf(false) }
    var showDiskCleanupConfirm by remember { mutableStateOf(false) }
    val redacted = remember(log) { DeployIssue.redactLog(log.joinToString("\n")) }
    val headline = when {
        failure != null -> failure.summary
        !status.isNullOrBlank() -> status
        else -> null
    }
    val dockerMissing = failure?.code == DeployIssue.DOCKER_MISSING
    val offerDiskCleanup = !busy && !isUninstall &&
        DeployIssue.offersDiskCleanup(failure) &&
        onDiskCleanupRetry != null
    val prepareLabel = if (failure?.hopRole == "exit") "Подготовить VPS2" else "Подготовить VPS"
    ArdttDialog(
        title = deployProgressSheetTitle(
            busy = busy,
            isUpdate = isUpdate,
            status = status,
            isUninstall = isUninstall,
            failure = failure,
            isPreflight = isPreflight,
        ),
        onDismissRequest = {},
        confirmAction = if (busy) {
            ArdttDialogAction("Отменить", onCancel, destructive = true)
        } else {
            ArdttDialogAction("Закрыть", onClose)
        },
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
    ) {
        if (slots.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small)) {
                slots.forEach { slot ->
                    DeployHopSlotCard(
                        slot = slot,
                        isUpdate = isUpdate,
                        isUninstall = isUninstall,
                    )
                }
            }
        }
        if (failed) {
            headline?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (step.isNotBlank()) {
                Text(
                    "Этап: $step",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                step.ifBlank { headline ?: "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (finishedOk) connectedStatusColor() else MaterialTheme.colorScheme.onSurface,
            )
            if (busy) {
                ArdttLinearProgress(progress = progress)
                Text(
                    DeployProgressCopy.percentLabel(progress),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!busy && dockerMissing && !DeployRuntimeBundle.INCLUDED) {
            Text(
                DeployRuntimeBundle.missingRuntimeMessage(
                    osId = "",
                    osVersion = "",
                    arch = "",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttButton(
                text = prepareLabel,
                onClick = {},
                enabled = false,
                variant = ArdttButtonVariant.Tonal,
                fillMaxWidth = true,
            )
        }
        if (offerDiskCleanup) {
            Text(
                "Можно освободить место на VPS (логи Docker, кэш apt, лишние headers, хвосты ARDTT) и сразу повторить установку. Данные ARDTT и чужие контейнеры не удаляются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttButton(
                text = "Очистить место и повторить",
                onClick = { showDiskCleanupConfirm = true },
                fillMaxWidth = true,
            )
        }
        if (!busy && !isUninstall) {
            if (onRetryPreflight != null) {
                ArdttButton(
                    text = "Повторить проверку",
                    onClick = onRetryPreflight,
                    variant = ArdttButtonVariant.Outlined,
                    fillMaxWidth = true,
                )
            }
            if (onRetryInstall != null) {
                ArdttButton(
                    text = "Повторить установку",
                    onClick = onRetryInstall,
                    enabled = retryInstallEnabled,
                    fillMaxWidth = true,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        ) {
            ArdttButton(
                text = if (showLog) "Скрыть журнал" else "Журнал",
                onClick = { showLog = !showLog },
                variant = ArdttButtonVariant.Text,
                modifier = Modifier.weight(1f),
            )
            ArdttButton(
                text = "Скопировать лог",
                onClick = { copyToClipboard(context, redacted, "ARDTT deploy") },
                enabled = redacted.isNotBlank(),
                variant = ArdttButtonVariant.Text,
                modifier = Modifier.weight(1f),
            )
        }
        ArdttButton(
            text = "Поделиться логом",
            onClick = { shareText(context, redacted, "ARDTT deploy", "Поделиться логом") },
            enabled = redacted.isNotBlank(),
            variant = ArdttButtonVariant.Text,
            fillMaxWidth = true,
        )
        if (showLog) {
            ArdttTerminalCard(
                text = redacted.ifBlank { log.takeLast(24).joinToString("\n") },
                maxHeight = 200.dp,
            )
        }
    }
    if (showDiskCleanupConfirm && onDiskCleanupRetry != null) {
        ArdttDialog(
            title = "Очистить место на VPS?",
            onDismissRequest = { showDiskCleanupConfirm = false },
            confirmAction = ArdttDialogAction(
                text = "Очистить и установить",
                onClick = {
                    showDiskCleanupConfirm = false
                    onDiskCleanupRetry()
                },
            ),
            dismissAction = ArdttDialogAction(
                text = "Отмена",
                onClick = { showDiskCleanupConfirm = false },
            ),
        ) {
            Text(
                "Будут очищены: большие логи Docker, кэш apt, неиспользуемые linux-headers, хвосты неудачной установки ARDTT. Не трогаем: /opt/ardtt/data, работающие контейнеры, образы в использовании.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
@Composable
private fun DeployHopSlotCard(
    slot: DeploySlotView,
    isUpdate: Boolean,
    isUninstall: Boolean,
) {
    val outline = MaterialTheme.colorScheme.outline
    val borderColor = when (slot.phase) {
        DeploySlotPhase.Done -> ArdttColors.Connected
        DeploySlotPhase.Failed -> MaterialTheme.colorScheme.error
        DeploySlotPhase.Skipped,
        DeploySlotPhase.Pending,
        DeploySlotPhase.Active,
        -> hopMapGrayStroke(outline)
    }
    val statusColor = when (slot.phase) {
        DeploySlotPhase.Done -> connectedStatusColor()
        DeploySlotPhase.Failed -> MaterialTheme.colorScheme.error
        DeploySlotPhase.Active -> MaterialTheme.colorScheme.onSurface
        DeploySlotPhase.Pending, DeploySlotPhase.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ArdttSectionCard(
        contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.SmallPlus),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
        shape = ArdttShapes.Chip,
        shadowElevation = ArdttElevation.None,
        tonalElevation = ArdttElevation.None,
        border = BorderStroke(ArdttSectionCardDefaults.ContourWidth, borderColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                slot.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                deploySlotStatusText(slot.phase, isUpdate, isUninstall),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
            )
        }
        Text(
            slot.host,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
