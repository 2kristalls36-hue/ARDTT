package com.nonamevpn.app.ui.admin

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.profile.NetworkEndpoint
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.telemetry.TelemetryClientId
import com.nonamevpn.app.telemetry.TelemetryFileManager
import com.nonamevpn.app.telemetry.TelemetryLogEntry
import com.nonamevpn.app.telemetry.TelemetryRecorder
import com.nonamevpn.app.telemetry.TelemetryUploadClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun TestingScreen(profiles: ProfileRepository) {
    val context = LocalContext.current
    val recorder = remember { TelemetryRecorder.get(context) }
    val fileManager = remember { TelemetryFileManager(context) }
    val uploadClient = remember { TelemetryUploadClient() }
    val scope = rememberCoroutineScope()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val isRecording by recorder.isRecording.collectAsStateWithLifecycle()

    val logs = remember { mutableStateListOf<TelemetryLogEntry>() }
    var message by remember { mutableStateOf<String?>(null) }
    var uploadingFile by remember { mutableStateOf<String?>(null) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* refresh on grant */ }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun refreshLogs() {
        logs.clear()
        logs.addAll(fileManager.listLogs())
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) refreshLogs()
    }

    LaunchedEffect(Unit) { refreshLogs() }

    val serverIp = remember(profile) {
        profile?.let { p ->
            NetworkEndpoint.hostOf(p.direct.endpoint) ?: NetworkEndpoint.hostOf(p.bypass.peer)
        } ?: "unknown"
    }

    val uploadUrl = remember(serverIp) {
        if (BuildConfig.TELEMETRY_UPLOAD_URL.isNotBlank()) {
            BuildConfig.TELEMETRY_UPLOAD_URL
        } else if (serverIp != "unknown") {
            "http://$serverIp:9200/api/upload-log"
        } else {
            ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Тестирование", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Полная телеметрия для отладки. Во время записи по периметру экрана — пульсирующая красная рамка.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Сохранённые логи", style = MaterialTheme.typography.titleMedium)
                if (logs.isEmpty()) {
                    Text(
                        "Записей пока нет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(logs, key = { it.file.absolutePath }) { entry ->
                            LogRow(
                                entry = entry,
                                uploading = uploadingFile == entry.file.name,
                                progress = if (uploadingFile == entry.file.name) uploadProgress else 0f,
                                onDelete = {
                                    scope.launch {
                                        fileManager.delete(entry.file)
                                        refreshLogs()
                                        message = "Удалено: ${entry.displayName}"
                                    }
                                },
                                onUpload = {
                                    scope.launch {
                                        uploadingFile = entry.file.name
                                        uploadProgress = 0f
                                        message = "Отправка ${entry.displayName}…"
                                        val clientId = TelemetryClientId.getAsync(context)
                                        val result = uploadClient.upload(
                                            file = entry.file,
                                            clientId = clientId,
                                            uploadUrl = uploadUrl,
                                            onProgress = { uploadProgress = it },
                                        )
                                        uploadingFile = null
                                        message = result.fold(
                                            onSuccess = { "Отправлено: ${entry.displayName}" },
                                            onFailure = { it.message ?: "Ошибка отправки" },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (isRecording) {
                    recorder.stop()
                    refreshLogs()
                    message = "Запись остановлена"
                } else {
                    recorder.start(serverIp)
                    message = "Запись начата"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (isRecording) "Остановить запись" else "Начать запись")
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Загрузка: ${uploadUrl.ifBlank { "не задана (импортируйте профиль или TELEMETRY_UPLOAD_URL)" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun LogRow(
    entry: TelemetryLogEntry,
    uploading: Boolean,
    progress: Float,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${dateFmt.format(Date(entry.createdAtMs))} · ${formatDuration(entry.durationMs)} · ${formatSize(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUpload, enabled = !uploading) {
                    Icon(Icons.Outlined.Send, contentDescription = "Отправить")
                }
                IconButton(onClick = onDelete, enabled = !uploading) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Удалить")
                }
            }
            if (uploading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val min = sec / 60
    val h = min / 60
    return when {
        h > 0 -> String.format(Locale.getDefault(), "%dч %02dм", h, min % 60)
        min > 0 -> String.format(Locale.getDefault(), "%dм %02dс", min, sec % 60)
        else -> "${sec}с"
    }
}
