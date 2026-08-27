package com.nonamevpn.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ServersRepository
import kotlinx.coroutines.launch

@Composable
fun DeployScreen(
    serversRepo: ServersRepository,
    engine: DeployEngine,
    initial: DeployTarget? = null,
    onSaved: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()

    var id by remember { mutableStateOf(initial?.id ?: serversRepo.newId()) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var sshPort by remember { mutableStateOf((initial?.sshPort ?: 22).toString()) }
    var sshUser by remember { mutableStateOf(initial?.sshUser ?: "root") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember { mutableStateOf(initial?.privateKeyPem ?: "") }
    var keyPass by remember { mutableStateOf(initial?.keyPassphrase ?: "") }
    var publicHost by remember { mutableStateOf(initial?.publicHost ?: "") }
    var directPort by remember { mutableStateOf((initial?.directPort ?: 51820).toString()) }
    var bypassPort by remember { mutableStateOf((initial?.bypassPort ?: 56003).toString()) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initial?.id) {
        val t = initial ?: return@LaunchedEffect
        id = t.id
        name = t.name
        host = t.host
        sshPort = t.sshPort.toString()
        sshUser = t.sshUser
        password = t.password
        privateKey = t.privateKeyPem
        keyPass = t.keyPassphrase
        publicHost = t.publicHost
        directPort = t.directPort.toString()
        bypassPort = t.bypassPort.toString()
    }

    fun buildTarget(): DeployTarget = DeployTarget(
        id = id,
        name = name.ifBlank { host },
        host = host.trim(),
        sshPort = sshPort.toIntOrNull() ?: 22,
        sshUser = sshUser.trim().ifBlank { "root" },
        password = password,
        privateKeyPem = privateKey.trim(),
        keyPassphrase = keyPass,
        sudoPassword = password,
        publicHost = publicHost.trim().ifBlank { host.trim() },
        directPort = directPort.toIntOrNull() ?: 51820,
        bypassPort = bypassPort.toIntOrNull() ?: 56003,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Деплой", style = MaterialTheme.typography.headlineMedium)
        Text(
            "«Сохранить» только добавляет VPS в список. Установка стека — кнопка «Установить на VPS».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя сервера") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("SSH host / IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("SSH порт") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                label = { Text("SSH user") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !busy,
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль (или sudo)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = privateKey,
            onValueChange = { privateKey = it },
            label = { Text("SSH private key PEM (опционально)") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp),
            minLines = 3,
            enabled = !busy,
        )
        if (privateKey.isNotBlank()) {
            OutlinedTextField(
                value = keyPass,
                onValueChange = { keyPass = it },
                label = { Text("Passphrase ключа") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
            )
        }
        OutlinedTextField(
            value = publicHost,
            onValueChange = { publicHost = it },
            label = { Text("Публичный host для профиля") },
            placeholder = { Text("Как в NVPN_PUBLIC_HOST, обычно = IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = directPort,
                onValueChange = { directPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Direct UDP") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = bypassPort,
                onValueChange = { bypassPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Bypass UDP") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
        }

        OutlinedButton(
            onClick = {
                if (host.isBlank()) {
                    status = "Укажите host"
                    return@OutlinedButton
                }
                if (password.isBlank() && privateKey.isBlank()) {
                    status = "Нужен пароль или SSH-ключ"
                    return@OutlinedButton
                }
                serversRepo.upsert(buildTarget())
                status = "Сервер сохранён — возврат к списку…"
                onSaved()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить сервер")
        }

        Button(
            onClick = {
                if (host.isBlank()) {
                    status = "Укажите host"
                    return@Button
                }
                if (password.isBlank() && privateKey.isBlank()) {
                    status = "Нужен пароль или SSH-ключ"
                    return@Button
                }
                val target = buildTarget()
                serversRepo.upsert(target)
                scope.launch {
                    status = null
                    val result = engine.deploy(target)
                    status = result.fold(
                        onSuccess = { it },
                        onFailure = { "Ошибка: ${it.message}" },
                    )
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Установка…" else "Установить на VPS")
        }

        OutlinedButton(
            onClick = onBack,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("К списку серверов")
        }

        if (busy) {
            OutlinedButton(
                onClick = { engine.cancel() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Отменить SSH") }
        }

        if (busy || progress > 0f) {
            Text(step.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        status?.let {
            Text(
                it,
                color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        if (log.isNotEmpty()) {
            Text("Лог", style = MaterialTheme.typography.titleMedium)
            Text(
                log.takeLast(80).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ServersScreen(
    serversRepo: ServersRepository,
    onDeploy: (DeployTarget?) -> Unit,
) {
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Серверы", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Сохранённые VPS для деплоя. Профили пользователей — через provision после установки.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Button(
            onClick = { onDeploy(null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить / деплой нового") }

        if (servers.isEmpty()) {
            Text("Пока нет сохранённых серверов.", style = MaterialTheme.typography.bodyLarge)
        } else {
            servers.forEach { s ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${s.sshUser}@${s.host}:${s.sshPort} · pub ${s.publicHost.ifBlank { s.host }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDeploy(s) }) { Text("Деплой") }
                        OutlinedButton(onClick = { serversRepo.delete(s.id) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}
