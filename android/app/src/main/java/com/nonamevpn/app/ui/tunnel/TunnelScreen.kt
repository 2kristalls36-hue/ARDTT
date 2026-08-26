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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.settings.AppSettingsRepository
import kotlinx.coroutines.launch

@Composable
fun TunnelScreen(settings: AppSettingsRepository) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val profile by settings.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Demo endpoints until profile import lands; empty → UDP-lite skipped, provision skipped.
        conn.updateEndpoints(directEndpoint = null, provisionUrl = null)
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
            onClick = {
                scope.launch { settings.setProfileName("demo-home") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Импорт профиля (заглушка)")
        }
    }
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
