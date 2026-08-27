package com.nonamevpn.app.ui.tunnel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
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
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.bypass.VkCallHashGenerator
import com.nonamevpn.app.bypass.VkLoginActivity
import com.nonamevpn.app.bypass.VkSession
import com.nonamevpn.app.bypass.VkUrl
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.theme.NvpnColors
import kotlinx.coroutines.delay
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
    var publicIp by remember { mutableStateOf(EgressIpProbe.current()) }

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
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val economy by settings.economyWorkersEnabled.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(profile?.deviceId) {
        if (profile == null) return@LaunchedEffect
        conn.setHideIp(hideIp)
    }

    val connected = ui.state == ConnState.Connected
    val pausedTrusted = ui.state == ConnState.PausedTrustedWifi
    val connecting = ui.state == ConnState.Connecting
    val sessionUp = connected || pausedTrusted
    val busy = ui.state == ConnState.Probing || connecting || ui.state == ConnState.Disconnecting
    val pathBusy = connecting || ui.state == ConnState.Disconnecting

    LaunchedEffect(sessionUp) {
        while (sessionUp) {
            publicIp = EgressIpProbe.current()
            delay(1_500)
        }
        publicIp = EgressIpProbe.current()
    }

    val buttonColor by animateColorAsState(
        targetValue = when {
            sessionUp -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "btn_color",
    )

    fun onVkAction() {
        scope.launch {
            if (!vkLoggedIn) {
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
                return@launch
            }
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
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "ARDTT",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        // ═══ Быстрые настройки ═══
        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Text(
                "Быстрые настройки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            QuickSettingRow(
                title = "Путь",
                subtitle = when (pathMode) {
                    "direct" -> "Только AmneziaWG (AWG)"
                    "bypass" -> "Только обход RAW через звонок"
                    else -> "Авто: AWG, резерв обход"
                },
            ) {
                ChoiceChipButton(
                    label = "Авто",
                    selected = pathMode == "auto",
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setPathMode("auto")
                            conn.setPathMode(ConnPathMode.Auto)
                            AppLog.i("PathMode", "auto")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Прямое",
                    selected = pathMode == "direct",
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setPathMode("direct")
                            conn.setPathMode(ConnPathMode.Direct)
                            AppLog.i("PathMode", "direct")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Обход",
                    selected = pathMode == "bypass",
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setPathMode("bypass")
                            conn.setPathMode(ConnPathMode.Bypass)
                            AppLog.i("PathMode", "bypass")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            QuickSettingRow(
                title = "Мощность",
                subtitle = if (economy) {
                    "Оптимально — 1 worker"
                } else {
                    "Максимум — 3 workers"
                },
            ) {
                ChoiceChipButton(
                    label = "Оптимально",
                    selected = economy,
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setEconomyWorkers(true)
                            conn.setWorkers(1)
                            AppLog.i("Power", "optimal (1 worker)")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Максимум",
                    selected = !economy,
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setEconomyWorkers(false)
                            conn.setWorkers(3)
                            AppLog.i("Power", "max (3 workers)")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            QuickSettingRow(
                title = "Хэш звонка",
                subtitle = when {
                    callMessage != null -> callMessage
                    ui.hasCallHash -> "Hash сохранён на этом телефоне"
                    vkLoggedIn -> "VK: вход выполнен — нажмите ещё раз, чтобы создать звонок"
                    else -> "Нужен для обхода (Path B)"
                },
            ) {
                ChoiceChipButton(
                    label = "Вход в ВК",
                    selected = vkLoggedIn || ui.hasCallHash,
                    enabled = profile != null && !callBusy,
                    onClick = { onVkAction() },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Ручное",
                    selected = false,
                    enabled = profile != null && !sessionUp && !callBusy,
                    onClick = { showHash = true },
                    modifier = Modifier.weight(1f),
                )
            }

            QuickSettingRow(
                title = "Скрыть IP",
                subtitle = if (hideIp) {
                    "Выход через Cloudflare WARP"
                } else {
                    "Выход напрямую (IP VPS)"
                },
            ) {
                ChoiceChipButton(
                    label = "На прямую",
                    selected = !hideIp,
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setHideIp(false)
                            conn.setHideIp(false)
                            AppLog.i("HideIP", "disabled (direct)")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "WARP",
                    selected = hideIp,
                    enabled = !pathBusy,
                    onClick = {
                        scope.launch {
                            settings.setHideIp(true)
                            conn.setHideIp(true)
                            AppLog.i("HideIP", "enabled (WARP)")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (profile == null) {
                Text(
                    "Профиль не загружен — импортируйте во вкладке «Профили».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showImport = true },
                    enabled = !importBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Импорт профиля…")
                }
            }
        }

        // ═══ Подключить — на всю ширину ═══
        Button(
            onClick = {
                if (sessionUp) conn.disconnect() else onRequestConnect()
            },
            enabled = !busy && (sessionUp || ui.connectEnabled),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (sessionUp) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    sessionUp && pausedTrusted -> "Остановить (пауза Wi‑Fi)"
                    sessionUp -> "Остановить"
                    connecting -> "Подключение…"
                    ui.state == ConnState.Probing -> "Проверка…"
                    else -> "Подключить"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        // ═══ Техстатус / IP / версия ═══
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                buildFooterLine(
                    status = ui.statusText,
                    path = ui.activePath,
                    pathMode = pathMode,
                    ip = publicIp,
                    profileName = profile?.name,
                    version = BuildConfig.VERSION_NAME,
                    economy = economy,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    connected || pausedTrusted -> NvpnColors.connected
                    ui.state == ConnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            profile?.let { p ->
                Text(
                    "Direct ${p.direct.endpoint} · Bypass ${p.bypass.peer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
                p.provisionBaseUrl?.let { base ->
                    Text(
                        "Provision $base · host ${p.hostId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
            ui.probe?.let { probe ->
                Text(
                    probeDetailLine(probe.networkClass, probe.yandexOk, probe.bigtechOk, probe.vpsUdpOk, probe.provisionOk, probe.elapsedMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            ui.softInfo?.takeIf { it.isNotBlank() }?.let { info ->
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            ui.lastError?.takeIf { ui.state == ConnState.Error }?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (vkLoggedIn) {
                TextButton(
                    onClick = {
                        VkSession.clear()
                        vkLoggedIn = false
                        callMessage = "Сессия VK сброшена"
                    },
                    enabled = !callBusy,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        "Выйти из VK",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    callMessage = "Hash сохранён вручную"
                }
            },
            onClear = {
                conn.clearCallHash()
                showHash = false
                callMessage = "Hash очищен"
            },
        )
    }
}

@Composable
private fun QuickSettingRow(
    title: String,
    subtitle: String?,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun ChoiceChipButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.45f)),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun buildFooterLine(
    status: String,
    path: VpnPath?,
    pathMode: String,
    ip: String?,
    profileName: String?,
    version: String,
    economy: Boolean,
): String {
    val bits = buildList {
        add(status.ifBlank { "—" })
        when (path) {
            VpnPath.Direct -> add("актив: прямое")
            VpnPath.Bypass -> add("актив: обход")
            null -> Unit
        }
        add(
            when (pathMode) {
                "direct" -> "режим: прямое"
                "bypass" -> "режим: обход"
                else -> "режим: авто"
            },
        )
        add(if (economy) "мощность: 1" else "мощность: 3")
        add("IP ${ip?.takeIf { it.isNotBlank() } ?: "…"}")
        if (!profileName.isNullOrBlank()) add(profileName)
        add("v$version")
    }
    return bits.joinToString(" · ")
}

private fun probeDetailLine(
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
                    "Выберите JSON с телефона или вставьте текст.",
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
                    Spacer(modifier = Modifier.width(8.dp))
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
