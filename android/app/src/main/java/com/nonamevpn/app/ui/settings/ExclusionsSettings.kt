package com.nonamevpn.app.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

@Composable
fun ExclusionsSettingsCard(settings: AppSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by settings.excludedAppsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val hosts by settings.excludedHostsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    var showAppPicker by remember { mutableStateOf(false) }
    var hostDraft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Исключения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Приложения и сайты идут мимо VPN (как split-tunnel). " +
                "Смена списка применяется при следующем подключении / soft-restart.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Приложения", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (apps.isEmpty()) {
            Text(
                "Нет исключений",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            apps.sorted().forEach { pkg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pkg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { settings.removeExcludedApp(pkg) } }) {
                        Text("Убрать")
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { showAppPicker = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("Выбрать приложения…")
        }

        Text("Сайты / IP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Домены резолвятся в IPv4 и исключаются из туннеля (excludeRoute)."
            } else {
                "Нужен Android 13+ для исключения сайтов. Список сохранится и применится после обновления ОС."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hosts.isEmpty()) {
            Text(
                "Нет исключений",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            hosts.sorted().forEach { host ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(host, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { settings.removeExcludedHost(host) } }) {
                        Text("Убрать")
                    }
                }
            }
        }
        OutlinedTextField(
            value = hostDraft,
            onValueChange = { hostDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("example.com или 1.2.3.4") },
        )
        OutlinedButton(
            onClick = {
                val raw = hostDraft
                scope.launch {
                    val clean = AppSettingsRepository.normalizeHost(raw)
                    if (clean.isBlank()) {
                        hint = "Введите домен или IPv4"
                        return@launch
                    }
                    settings.addExcludedHost(clean)
                    hostDraft = ""
                    hint = "Добавлено: $clean"
                    // Soft-restart if connected so TUN rebuilds with new excludes.
                    ConnectionManager.getOrNull()?.requestTransportRestart(
                        "Обновлены исключения сайтов",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("Добавить сайт / IP")
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = apps,
            onDismiss = { showAppPicker = false },
            onConfirm = { selected ->
                showAppPicker = false
                scope.launch {
                    settings.setExcludedApps(selected)
                    hint = "Приложений исключено: ${selected.size}"
                    ConnectionManager.getOrNull()?.requestTransportRestart(
                        "Обновлены исключения приложений",
                    )
                }
            },
        )
    }
}

@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var draft by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            // unused: flags kept for API symmetry
            @Suppress("UNUSED_VARIABLE")
            val flags = PackageManager.MATCH_ALL
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            installed
                .asSequence()
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info ->
                    LaunchableApp(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                    )
                }
                .filter { it.packageName != context.packageName }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
        loading = false
        draft = selected
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Исключить приложения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск") },
                )
                if (loading) {
                    Text("Загрузка списка…", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in draft
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draft = if (checked) draft - app.packageName else draft + app.packageName
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        draft = if (on) draft + app.packageName else draft - app.packageName
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
