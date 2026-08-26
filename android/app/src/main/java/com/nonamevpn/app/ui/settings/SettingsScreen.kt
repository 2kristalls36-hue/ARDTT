package com.nonamevpn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.settings.AppSettingsRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettingsRepository) {
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val hasPin by settings.hasAdminPin.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

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
            "Пользовательский режим намеренно минимален. AWG/RAW/probe появятся в следующих итерациях.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}
