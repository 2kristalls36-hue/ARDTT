package com.nonamevpn.app.ui.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.settings.AppSettingsRepository
import kotlinx.coroutines.launch

/**
 * User-facing tunnel screen (scaffold).
 * Probe / AWG / RAW backends will plug into ConnectionManager later.
 */
@Composable
fun TunnelScreen(settings: AppSettingsRepository) {
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val profile by settings.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var pathLabel by remember { mutableStateOf("Готово: прямое (каркас)") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("nonameVPN", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (profile.isBlank()) "Профиль не импортирован" else "Профиль: $profile",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сеть", style = MaterialTheme.typography.titleMedium)
                Text(pathLabel)
                Text(
                    if (connected) "Подключено (заглушка)" else "Отключено",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                RowSwitch(
                    title = "Скрыть свой IP",
                    subtitle = "Выход через WARP на VPS",
                    checked = hideIp,
                    onCheckedChange = { scope.launch { settings.setHideIp(it) } },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { connected = !connected },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (connected) "Отключить" else "Подключить")
        }

        OutlinedButton(
            onClick = {
                scope.launch { settings.setProfileName("demo-home") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Импорт профиля (заглушка)")
        }
    }
}

@Composable
private fun RowSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
