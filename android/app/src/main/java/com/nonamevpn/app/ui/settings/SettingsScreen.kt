package com.nonamevpn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.settings.AppSettingsRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettingsRepository) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val testingMode by settings.testingModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val hasPin by settings.hasAdminPin.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val economy by settings.economyWorkersEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(silent, economy, dial) {
        conn.setSilentRecreate(silent)
        conn.setWorkers(if (economy) 1 else 3)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Режим: ${if (admin) "администратор" else "пользователь"}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Версия ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        Text("Обход", style = MaterialTheme.typography.titleMedium)
        Text(
            "Путь дозвона TURN",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Авто: vkcalls, при ошибке — legacy (капча). Connect остаётся анонимным по hash.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DialChip(
                label = "Авто",
                selected = dial == "auto",
                onClick = { scope.launch { settings.setDialPath("auto") } },
                modifier = Modifier.weight(1f),
            )
            DialChip(
                label = "vkcalls",
                selected = dial == "vkcalls",
                onClick = { scope.launch { settings.setDialPath("vkcalls") } },
                modifier = Modifier.weight(1f),
            )
            DialChip(
                label = "Капча",
                selected = dial == "legacy",
                onClick = { scope.launch { settings.setDialPath("legacy") } },
                modifier = Modifier.weight(1f),
            )
        }
        RowSetting(
            title = "Тихий recreate звонка",
            subtitle = "Без диалога, если hash «умер» (нужна сессия VK)",
            checked = silent,
            onCheckedChange = { scope.launch { settings.setSilentRecreate(it) } },
        )
        RowSetting(
            title = "Экономия workers",
            subtitle = "1 вместо 3 (медленнее, стабильнее на слабых сетях)",
            checked = economy,
            onCheckedChange = { scope.launch { settings.setEconomyWorkers(it) } },
        )

        Text("Для администратора", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasPin) {
                "Введите PIN, чтобы открыть логи, деплой и расширенные настройки."
            } else {
                "Задайте PIN администратора (первый ввод создаёт его)."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!admin) {
            Button(
                onClick = {
                    scope.launch {
                        if (pin.length < 4) {
                            message = "PIN не короче 4 цифр"
                            return@launch
                        }
                        val ok = settings.unlockAdmin(pin)
                        message = if (ok) "Режим админа включён" else "Неверный PIN"
                        if (ok) pin = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (hasPin) "Разблокировать админа" else "Создать PIN и войти")
            }
        } else {
            RowSetting(
                title = "Тестирование",
                subtitle = "Вкладка с полной телеметрией и записью логов",
                checked = testingMode,
                onCheckedChange = { scope.launch { settings.setTestingMode(it) } },
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        settings.lockAdmin()
                        message = "Снова режим пользователя"
                        pin = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Выйти из режима админа")
            }
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Text(
            "Path A/B backends подключены в VpnService. Native AWG и TURN/vkcalls HTTP — следующие слои.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun DialChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
    )
}

@Composable
private fun RowSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
