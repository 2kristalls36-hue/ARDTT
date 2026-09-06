package com.ardtt.lab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ardtt.lab.LabTarget
import com.ardtt.lab.LabUiState

private val Navy = Color(0xFF031D3B)
private val Panel = Color(0xFF0B2B52)
private val Accent = Color(0xFF5CA9E6)
private val Ok = Color(0xFF4CAF50)
private val Warn = Color(0xFFFFA726)
private val Danger = Color(0xFFEF5350)

@Composable
fun LabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Navy,
            surface = Panel,
            onBackground = Color(0xFFE8F4FF),
            onSurface = Color(0xFFE8F4FF),
        ),
        content = content,
    )
}

@Composable
fun LabScreen(
    state: LabUiState,
    onConnect: (LabTarget) -> Unit,
    onDisconnect: () -> Unit,
    onAllowScreen: () -> Unit,
) {
    var host by remember(state.host) { mutableStateOf(state.host) }
    var sshPort by remember(state.sshPort) { mutableStateOf(state.sshPort) }
    var user by remember(state.user) { mutableStateOf(state.user) }
    var password by remember(state.password) { mutableStateOf(state.password) }
    var remotePort by remember(state.remotePort) { mutableStateOf(state.remotePort) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ARDTT Lab", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            "Отдельное приложение. ARDTT не настраивается. " +
                "Lab выходит на сервер по SSH, агент заходит туда же и видит телефон вживую.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB9D4EE),
        )
        Field("Сервер", host, { host = it }, KeyboardType.Uri)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Field("Порт SSH", sshPort, { sshPort = it }, KeyboardType.Number, Modifier.weight(1f))
            Field("Порт на сервере", remotePort, { remotePort = it }, KeyboardType.Number, Modifier.weight(1f))
        }
        Field("Пользователь", user, { user = it }, KeyboardType.Text)
        Field("Пароль", password, { password = it }, KeyboardType.Password, password = true)

        val statusColor = when {
            state.online && state.agentSeen -> Ok
            state.online -> Accent
            state.connecting -> Warn
            state.lastError != null -> Danger
            else -> Color(0xFF8AA4BD)
        }
        val statusText = when {
            state.online && state.agentSeen -> "На связи, агент работает"
            state.online -> "Туннель поднят. На сервере: ardtt-lab attach"
            state.connecting -> "Подключение…"
            state.lastError != null -> state.lastError
            else -> "Не подключено"
        }
        Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(statusText, color = statusColor, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Wi‑Fi: ${if (state.wifiOn) "вкл" else "выкл"}   " +
                        "LTE: ${if (state.cellular) state.operator.ifBlank { "есть" } else "нет"}   " +
                        "VPN: ${if (state.vpn) "да" else "нет"}   " +
                        "ARDTT: ${if (state.ardttInstalled) "стоит" else "нет"}",
                    color = Color(0xFFB9D4EE),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.wifiOn) {
                    Text(
                        "Для проверки белого списка выключите Wi‑Fi. " +
                            "Иначе ARDTT сам возьмёт прямое, без всякой перенастройки.",
                        color = Warn,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.online || state.connecting) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    modifier = Modifier.weight(1f),
                ) { Text("Отключить") }
            } else {
                Button(
                    onClick = {
                        onConnect(
                            LabTarget(
                                host = host.trim(),
                                sshPort = sshPort.toIntOrNull() ?: 22,
                                user = user.trim(),
                                password = password,
                                remotePort = remotePort.toIntOrNull() ?: 7422,
                            ),
                        )
                    },
                    enabled = host.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Подключить") }
            }
            OutlinedButton(onClick = onAllowScreen, modifier = Modifier.weight(1f)) {
                Text(if (state.screenGranted) "Экран есть" else "Разрешить экран")
            }
        }

        Text("Журнал", color = Color(0xFF8AA4BD), style = MaterialTheme.typography.labelLarge)
        Surface(
            color = Color(0xFF021225),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        ) {
            Text(
                text = state.log.asReversed().joinToString("\n").ifBlank { "пока тихо" },
                color = Color(0xFFC5D7E8),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Color(0xFF3A5A7A),
            focusedLabelColor = Accent,
            cursorColor = Accent,
        ),
    )
}
