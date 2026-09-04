package com.ardtt.app.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ProfileAddOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileAddSheet(
    onDismissRequest: () -> Unit,
    onSubscription: () -> Unit,
    onManual: () -> Unit,
    onFromFile: () -> Unit,
    onFromClipboard: () -> Unit,
    onScanQr: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Добавить",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEachIndexed { index, option ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = option.onClick)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        option.icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        }
    }
}
