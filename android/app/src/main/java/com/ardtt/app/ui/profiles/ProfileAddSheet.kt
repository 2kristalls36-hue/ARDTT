package com.ardtt.app.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.components.surface.ArdttBottomSheet
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSpacing

/** Leading glyph of an add-profile option; between the icon and icon-large steps. */
private val OptionIconSize = 26.dp

private data class ProfileAddOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun ProfileAddSheet(
    onDismissRequest: () -> Unit,
    onSubscription: () -> Unit,
    onManual: () -> Unit,
    onFromFile: () -> Unit,
    onFromClipboard: () -> Unit,
    onScanQr: () -> Unit,
) {
    val options = listOf(
        ProfileAddOption(
            title = "Подписка",
            subtitle = "Профили по адресу JSON на сервере",
            icon = Icons.Default.RssFeed,
            onClick = { onDismissRequest(); onSubscription() },
        ),
        ProfileAddOption(
            title = "Вручную",
            subtitle = "Вставить JSON или ссылку ardtt://",
            icon = Icons.Default.Add,
            onClick = { onDismissRequest(); onManual() },
        ),
        ProfileAddOption(
            title = "Из файла",
            subtitle = "Выбрать файл конфигурации на устройстве",
            icon = Icons.Default.FolderOpen,
            onClick = { onDismissRequest(); onFromFile() },
        ),
        ProfileAddOption(
            title = "Из буфера",
            subtitle = "Вставить скопированную ссылку или JSON",
            icon = Icons.Default.ContentPaste,
            onClick = { onDismissRequest(); onFromClipboard() },
        ),
        ProfileAddOption(
            title = "Сканировать QR-код",
            subtitle = "Считать профиль с другого устройства",
            icon = Icons.Default.QrCodeScanner,
            onClick = { onDismissRequest(); onScanQr() },
        ),
    )

    ArdttBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.padding(horizontal = ArdttSpacing.XLarge),
        scrollable = false,
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
    ) {
        Text(
            "Добавить",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = ArdttSpacing.Small),
        )
        options.forEachIndexed { index, option ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                        .copy(alpha = ArdttAlpha.Divider),
                )
            }
            ProfileAddRow(option)
        }
    }
}

@Composable
private fun ProfileAddRow(option: ProfileAddOption) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = option.onClick)
            .padding(vertical = ArdttSpacing.MediumPlus),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Large),
    ) {
        Icon(
            option.icon,
            contentDescription = null,
            modifier = Modifier.size(OptionIconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
        ) {
            Text(
                option.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                option.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
