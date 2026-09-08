package com.ardtt.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.deviceDisplayLabels
import com.ardtt.app.profile.ProfileLinkCodec
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.feedback.ArdttCopyRow
import com.ardtt.app.ui.components.feedback.ArdttStackedFactRow
import com.ardtt.app.ui.components.surface.ArdttBottomSheet
import com.ardtt.app.ui.components.surface.ArdttQrCode
import com.ardtt.app.ui.components.surface.ArdttSectionTitle
import com.ardtt.app.ui.components.surface.ArdttSheetDefaults
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.shareText

/** Rim padding for every row of this sheet. */
private val SheetPadding = Modifier.padding(horizontal = ArdttSheetDefaults.HorizontalPadding)

@Composable
internal fun ClientSettingsSheet(
    user: ProvisionAdminApi.UserSummary,
    latestVersionCode: Int,
    profile: VpnProfile?,
    loadingProfile: Boolean,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onEditName: () -> Unit,
    onUnbindDevice: (String) -> Unit,
    onAddToPhone: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val link = profile?.let { ProfileLinkCodec.buildLink(it) }.orEmpty()
    val deviceLabels = deviceDisplayLabels(user.deviceIds, user.deviceModels)

    ArdttBottomSheet(onDismissRequest = onDismissRequest) {
        Row(
            modifier = SheetPadding.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                user.name.ifBlank { "Клиент" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onEditName, enabled = !busy) {
                Icon(Icons.Filled.Edit, contentDescription = "Изменить имя")
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loadingProfile -> CircularProgressIndicator(
                    modifier = Modifier.size(ArdttSize.SpinnerLarge),
                    strokeWidth = ArdttSize.Stroke,
                )
                link.isNotBlank() -> ArdttQrCode(
                    content = link,
                    modifier = SheetPadding,
                    contentDescription = "QR-код профиля клиента",
                )
            }
        }

        if (link.isNotBlank()) {
            ArdttCopyRow(
                label = "Ссылка ARDTT",
                value = link,
                modifier = SheetPadding,
                onCopy = { copyToClipboard(context, link, "ARDTT", "Ссылка скопирована") },
                onShare = { shareText(context, link, user.name, "Отправить ссылку") },
            )
        }

        SheetDivider()

        SheetStatPair(
            first = "Устройства" to "${user.deviceIds.size}/${user.maxDevices}",
            second = "Трафик" to clientTrafficLabel(user),
        )
        SheetStatPair(
            first = "Режим" to clientModeLabel(user),
            second = "Время в оффлайне" to
                if (user.online) "—" else formatOfflineDuration(user.offlineForSec),
        )
        ArdttStackedFactRow(
            label = "IP-адрес",
            value = user.lastExternalIp.trim().ifBlank { "—" },
            modifier = SheetPadding,
        )
        ArdttStackedFactRow(
            label = "Устройство",
            value = clientDeviceSummary(user),
            modifier = SheetPadding,
        )
        ArdttStackedFactRow(
            label = "Версия приложения",
            value = clientAppVersionView(user, latestVersionCode).label,
            modifier = SheetPadding,
        )

        SheetDivider()

        ArdttSectionTitle("Привязанные устройства", modifier = SheetPadding)
        if (user.deviceIds.isEmpty()) {
            Text(
                "Нет привязанных устройств",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = SheetPadding,
            )
        } else {
            user.deviceIds.forEachIndexed { index, id ->
                BoundDeviceRow(
                    label = deviceLabels.getOrNull(index)?.ifBlank { null } ?: id,
                    version = clientDeviceAppVersion(user, id),
                    enabled = !busy,
                    onUnbind = { onUnbindDevice(id) },
                )
            }
        }

        if (profile != null) {
            ArdttButton(
                text = "Добавить на этот телефон",
                onClick = onAddToPhone,
                enabled = !busy,
                variant = ArdttButtonVariant.Outlined,
                modifier = SheetPadding,
            )
        }
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = SheetPadding,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ArdttAlpha.Muted),
    )
}

@Composable
private fun SheetStatPair(
    first: Pair<String, String>,
    second: Pair<String, String>,
) {
    Row(
        modifier = SheetPadding.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Large),
    ) {
        ArdttStackedFactRow(
            label = first.first,
            value = first.second,
            modifier = Modifier.weight(1f),
        )
        ArdttStackedFactRow(
            label = second.first,
            value = second.second,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BoundDeviceRow(
    label: String,
    version: String?,
    enabled: Boolean,
    onUnbind: () -> Unit,
) {
    Row(
        modifier = SheetPadding.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                version ?: "нет версии",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onUnbind, enabled = enabled) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Отвязать",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
