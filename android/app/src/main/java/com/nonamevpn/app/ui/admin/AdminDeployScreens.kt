package com.nonamevpn.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.needsNotificationPermission
import com.nonamevpn.app.deploy.DeployBundle
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.deploy.ServerOsProbe
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedColumn
import com.nonamevpn.app.ui.components.EdgeFeedTopInset
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import kotlinx.coroutines.launch

private enum class ServersPane {
    List,
    Overview,
    Deploy,
    Users,
}

/** Nested Servers: List → Overview (Deploy / Users / Delete) → Deploy or Users. */
@Composable
fun ServersHub(
    serversRepo: ServersRepository,
    deployEngine: DeployEngine,
    profiles: ProfileRepository,
) {
    var pane by remember { mutableStateOf(ServersPane.List) }
    var selected by remember { mutableStateOf<DeployTarget?>(null) }
    var deployInitial by remember { mutableStateOf<DeployTarget?>(null) }

    when (pane) {
        ServersPane.List -> ServersListPane(
            serversRepo = serversRepo,
            onAdd = {
                selected = null
                deployInitial = null
                pane = ServersPane.Deploy
            },
            onOpen = { target ->
                selected = target
                pane = ServersPane.Overview
            },
        )
        ServersPane.Overview -> {
            val target = selected
            if (target == null) {
                pane = ServersPane.List
            } else {
                ServerOverviewPane(
                    target = target,
                    onBack = {
                        selected = null
                        pane = ServersPane.List
                    },
                    onDeploy = {
                        deployInitial = target
                        pane = ServersPane.Deploy
                    },
                    onUsers = { pane = ServersPane.Users },
                    onDelete = {
                        serversRepo.delete(target.id)
                        selected = null
                        pane = ServersPane.List
                    },
                )
            }
        }
        ServersPane.Deploy -> DeployScreen(
            serversRepo = serversRepo,
            engine = deployEngine,
            initial = deployInitial,
            onBack = {
                pane = if (selected != null) ServersPane.Overview else ServersPane.List
            },
            onSaved = { saved ->
                selected = saved
                deployInitial = saved
            },
            onOpenUsers = { saved ->
                selected = saved
                deployInitial = saved
                pane = ServersPane.Users
            },
        )
        ServersPane.Users -> {
            val target = selected
            if (target == null) {
                pane = ServersPane.List
            } else {
                UsersScreen(
                    target = target,
                    profiles = profiles,
                    onBack = { pane = ServersPane.Overview },
                )
            }
        }
    }
}

@Composable
private fun ServersListPane(
    serversRepo: ServersRepository,
    onAdd: () -> Unit,
    onOpen: (DeployTarget) -> Unit,
) {
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = NvpnBottomChrome.navigationReserve() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EdgeFeedTopInset()
        Text(
            "Серверы",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "VPS для деплоя и пользователей. Откройте сервер для деплоя или списка users.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить / деплой нового") }

        if (servers.isEmpty()) {
            Text("Пока нет сохранённых серверов.", style = MaterialTheme.typography.bodyLarge)
        } else {
            servers.forEach { s ->
                AppSectionCard(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable { onOpen(s) },
                ) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${s.sshUser}@${s.host}:${s.sshPort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "pub ${s.publicHost.ifBlank { s.host }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ServerOsBadge(
                            osId = s.osId,
                            osVersion = s.osVersion,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerOverviewPane(
    target: DeployTarget,
    onBack: () -> Unit,
    onDeploy: () -> Unit,
    onUsers: () -> Unit,
    onDelete: () -> Unit,
) {
    EdgeFeedColumn(
        header = {
            AppPageHeader(
                title = target.name,
                subtitle = "${target.sshUser}@${target.host}:${target.sshPort}",
                onBack = onBack,
                pinBelowStatusBar = false,
            )
        },
    ) {
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${target.sshUser}@${target.host}:${target.sshPort}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Публичный host: ${target.publicHost.ifBlank { target.host }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ServerOsBadge(
                    osId = target.osId,
                    osVersion = target.osVersion,
                )
            }
            Text(
                "Direct ${target.directPort} · Bypass ${target.bypassPort}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onDeploy, modifier = Modifier.fillMaxWidth()) { Text("Деплой") }
        OutlinedButton(onClick = onUsers, modifier = Modifier.fillMaxWidth()) { Text("Пользователи") }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Удалить сервер") }
    }
}

@Composable
fun DeployScreen(
    serversRepo: ServersRepository,
    engine: DeployEngine,
    initial: DeployTarget? = null,
    onBack: (() -> Unit)? = null,
    onSaved: ((DeployTarget) -> Unit)? = null,
    onOpenUsers: ((DeployTarget) -> Unit)? = null,
) {
    val startDeploy = rememberStartDeploy(engine)
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()
    val outcome by engine.outcome.collectAsStateWithLifecycle()

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
    var cascadeEnabled by remember { mutableStateOf(initial?.cascadeEnabled == true) }
    var cascadeHost by remember { mutableStateOf(initial?.cascadeHost ?: "") }
    var cascadePort by remember { mutableStateOf((initial?.cascadePort ?: 22).toString()) }
    var cascadeUser by remember { mutableStateOf(initial?.cascadeUser ?: "") }
    var cascadePassword by remember { mutableStateOf(initial?.cascadePassword ?: "") }
    var osId by remember { mutableStateOf(initial?.osId ?: "") }
    var osVersion by remember { mutableStateOf(initial?.osVersion ?: "") }
    var lastDeployedAtMs by remember { mutableStateOf(initial?.lastDeployedAtMs ?: 0L) }
    var status by remember { mutableStateOf<String?>(null) }
    var deployOk by remember { mutableStateOf(false) }

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
        cascadeEnabled = t.cascadeEnabled
        cascadeHost = t.cascadeHost
        cascadePort = t.cascadePort.toString()
        cascadeUser = t.cascadeUser
        cascadePassword = t.cascadePassword
        osId = t.osId
        osVersion = t.osVersion
        lastDeployedAtMs = t.lastDeployedAtMs
    }

    LaunchedEffect(busy, outcome) {
        if (!busy && outcome != null) {
            status = outcome
            val deployedAt = serversRepo.snapshot().find { it.id == id }?.lastDeployedAtMs
            if (deployedAt != null && deployedAt > 0L) lastDeployedAtMs = deployedAt
        }
    }

    fun buildTarget(deployedAt: Long = lastDeployedAtMs): DeployTarget = DeployTarget(
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
        cascadeEnabled = cascadeEnabled,
        cascadeHost = cascadeHost.trim(),
        cascadePort = cascadePort.toIntOrNull() ?: 22,
        cascadeUser = cascadeUser.trim(),
        cascadePassword = cascadePassword,
        osId = osId.trim(),
        osVersion = osVersion.trim(),
        lastDeployedAtMs = deployedAt,
    )

    val context = LocalContext.current
    val expectedDeployVersion = remember(context) { DeployBundle.expectedVersion(context) }
    val isUpdate = initial != null && lastDeployedAtMs > 0L

    EdgeFeedColumn(
        header = {
            AppPageHeader(
                title = "Деплой",
                subtitle = "SSH-установка стека на VPS (Docker: provision / direct / bypass / warp). После успеха — пользователи через provision :9100.",
                onBack = onBack,
                pinBelowStatusBar = false,
            )
        },
    ) {
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SSH", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Сеть / порты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = publicHost,
                onValueChange = { publicHost = it },
                label = { Text("Публичный host для профиля") },
                placeholder = { Text("Обычно = IP VPS") },
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
            Text(
                "Provision API: TCP 9100 (создание пользователей без лимитов дней/устройств).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Действия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                    val target = buildTarget()
                    serversRepo.upsert(target)
                    onSaved?.invoke(target)
                    status = "Сервер сохранён"
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
                    onSaved?.invoke(target)
                    scope.launch {
                        status = null
                        deployOk = false
                        val result = engine.deploy(target)
                        status = result.fold(
                            onSuccess = {
                                deployOk = true
                                "Установка завершена. Можно создавать пользователей."
                            },
                            onFailure = { "Ошибка: ${it.message}" },
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Установка…" else "Установить на VPS")
            }

            if (deployOk && onOpenUsers != null) {
                Button(
                    onClick = { onOpenUsers(buildTarget()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("К пользователям") }
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
        }

        if (log.isNotEmpty()) {
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Лог", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    log.takeLast(80).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Legacy entry kept for any external call sites — prefer [ServersHub]. */
@Composable
fun ServersScreen(
    serversRepo: ServersRepository,
    onDeploy: (DeployTarget?) -> Unit,
) {
    ServersListPane(
        serversRepo = serversRepo,
        onAdd = { onDeploy(null) },
        onOpen = { onDeploy(it) },
    )
}

@Composable
private fun ServerOsBadge(
    osId: String,
    osVersion: String,
) {
    val normalized = osId.trim().lowercase()
    val symbol = when {
        normalized.contains("ubuntu") -> "🟠"
        normalized.contains("debian") -> "🔴"
        normalized.contains("alpine") -> "🔷"
        normalized.contains("arch") -> "⚫"
        normalized.contains("centos") ||
            normalized.contains("rhel") ||
            normalized.contains("rocky") ||
            normalized.contains("alma") ||
            normalized.contains("fedora") ||
            normalized.contains("suse") ||
            normalized.contains("linux") -> "🐧"
        normalized.contains("windows") -> "🪟"
        normalized.contains("darwin") || normalized.contains("mac") -> "🍎"
        osVersion.isNotBlank() -> "🖥️"
        else -> "🖥️"
    }
    val label = osId.ifBlank { "OS" }.uppercase()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            "$symbol $label",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
