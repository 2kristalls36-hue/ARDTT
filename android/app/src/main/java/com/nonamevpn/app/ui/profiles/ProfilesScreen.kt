package com.nonamevpn.app.ui.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(profiles: ProfileRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by profiles.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by profiles.activeId.collectAsStateWithLifecycle(initialValue = null)
    var showImport by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    val pickProfileFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importBusy = true
            importError = null
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                profiles.importUri(uri)
            }.onSuccess { p ->
                hint = "Импортирован: ${p.name}"
                showImport = false
                importBusy = false
                AppLog.i("Profiles", "Imported ${p.name}")
            }.onFailure { e ->
                importBusy = false
                importError = e.message ?: "Не удалось импортировать"
                showImport = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Профили",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Активный профиль используется на вкладке Туннель.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                pickProfileFile.launch(
                    arrayOf(
                        "application/json",
                        "text/plain",
                        "text/*",
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            },
            enabled = !importBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (importBusy) "Читаем…" else "Импорт из файла…", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = { showImport = true },
            enabled = !importBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Вставить JSON…")
        }

        if (list.isEmpty()) {
            Text("Нет сохранённых профилей.", style = MaterialTheme.typography.bodyLarge)
        } else {
            list.forEach { p ->
                val key = ProfileRepository.profileKey(p)
                val isActive = key == activeId
                AppSectionCard(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "hostId=${p.hostId} · ${p.deviceId.ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                p.direct.endpoint.ifBlank { p.bypass.peer },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isActive) {
                            Text(
                                "активен",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        profiles.setActive(key)
                                        hint = "Активен: ${p.name}"
                                    }
                                },
                            ) { Text("Сделать активным") }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    profiles.delete(key)
                                    hint = "Удалён: ${p.name}"
                                }
                            },
                        ) { Text("Удалить") }
                    }
                }
            }
        }

        hint?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!importBusy) showImport = false },
            title = { Text("Импорт профиля") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !importBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        shape = RoundedCornerShape(16.dp),
                    )
                    importError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            importBusy = true
                            runCatching { profiles.importJson(text) }
                                .onSuccess {
                                    hint = "Импортирован: ${it.name}"
                                    showImport = false
                                    importBusy = false
                                    importError = null
                                }
                                .onFailure {
                                    importBusy = false
                                    importError = it.message ?: "Неверный JSON"
                                }
                        }
                    },
                    enabled = text.isNotBlank() && !importBusy,
                ) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }, enabled = !importBusy) { Text("Отмена") }
            },
            shape = RoundedCornerShape(24.dp),
        )
    }
}
