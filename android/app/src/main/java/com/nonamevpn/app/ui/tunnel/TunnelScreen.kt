package com.nonamevpn.app.ui.tunnel

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import kotlinx.coroutines.launch

@Composable
fun TunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile) {
        val p = profile
        if (p != null) {
            val tunAddr = when (p.prefer) {
                "bypass" -> p.bypass.address
                else -> p.direct.address
            }
            conn.updateEndpoints(
                directEndpoint = p.direct.endpoint,
                provisionUrl = p.provisionBaseUrl,
                tunAddress = tunAddr,
            )
            settings.setProfileName(p.name)
            if (p.hideIp) settings.setHideIp(true)
        } else {
            conn.updateEndpoints(null, null, null)
        }
        conn.startInitialProbe()
    }

    LaunchedEffect(hideIp) {
        conn.setHideIp(hideIp)
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            conn.connect()
        }
    }

    fun requestConnect() {
        val prep = VpnService.prepare(context)
        if (prep != null) {
            vpnPermission.launch(prep)
        } else {
            conn.connect()
        }
    }

    val connected = ui.state == ConnState.Connected
    val busy = ui.state == ConnState.Probing ||
        ui.state == ConnState.Connecting ||
        ui.state == ConnState.Disconnecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("nonameVPN", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (profile == null) {
                "Профиль не импортирован"
            } else {
                "Профиль: ${profile!!.name} · host ${profile!!.hostId}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        profile?.let { p ->
            Text(
                "Direct ${p.direct.endpoint} · Bypass ${p.bypass.peer}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сеть", style = MaterialTheme.typography.titleMedium)
                Text(ui.statusText)
                Text(
                    when (ui.state) {
                        ConnState.Idle -> "Ожидание"
                        ConnState.Probing -> "Проверка…"
                        ConnState.Ready -> "Готово к подключению"
                        ConnState.Connecting -> "Подключение…"
                        ConnState.Connected -> pathStatus(ui.activePath)
                        ConnState.Disconnecting -> "Отключение…"
                        ConnState.Error -> "Ошибка"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                ui.probe?.let { p ->
                    Text(
                        detailLine(p.networkClass, p.yandexOk, p.bigtechOk, p.vpsUdpOk, p.elapsedMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                ui.softInfo?.let { info ->
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                ui.lastError?.takeIf { ui.state == ConnState.Error }?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
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
            onClick = {
                if (connected) conn.disconnect() else requestConnect()
            },
            enabled = !busy && (connected || ui.connectEnabled),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                when {
                    connected -> "Отключить"
                    ui.state == ConnState.Probing -> "Проверка сети…"
                    else -> "Подключить"
                },
            )
        }

        OutlinedButton(
            onClick = { conn.startInitialProbe() },
            enabled = !busy && !connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Проверить сеть снова")
        }

        OutlinedButton(
            onClick = { showImport = true },
            enabled = !connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Импорт профиля…")
        }

        if (profile != null) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        profiles.clear()
                        settings.setProfileName("")
                    }
                },
                enabled = !connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Удалить профиль")
            }
        }
    }

    if (showImport) {
        ImportProfileDialog(
            error = importError,
            onDismiss = {
                showImport = false
                importError = null
            },
            onDemo = {
                scope.launch {
                    runCatching {
                        profiles.importDemo()
                        showImport = false
                        importError = null
                    }.onFailure { importError = it.message ?: "Ошибка импорта" }
                }
            },
            onPaste = { raw ->
                scope.launch {
                    runCatching {
                        profiles.importJson(raw)
                        showImport = false
                        importError = null
                    }.onFailure { importError = it.message ?: "Неверный JSON профиля" }
                }
            },
        )
    }
}

@Composable
private fun ImportProfileDialog(
    error: String?,
    onDismiss: () -> Unit,
    onDemo: () -> Unit,
    onPaste: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт профиля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Вставьте JSON от provision (create-user) или загрузите демо.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    label = { Text("JSON") },
                    minLines = 5,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPaste(text) },
                enabled = text.isNotBlank(),
            ) { Text("Импортировать") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDemo) { Text("Демо-профиль") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

private fun pathStatus(path: VpnPath?): String = when (path) {
    VpnPath.Direct -> "Подключено: прямое"
    VpnPath.Bypass -> "Подключено: обход"
    null -> "Подключено"
}

private fun detailLine(
    networkClass: NetworkClass,
    yandexOk: Boolean,
    bigtechOk: Boolean,
    vpsUdpOk: Boolean,
    elapsedMs: Long,
): String {
    val bits = buildList {
        add(networkClass.name)
        add("yandex=${if (yandexOk) "ok" else "—"}")
        add("bigtech=${if (bigtechOk) "ok" else "—"}")
        add("udp=${if (vpsUdpOk) "ok" else "—"}")
        if (elapsedMs > 0) add("${elapsedMs}ms")
    }
    return bits.joinToString(" · ")
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
