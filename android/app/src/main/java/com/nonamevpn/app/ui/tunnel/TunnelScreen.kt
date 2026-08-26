package com.nonamevpn.app.ui.tunnel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.bypass.VkCallHashGenerator
import com.nonamevpn.app.bypass.VkLoginActivity
import com.nonamevpn.app.bypass.VkSession
import com.nonamevpn.app.bypass.VkUrl
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.theme.NvpnColors
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun TunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    onRequestConnect: () -> Unit,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showHash by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var callBusy by remember { mutableStateOf(false) }
    var callMessage by remember { mutableStateOf<String?>(null) }
    var vkLoggedIn by remember { mutableStateOf(VkSession.hasSessionCookie()) }

    fun applyImported() {
        showImport = false
        importError = null
        importBusy = false
    }

    val pickProfileFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            AppLog.w("Import", "File pick cancelled")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            importBusy = true
            importError = null
            runCatching {
                // Persist read access across reboots if the provider allows it.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                profiles.importUri(uri)
            }.onSuccess { p ->
                AppLog.i("Import", "Profile from file: ${p.name} hostId=${p.hostId}")
                applyImported()
            }.onFailure { e ->
                AppLog.e("Import", e.message ?: "import failed")
                importBusy = false
                importError = e.message ?: "Не удалось импортировать файл"
                showImport = true
            }
        }
    }

    fun launchFilePicker() {
        AppLog.i("Import", "Opening file picker")
        pickProfileFile.launch(
            arrayOf(
                "application/json",
                "text/plain",
                "text/*",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    LaunchedEffect(Unit) {
        // no-op placeholder kept for future cold-start hooks
    }

    LaunchedEffect(profile) {
        conn.updateProfile(profile)
        if (profile != null) {
            settings.setProfileName(profile!!.name)
        } else {
            settings.setProfileName("")
        }
        conn.startInitialProbe()
    }

    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(hideIp) {
        conn.setHideIp(hideIp)
    }

    val connected = ui.state == ConnState.Connected
    val connecting = ui.state == ConnState.Connecting
    val busy = ui.state == ConnState.Probing || connecting || ui.state == ConnState.Disconnecting

    val buttonColor by animateColorAsState(
        targetValue = when {
            connected -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "btn_color",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "nonameVPN",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // Connect — like qWDTT main card
        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profile == null) {
                Button(
                    onClick = { launchFilePicker() },
                    enabled = !importBusy && !connected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (importBusy) "Читаем файл…" else "Выбрать файл профиля…",
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = { showImport = true },
                    enabled = !importBusy && !connected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Вставить JSON вручную…")
                }
            } else {
                Text(
                    "Профиль: ${profile!!.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Direct ${profile!!.direct.endpoint} · Bypass ${profile!!.bypass.peer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (ui.pathMode) {
                        ConnPathMode.Auto -> "Режим: Авто (AWG → WDTT)"
                        ConnPathMode.Direct -> "Режим: только прямое (AWG)"
                        ConnPathMode.Bypass -> "Режим: только обход (WDTT)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                ui.statusText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    connected -> NvpnColors.connected
                    ui.state == ConnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.probe?.let { p ->
                Text(
                    detailLine(
                        p.networkClass,
                        p.yandexOk,
                        p.bigtechOk,
                        p.vpsUdpOk,
                        p.provisionOk,
                        p.elapsedMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            ui.softInfo?.let { info ->
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            ui.lastError?.takeIf { ui.state == ConnState.Error }?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { conn.startInitialProbe() },
                    enabled = !busy && !connected,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Сеть", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        if (connected) conn.disconnect() else onRequestConnect()
                    },
                    enabled = !busy && (connected || ui.connectEnabled),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (connected) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            connected -> "Остановить"
                            connecting -> "Подключение…"
                            ui.state == ConnState.Probing -> "Проверка…"
                            else -> "Подключить"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Звонок (обход)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (ui.hasCallHash) "Hash сохранён на этом телефоне" else "Hash не задан — нужен для Path B",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            callMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (!vkLoggedIn) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            callBusy = true
                            callMessage = "Открываем вход VK…"
                            AppLog.i("VK", "Login button pressed")
                            val activityCtx = context.findActivity() ?: context
                            val r = runCatching { VkLoginActivity.login(activityCtx) }
                                .getOrElse { Result.failure(it) }
                            callBusy = false
                            vkLoggedIn = VkSession.hasSessionCookie()
                            callMessage = when {
                                r.isSuccess && vkLoggedIn -> "Вход выполнен — можно создать звонок"
                                r.isSuccess -> "Сессия не подтвердилась — попробуйте ещё раз"
                                else -> r.exceptionOrNull()?.message ?: "Вход отменён"
                            }
                            AppLog.i("VK", "Login result success=${r.isSuccess} cookie=$vkLoggedIn")
                        }
                    },
                    // Allow login even while connected (session is for hash recreate).
                    enabled = profile != null && !callBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Войти в VK…", fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            callBusy = true
                            callMessage = "Создаём звонок…"
                            val r = VkCallHashGenerator.generateOne(context)
                            callBusy = false
                            r.onSuccess { hash ->
                                conn.saveCallHash(hash)
                                callMessage = "Звонок создан, hash сохранён"
                            }.onFailure { e ->
                                callMessage = e.message ?: "Не удалось создать звонок"
                                vkLoggedIn = VkSession.hasSessionCookie()
                            }
                        }
                    },
                    enabled = profile != null && !connected && !callBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        if (ui.hasCallHash) "Создать новый звонок" else "Создать звонок",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = {
                        VkSession.clear()
                        vkLoggedIn = false
                        callMessage = "Сессия VK сброшена"
                    },
                    enabled = !connected && !callBusy,
                ) {
                    Text("Выйти из VK")
                }
            }
            OutlinedButton(
                onClick = { showHash = true },
                enabled = profile != null && !connected && !callBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    if (ui.hasCallHash) "Вставить hash вручную…" else "Сохранить hash вручную…",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowSwitch(
                title = "Скрыть свой IP",
                subtitle = if (hideIp) {
                    "Включено — выход через Cloudflare WARP (не IP VPS)"
                } else {
                    "Выход в интернет через WARP на VPS вместо адреса сервера"
                },
                checked = hideIp,
                enabled = !connecting && ui.state != ConnState.Disconnecting,
                onCheckedChange = { on ->
                    scope.launch {
                        settings.setHideIp(on)
                        conn.setHideIp(on)
                        AppLog.i("HideIP", if (on) "enabled" else "disabled")
                    }
                },
            )
        }

        if (profile != null) {
            Button(
                onClick = { launchFilePicker() },
                enabled = !connected && !importBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (importBusy) "Читаем файл…" else "Импорт из файла…", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { showImport = true },
                enabled = !connected && !importBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Вставить JSON вручную…")
            }
            TextButton(
                onClick = {
                    scope.launch {
                        profiles.clear()
                        conn.clearCallHash()
                    }
                },
                enabled = !connected,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Удалить профиль")
            }
        }
    }

    if (showImport) {
        ImportDialog(
            error = importError,
            busy = importBusy,
            onDismiss = {
                showImport = false
                importError = null
            },
            onPickFile = { launchFilePicker() },
            onPaste = { text ->
                scope.launch {
                    importBusy = true
                    runCatching { profiles.importJson(text) }
                        .onSuccess {
                            AppLog.i("Import", "Profile from paste: ${it.name}")
                            applyImported()
                        }.onFailure {
                            importBusy = false
                            importError = it.message ?: "Неверный JSON профиля"
                        }
                }
            },
            onDemo = {
                scope.launch {
                    profiles.importDemo()
                    AppLog.w("Import", "Demo profile loaded (fake IP)")
                    applyImported()
                }
            },
        )
    }

    if (showHash) {
        HashDialog(
            onDismiss = { showHash = false },
            onSave = { hash ->
                val cleaned = VkUrl.strip(hash)
                if (VkUrl.isPlausibleHash(cleaned)) {
                    conn.saveCallHash(cleaned)
                    showHash = false
                }
            },
            onClear = {
                conn.clearCallHash()
                showHash = false
            },
        )
    }
}

@Composable
private fun ImportDialog(
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onPaste: (String) -> Unit,
    onDemo: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Импорт профиля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Выберите JSON с телефона (Downloads / Files) или вставьте текст.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onPickFile,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Читаем…" else "Выбрать файл…", fontWeight = FontWeight.Bold)
                }
                Text("Или вставьте JSON:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    shape = RoundedCornerShape(16.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPaste(text) },
                enabled = text.isNotBlank() && !busy,
            ) { Text("Импортировать текст") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDemo, enabled = !busy) { Text("Демо (фейковый IP)") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun HashDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hash звонка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ссылка vk.com/call/join/… или сам hash.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onClear) { Text("Очистить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
        shape = RoundedCornerShape(24.dp),
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
    provisionOk: Boolean,
    elapsedMs: Long,
): String {
    val bits = buildList {
        add(networkClass.name)
        add("yandex=${if (yandexOk) "ok" else "—"}")
        add("bigtech=${if (bigtechOk) "ok" else "—"}")
        add("udp=${if (vpsUdpOk) "ok" else "—"}")
        add("health=${if (provisionOk) "ok" else "—"}")
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
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
