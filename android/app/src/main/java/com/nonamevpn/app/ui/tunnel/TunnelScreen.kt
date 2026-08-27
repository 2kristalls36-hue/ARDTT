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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.nonamevpn.app.core.ProbeResult
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

    LaunchedEffect(sessionUp, hideIp) {
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

        // ═══ Статус сессии — структурированная панель ═══
        TunnelStatusPanel(
            statusText = ui.statusText.ifBlank { "—" },
            statusColor = when {
                connected || pausedTrusted -> NvpnColors.connected
                ui.state == ConnState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            pathModeLabel = when (pathMode) {
                "direct" -> "Прямое"
                "bypass" -> "Обход"
                else -> "Авто"
            },
            activePathLabel = when (ui.activePath) {
                VpnPath.Direct -> "Прямое"
                VpnPath.Bypass -> "Обход"
                null -> null
            },
            powerLabel = if (economy) "Оптимально (1)" else "Максимум (3)",
            publicIp = publicIp?.takeIf { it.isNotBlank() } ?: "…",
            profileName = profile?.name?.takeIf { it.isNotBlank() },
            version = BuildConfig.VERSION_NAME,
            directEndpoint = profile?.direct?.endpoint,
            bypassPeer = profile?.bypass?.peer,
            provisionLine = profile?.let { p ->
                p.provisionBaseUrl?.let { base -> "$base · host ${p.hostId}" }
            },
            probe = ui.probe,
            softInfo = ui.softInfo?.takeIf { it.isNotBlank() },
            errorText = ui.lastError?.takeIf { ui.state == ConnState.Error && it.isNotBlank() },
            showVkLogout = vkLoggedIn,
            vkLogoutEnabled = !callBusy,
            onVkLogout = {
                VkSession.clear()
                vkLoggedIn = false
                callMessage = "Сессия VK сброшена"
            },
        )
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

@Composable
private fun TunnelStatusPanel(
    statusText: String,
    statusColor: Color,
    pathModeLabel: String,
    activePathLabel: String?,
    powerLabel: String,
    publicIp: String,
    profileName: String?,
    version: String,
    directEndpoint: String?,
    bypassPeer: String?,
    provisionLine: String?,
    probe: ProbeResult?,
    softInfo: String?,
    errorText: String?,
    showVkLogout: Boolean,
    vkLogoutEnabled: Boolean,
    onVkLogout: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Статус",
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                fontWeight = FontWeight.Medium,
            )
            Text(
                statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
            )
        }

        HorizontalDivider(color = dividerColor)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusFactRow(label = "Режим", value = pathModeLabel)
            activePathLabel?.let { StatusFactRow(label = "Активный путь", value = it) }
            StatusFactRow(label = "Мощность", value = powerLabel)
            StatusFactRow(label = "IP", value = publicIp)
            profileName?.let { StatusFactRow(label = "Профиль", value = it) }
            StatusFactRow(label = "Версия", value = "v$version")
        }

        val hasEndpoints = !directEndpoint.isNullOrBlank() ||
            !bypassPeer.isNullOrBlank() ||
            !provisionLine.isNullOrBlank()
        if (hasEndpoints) {
            HorizontalDivider(color = dividerColor)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Эндпоинты",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    fontWeight = FontWeight.Medium,
                )
                directEndpoint?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Direct", value = it)
                }
                bypassPeer?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Bypass", value = it)
                }
                provisionLine?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Provision", value = it)
                }
            }
        }

        probe?.let { p ->
            HorizontalDivider(color = dividerColor)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Проверка сети",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    fontWeight = FontWeight.Medium,
                )
                StatusFactRow(label = "Сеть", value = p.networkClass.name)
                StatusFactRow(label = "Yandex", value = if (p.yandexOk) "ok" else "—")
                StatusFactRow(label = "Bigtech", value = if (p.bigtechOk) "ok" else "—")
                StatusFactRow(label = "UDP VPS", value = if (p.vpsUdpOk) "ok" else "—")
                StatusFactRow(label = "Health", value = if (p.provisionOk) "ok" else "—")
                if (p.elapsedMs > 0) {
                    StatusFactRow(label = "Время", value = "${p.elapsedMs} мс")
                }
            }
        }

        softInfo?.let { info ->
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        errorText?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (showVkLogout) {
            TextButton(
                onClick = onVkLogout,
                enabled = vkLogoutEnabled,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(
                    "Выйти из VK",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
        }
    }
}

@Composable
private fun StatusFactRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
