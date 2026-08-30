package com.nonamevpn.app.ui.admin

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import com.nonamevpn.app.ui.components.PullRefreshHost
import com.nonamevpn.app.ui.components.StickyPrimaryButton
import com.nonamevpn.app.ui.components.rememberPullRefresh
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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
    var dismissingNames by remember { mutableStateOf(setOf<String>()) }
    var uploadingFile by remember { mutableStateOf<String?>(null) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var uploadTarget by remember { mutableStateOf<TelemetryLogEntry?>(null) }
    var uploadComment by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* refresh on grant */ }

    fun notifyError(text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

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
        val animating = logs.filter { it.file.name in dismissingNames }
        val fromDisk = fileManager.listLogs()
            .filter { disk -> animating.none { it.file.name == disk.file.name } }
        logs.clear()
        logs.addAll(fromDisk)
        logs.addAll(animating)
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

    fun toggleRecording() {
        if (isRecording) {
            recorder.stop()
            refreshLogs()
        } else {
            recorder.start(serverIp)
        }
    }

    fun uploadWithComment(entry: TelemetryLogEntry, comment: String) {
        scope.launch {
            uploadingFile = entry.file.name
            uploadProgress = 0f
            val embedded = runCatching {
                TelemetryFileManager.embedUserComment(entry.file, comment)
            }
            if (embedded.isFailure) {
                uploadingFile = null
                notifyError(embedded.exceptionOrNull()?.message ?: "Не удалось добавить комментарий")
                return@launch
            }
            refreshLogs()
            val clientId = TelemetryClientId.getAsync(context)
            val result = uploadClient.upload(
                context = context,
                file = entry.file,
                clientId = clientId,
                uploadUrl = uploadUrl,
                onProgress = { uploadProgress = it },
            )
            uploadingFile = null
            result.fold(
                onSuccess = {
                    dismissingNames = dismissingNames + entry.file.name
                    delay(LOG_DISMISS_MS + 40L)
                    fileManager.delete(entry.file)
                    logs.removeAll { it.file.name == entry.file.name }
                    dismissingNames = dismissingNames - entry.file.name
                },
                onFailure = {
                    notifyError(it.message ?: "Ошибка отправки")
                },
            )
        }
    }

    val pull = rememberPullRefresh { refreshLogs() }

    Box(modifier = Modifier.fillMaxSize()) {
        PullRefreshHost(
            refreshing = pull.refreshing,
            onRefresh = pull.onRefresh,
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.scrollContentPadding(extra = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppTabPageHeader(
                title = "Режим тестирования",
                subtitle = "${BuildConfig.VERSION_NAME} · полная телеметрия и отправка на сервер",
            )

            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Статус записи",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(
                    text = if (isRecording) "Идёт запись" else "Запись остановлена",
                    accent = isRecording,
                )
                Text(
                    "Во время записи весь интерфейс подсвечивается пульсирующей тёмно-красной рамкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Сервер: ${uploadUrl.ifBlank { "не задан" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppSectionCard(
                modifier = Modifier.weight(1f),
                fillHeight = true,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Сохранённые логи",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (logs.isEmpty()) {
                        "Записей пока нет"
                    } else {
                        "${logs.size} файлов · ${formatSize(logs.sumOf { it.sizeBytes })}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (logs.isEmpty()) {
                    EmptyLogsBlock()
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(logs, key = { it.file.name }) { entry ->
                            AnimatedVisibility(
                                visible = entry.file.name !in dismissingNames,
                                modifier = Modifier.fillMaxWidth(),
                                enter = EnterTransition.None,
                                exit = fadeOut(
                                    animationSpec = tween(LOG_DISMISS_MS.toInt(), easing = FastOutSlowInEasing),
                                ) + slideOutVertically(
                                    animationSpec = tween(LOG_DISMISS_MS.toInt(), easing = FastOutSlowInEasing),
                                ) { -it } + shrinkVertically(
                                    animationSpec = tween(LOG_DISMISS_MS.toInt(), easing = FastOutSlowInEasing),
                                ),
                            ) {
                                LogRow(
                                    entry = entry,
                                    uploading = uploadingFile == entry.file.name,
                                    progress = if (uploadingFile == entry.file.name) uploadProgress else 0f,
                                    onDelete = {
                                        scope.launch {
                                            fileManager.delete(entry.file)
                                            refreshLogs()
                                        }
                                    },
                                    onUpload = {
                                        uploadTarget = entry
                                        uploadComment = ""
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
        ) {
            StickyPrimaryButton(
                text = if (isRecording) "Остановить запись" else "Начать запись",
                onClick = { toggleRecording() },
                containerColor = if (isRecording) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (isRecording) {
                    MaterialTheme.colorScheme.onError
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            )
        }
    }

    uploadTarget?.let { entry ->
        NvpnDialog(
            title = "Комментарий к логу",
            onDismissRequest = { uploadTarget = null },
            confirmAction = NvpnDialogAction(
                text = "Встроить и отправить",
                onClick = {
                    val comment = uploadComment
                    uploadTarget = null
                    uploadWithComment(entry, comment)
                },
                enabled = uploadComment.isNotBlank(),
            ),
            dismissAction = NvpnDialogAction("Отмена", { uploadTarget = null }),
        ) {
            Text(
                "Опишите, что произошло и что ожидалось. Комментарий станет частью JSONL-файла и будет учтён при разборе.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uploadComment,
                onValueChange = { uploadComment = it.take(2_048) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                label = { Text("Комментарий пользователя") },
                placeholder = { Text("Например: после смены Wi‑Fi туннель не восстановился…") },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun EmptyLogsBlock() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Запись запускается кнопкой внизу экрана. После остановки файл появится здесь.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusPill(text: String, accent: Boolean) {
    Surface(
        color = if (accent) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (accent) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${dateFmt.format(Date(entry.createdAtMs))} · ${formatDuration(entry.durationMs)} · ${formatSize(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUpload, enabled = !uploading) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Отправить",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, enabled = !uploading) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error,
                    )
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

private const val LOG_DISMISS_MS = 480L
