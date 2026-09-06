package com.ardtt.app.ui.admin

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.BuildConfig
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.telemetry.TelemetryClientId
import com.ardtt.app.telemetry.TelemetryFileManager
import com.ardtt.app.telemetry.TelemetryLogEntry
import com.ardtt.app.telemetry.TelemetryRecorder
import com.ardtt.app.telemetry.TelemetryUploadClient
import com.ardtt.app.telemetry.TestingTicket
import com.ardtt.app.telemetry.TestingTicketStore
import com.ardtt.app.telemetry.formatTestingTicketTime
import com.ardtt.app.telemetry.testingCommentPreview
import com.ardtt.app.telemetry.testingTicketReadLabel
import com.ardtt.app.telemetry.testingTicketTitle
import com.ardtt.app.telemetry.testingUploadNeedsCommentPrompt
import com.ardtt.app.ui.components.control.ArdttChoice
import com.ardtt.app.ui.components.control.ArdttChoiceChipRow
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.feedback.ArdttLinearProgress
import com.ardtt.app.ui.components.feedback.ArdttStatusChip
import com.ardtt.app.ui.components.feedback.ArdttStatusPill
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttFeedHeader
import com.ardtt.app.ui.components.layout.ArdttPullRefresh
import com.ardtt.app.ui.components.layout.ArdttStickyBottomBar
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.ArdttSectionTitle
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TestingPane { Storage, History }

@Composable
fun TestingScreen(profiles: ProfileRepository) {
    val context = LocalContext.current
    val recorder = remember { TelemetryRecorder.get(context) }
    val fileManager = remember { TelemetryFileManager(context) }
    val ticketStore = remember { TestingTicketStore(context) }
    val uploadClient = remember { TelemetryUploadClient() }
    val scope = rememberCoroutineScope()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val isRecording by recorder.isRecording.collectAsStateWithLifecycle()

    val logs = remember { mutableStateListOf<TelemetryLogEntry>() }
    var pane by remember { mutableStateOf(TestingPane.Storage) }
    var uploadingFile by remember { mutableStateOf<String?>(null) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var comments by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editingLog by remember { mutableStateOf<String?>(null) }
    var editingSubmit by remember { mutableStateOf(false) }
    var draftsReady by remember { mutableStateOf(false) }
    var tickets by remember { mutableStateOf<List<TestingTicket>>(emptyList()) }
    val latestComments = rememberUpdatedState(comments)

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

    suspend fun refreshLogs() {
        val fromDisk = withContext(Dispatchers.IO) { fileManager.listLogs() }
        logs.clear()
        logs.addAll(fromDisk)
    }

    suspend fun refreshTickets(loadDrafts: Boolean = false) {
        val state = withContext(Dispatchers.IO) { ticketStore.load() }
        if (loadDrafts) comments = state.drafts
        tickets = state.tickets
    }

    val serverIp = remember(profile) {
        profile?.let { p ->
            NetworkEndpoint.hostOf(p.direct.endpoint) ?: NetworkEndpoint.hostOf(p.bypass.peer)
        } ?: "unknown"
    }

    val uploadUrl = remember(serverIp) {
        if (BuildConfig.TELEMETRY_UPLOAD_URL.isNotBlank()) {
            BuildConfig.TELEMETRY_UPLOAD_URL
        } else if (serverIp != "unknown") {
            "https://$serverIp/api/upload-log"
        } else {
            ""
        }
    }

    suspend fun refreshInbox(notify: Boolean = false) {
        refreshTickets()
        if (uploadUrl.isBlank()) return
        val clientId = TelemetryClientId.getAsync(context)
        val result = uploadClient.fetchInbox(
            context = context,
            clientId = clientId,
            uploadUrl = uploadUrl,
        )
        result.fold(
            onSuccess = { items ->
                withContext(Dispatchers.IO) { ticketStore.applyServerStatuses(items) }
                refreshTickets()
            },
            onFailure = {
                if (notify) notifyError(it.message ?: "Не удалось обновить историю")
            },
        )
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) refreshLogs()
    }

    LaunchedEffect(Unit) {
        refreshLogs()
        refreshTickets(loadDrafts = true)
        draftsReady = true
    }

    LaunchedEffect(pane, uploadUrl) {
        if (pane != TestingPane.History) return@LaunchedEffect
        while (true) {
            refreshInbox()
            delay(INBOX_POLL_MS)
        }
    }

    LaunchedEffect(comments, draftsReady) {
        if (!draftsReady) return@LaunchedEffect
        delay(DRAFT_PERSIST_MS)
        withContext(Dispatchers.IO) { ticketStore.saveDrafts(comments) }
    }

    DisposableEffect(ticketStore) {
        onDispose {
            runCatching { ticketStore.saveDrafts(latestComments.value) }
        }
    }

    fun toggleRecording() {
        if (isRecording) {
            recorder.stop()
            scope.launch { refreshLogs() }
        } else {
            recorder.start(serverIp)
        }
    }

    fun persistLogComment(logName: String, value: String) {
        comments = comments + (logName to value.take(TestingTicketStore.MAX_COMMENT))
    }

    fun dropLogComment(logName: String) {
        if (logName in comments) comments = comments - logName
        if (editingLog == logName) {
            editingLog = null
            editingSubmit = false
        }
    }

    fun uploadWithComment(entry: TelemetryLogEntry, comment: String) {
        if (uploadingFile != null) return
        val cleaned = comment.trim()
        if (testingUploadNeedsCommentPrompt(cleaned)) {
            editingLog = entry.file.name
            editingSubmit = true
            return
        }
        uploadingFile = entry.file.name
        uploadProgress = 0f
        scope.launch {
            val embedded = runCatching {
                withContext(Dispatchers.IO) {
                    TelemetryFileManager.embedUserComment(entry.file, cleaned)
                }
            }
            if (embedded.isFailure) {
                uploadingFile = null
                notifyError(embedded.exceptionOrNull()?.message ?: "Не удалось добавить комментарий")
                return@launch
            }
            val clientId = TelemetryClientId.getAsync(context)
            val result = uploadClient.upload(
                context = context,
                file = entry.file,
                clientId = clientId,
                uploadUrl = uploadUrl,
                onProgress = { uploadProgress = it },
            )
            result.fold(
                onSuccess = { uploaded ->
                    val ticket = runCatching {
                        withContext(Dispatchers.IO) {
                            ticketStore.saveDrafts(comments - entry.file.name)
                            val saved = ticketStore.rememberUpload(
                                comment = cleaned,
                                logName = entry.file.name,
                                serverNumber = uploaded.ticket,
                                read = uploaded.read,
                            )
                            fileManager.delete(entry.file)
                            saved
                        }
                    }.getOrElse {
                        uploadingFile = null
                        notifyError(it.message ?: "Не удалось сохранить обращение")
                        return@fold
                    }
                    dropLogComment(entry.file.name)
                    logs.removeAll { it.file.name == entry.file.name }
                    refreshTickets()
                    pane = TestingPane.History
                    Toast.makeText(
                        context.applicationContext,
                        "${testingTicketTitle(ticket.number)} отправлено",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onFailure = {
                    notifyError(it.message ?: "Ошибка отправки")
                },
            )
            uploadingFile = null
        }
    }

    fun beginSubmit(entry: TelemetryLogEntry) {
        val comment = comments[entry.file.name].orEmpty()
        if (testingUploadNeedsCommentPrompt(comment)) {
            editingLog = entry.file.name
            editingSubmit = true
            return
        }
        if (editingLog == entry.file.name) {
            editingLog = null
            editingSubmit = false
        }
        uploadWithComment(entry, comment)
    }

    val pull = rememberPullRefresh {
        if (pane == TestingPane.History) refreshInbox(notify = true) else refreshLogs()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArdttPullRefresh(
            refreshing = pull.refreshing,
            onRefresh = pull.onRefresh,
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ArdttLayout.ScreenPadding,
                end = ArdttLayout.ScreenPadding,
                bottom = ArdttBottomChrome.scrollContentPadding(extra = ArdttSpacing.Small),
            ),
            verticalArrangement = Arrangement.spacedBy(ArdttLayout.FeedSpacing),
        ) {
            item(key = "header") {
                ArdttFeedHeader(
                    title = "Режим тестирования",
                    subtitle = "${BuildConfig.VERSION_NAME} · полная телеметрия и отправка на сервер",
                )
            }

            item(key = "status") {
                ArdttSectionCard(
                    contentPadding = PaddingValues(ArdttSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus),
                ) {
                    ArdttSectionTitle("Статус записи")
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
            }

            item(key = "pane") {
                ArdttChoiceChipRow(
                    choices = listOf(
                        ArdttChoice(TestingPane.Storage, "Хранилище"),
                        ArdttChoice(TestingPane.History, "История"),
                    ),
                    selected = pane,
                    onSelect = { pane = it },
                    chipHeight = ArdttSize.ChipCompact,
                )
            }

            if (pane == TestingPane.Storage) {
                item(key = "logs-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
                        ArdttSectionTitle("Локальное хранилище")
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
                        }
                    }
                }

                items(logs, key = { it.file.name }) { entry ->
                    LogRow(
                        entry = entry,
                        comment = comments[entry.file.name].orEmpty(),
                        uploading = uploadingFile == entry.file.name,
                        progress = if (uploadingFile == entry.file.name) uploadProgress else 0f,
                        onOpenEditor = {
                            editingLog = entry.file.name
                            editingSubmit = false
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { fileManager.delete(entry.file) }
                                dropLogComment(entry.file.name)
                                refreshLogs()
                            }
                        },
                        onUpload = { beginSubmit(entry) },
                    )
                }
            } else {
                item(key = "tickets-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
                        ArdttSectionTitle("История обращений")
                        Text(
                            if (tickets.isEmpty()) {
                                "Отправленных файлов пока нет"
                            } else {
                                val unread = tickets.count { !it.read }
                                if (unread > 0) {
                                    "${tickets.size} обращений · $unread ждут разбора"
                                } else {
                                    "${tickets.size} обращений"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (tickets.isEmpty()) {
                            EmptyHistoryBlock()
                        }
                    }
                }
                items(tickets, key = { "ticket-${it.number}-${it.logName}" }) { ticket ->
                    TicketRow(ticket)
                }
            }
        }
        }

        ArdttStickyBottomBar {
            ArdttPrimaryButton(
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

    val sheetLog = editingLog
    if (sheetLog != null) {
        val comment = comments[sheetLog].orEmpty()
        LogCommentSheet(
            comment = comment,
            submit = editingSubmit,
            onCommentChange = { persistLogComment(sheetLog, it) },
            onDismiss = {
                editingLog = null
                editingSubmit = false
            },
            onConfirm = {
                val text = comments[sheetLog].orEmpty()
                val shouldSubmit = editingSubmit
                editingLog = null
                editingSubmit = false
                if (shouldSubmit) {
                    logs.firstOrNull { it.file.name == sheetLog }?.let { uploadWithComment(it, text) }
                }
            },
        )
    }
}

@Composable
private fun LogCommentSheet(
    comment: String,
    submit: Boolean,
    onCommentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    ArdttDialog(
        title = "Комментарий к логу",
        onDismissRequest = onDismiss,
        confirmAction = ArdttDialogAction(
            text = if (submit) "Отправить" else "Готово",
            onClick = onConfirm,
            enabled = !submit || comment.trim().isNotEmpty(),
        ),
        dismissAction = ArdttDialogAction("Отмена", onDismiss),
    ) {
        Text(
            "Коротко опишите, что произошло. Комментарий уйдёт вместе с файлом.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = comment,
            onValueChange = { onCommentChange(it.take(TestingTicketStore.MAX_COMMENT)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text("Что произошло") },
            placeholder = { Text("Например: после смены Wi‑Fi туннель не восстановился…") },
            minLines = 3,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            shape = ArdttShapes.Field,
        )
    }
}

@Composable
private fun TicketRow(ticket: TestingTicket) {
    val whenText = formatTestingTicketTime(ticket.createdAtMs)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = ArdttAlpha.Muted),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = ArdttShapes.Card,
    ) {
        Column(
            modifier = Modifier.padding(ArdttSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.TinyPlus),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(testingTicketTitle(ticket.number))
                        if (whenText.isNotBlank()) append(" · ").append(whenText)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = ArdttSpacing.Small),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ArdttStatusChip(
                    text = testingTicketReadLabel(ticket.read),
                    accent = if (ticket.read) colors.tertiary else colors.primary,
                )
            }
            Text(
                ticket.comment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (ticket.logName.isNotBlank()) {
                Text(
                    ticket.logName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ticket.read && ticket.reviewNote.isNotBlank()) {
                Text(
                    ticket.reviewNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyLogsBlock() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ArdttAlpha.Disabled),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = ArdttShapes.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Запись запускается кнопкой внизу экрана. После остановки файл появится здесь — его можно отправить или удалить.",
            modifier = Modifier.padding(ArdttSpacing.MediumPlus),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EmptyHistoryBlock() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ArdttAlpha.Disabled),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = ArdttShapes.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "После отправки файл пропадает из хранилища и попадает сюда с номером, который выдаёт сервер. Когда автор разберёт лог, обращение помечается как прочитанное.",
            modifier = Modifier.padding(ArdttSpacing.MediumPlus),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusPill(text: String, accent: Boolean) {
    val colors = MaterialTheme.colorScheme
    ArdttStatusPill(
        text = text,
        container = if (accent) colors.errorContainer else colors.secondaryContainer,
        content = if (accent) colors.onErrorContainer else colors.onSecondaryContainer,
    )
}

@Composable
private fun LogRow(
    entry: TelemetryLogEntry,
    comment: String,
    uploading: Boolean,
    progress: Float,
    onOpenEditor: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = ArdttAlpha.Muted),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = ArdttShapes.Card,
    ) {
        Column(modifier = Modifier.padding(ArdttSpacing.Medium), verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${dateFmt.format(Date(entry.createdAtMs))} · ${formatDuration(entry.durationMs)} · ${formatSize(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogCommentField(
                    comment = comment,
                    enabled = !uploading,
                    onOpen = onOpenEditor,
                    modifier = Modifier.weight(1f),
                )
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
                ArdttLinearProgress(progress = progress)
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun LogCommentField(
    comment: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = testingCommentPreview(comment)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(ArdttSize.ChipCompact)
            .semantics { contentDescription = "Комментарий" }
            .clickable(enabled = enabled, onClick = onOpen),
        shape = ArdttShapes.Field,
        color = colors.surface,
        contentColor = if (preview.isBlank()) {
            colors.onSurfaceVariant
        } else {
            colors.onSurface
        },
        border = BorderStroke(ArdttSize.Border, colors.outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ArdttSpacing.Medium),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = preview.ifBlank { "Комментарий" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private const val DRAFT_PERSIST_MS = 400L
private const val INBOX_POLL_MS = 20_000L
