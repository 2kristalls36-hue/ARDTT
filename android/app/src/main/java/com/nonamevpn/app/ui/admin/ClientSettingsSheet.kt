package com.nonamevpn.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.deploy.deviceDisplayLabels
import com.nonamevpn.app.profile.ProfileLinkCodec
import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.ui.profiles.QrCodeImage

@OptIn(ExperimentalMaterial3Api::class)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val link = profile?.let { ProfileLinkCodec.buildLink(it) }.orEmpty()
    val deviceLabels = deviceDisplayLabels(user.deviceIds, user.deviceModels)
    val offlineTime = if (user.online) "—" else formatOfflineDuration(user.offlineForSec)
    val ip = user.lastExternalIp.trim().ifBlank { "—" }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    user.name.ifBlank { "Клиент" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onEditName, enabled = !busy) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Изменить имя",
                        tint = colors.onSurface,
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loadingProfile -> {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    }
                    link.isNotBlank() -> {
                        QrCodeImage(
                            content = link,
                            modifier = Modifier.padding(horizontal = 22.dp),
                        )
                    }
                }
            }

            if (link.isNotBlank()) {
                SheetCopyRow(
                    modifier = Modifier.padding(horizontal = 22.dp),
                    label = "Ссылка ARDTT",
                    value = link,
                    onCopy = {
                        copyText(context, "ARDTT", link)
                        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                            putExtra(Intent.EXTRA_SUBJECT, user.name)
                        }
                        context.startActivity(Intent.createChooser(send, "Отправить ссылку"))
                    },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 22.dp),
                color = colors.outlineVariant.copy(alpha = 0.55f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetStat(
                    label = "Устройства",
                    value = "${user.deviceIds.size}/${user.maxDevices}",
                    modifier = Modifier.weight(1f),
                )
                SheetStat(
                    label = "Трафик",
                    value = clientTrafficLabel(user),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetStat(
                    label = "Режим",
                    value = clientModeLabel(user),
                    modifier = Modifier.weight(1f),
                )
                SheetStat(
                    label = "Время в оффлайне",
                    value = offlineTime,
                    modifier = Modifier.weight(1f),
                )
            }
            SheetStat(
                label = "IP-адрес",
                value = ip,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            SheetStat(
                label = "Устройство",
                value = clientDeviceSummary(user),
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            SheetStat(
                label = "Версия приложения",
                value = clientAppVersionView(user, latestVersionCode).label,
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 22.dp),
                color = colors.outlineVariant.copy(alpha = 0.55f),
            )

            Text(
                "Привязанные устройства",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            if (user.deviceIds.isEmpty()) {
                Text(
                    "Нет привязанных устройств",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            } else {
                user.deviceIds.forEachIndexed { index, id ->
                    val label = deviceLabels.getOrNull(index)?.ifBlank { null } ?: id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { onUnbindDevice(id) },
                            enabled = !busy,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Отвязать",
                                tint = colors.error,
                            )
                        }
                    }
                }
            }

            if (profile != null) {
                OutlinedButton(
                    onClick = onAddToPhone,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Добавить на этот телефон")
                }
            }
        }
    }
}

@Composable
private fun SheetStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SheetCopyRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Поделиться")
            }
        }
    }
}

private fun copyText(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}
