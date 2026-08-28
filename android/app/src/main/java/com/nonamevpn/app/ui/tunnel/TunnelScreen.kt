package com.nonamevpn.app.ui.tunnel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.nonamevpn.app.R
import com.nonamevpn.app.bypass.VkCallHashGenerator
import com.nonamevpn.app.bypass.VkLoginActivity
import com.nonamevpn.app.bypass.VkSession
import com.nonamevpn.app.bypass.VkUrl
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.core.VpnLiveStats
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.DEFAULT_PROFILE_FOLDER
import com.nonamevpn.app.profile.ProfileCatalog
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedTopInset
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import com.nonamevpn.app.ui.components.StickyPrimaryButton
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
    val catalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val scope = rememberCoroutineScope()
    var pickerFolder by remember { mutableStateOf(DEFAULT_PROFILE_FOLDER) }
    var showImport by remember { mutableStateOf(false) }
    var showHash by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var callBusy by remember { mutableStateOf(false) }
    var callMessage by remember { mutableStateOf<String?>(null) }
    var vkLoggedIn by remember { mutableStateOf(VkSession.hasSessionCookie()) }
    var publicIp by remember { mutableStateOf(EgressIpProbe.current()) }
    var ipError by remember { mutableStateOf(EgressIpProbe.lastError) }
    var trafficRates by remember { mutableStateOf("—") }
    var trafficTotals by remember { mutableStateOf("—") }

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
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(profile?.deviceId) {
        if (profile == null) return@LaunchedEffect
        conn.setHideIp(hideIp)
    }

    val connecting = ui.state == ConnState.Connecting
    val pausedTrusted = ui.state == ConnState.PausedTrustedWifi
    val connected = ui.state == ConnState.Connected
    val sessionUp = connected || pausedTrusted
    val probing = ui.state == ConnState.Probing
    val disconnecting = ui.state == ConnState.Disconnecting
    val busy = probing || connecting || disconnecting
    val pathBusy = connecting || disconnecting

    val pickerFolders = catalog.folders.ifEmpty { listOf(DEFAULT_PROFILE_FOLDER) }
    LaunchedEffect(pickerFolders) {
        if (pickerFolder !in pickerFolders) pickerFolder = pickerFolders.first()
    }
    val pickerItems = catalog.inFolder(pickerFolder)

    fun activateStored(item: StoredProfile) {
        if (item.id == catalog.activeId) return
        scope.launch {
            profiles.setActive(item.id)
            AppLog.i("Tunnel", "profile=${item.profile.name}")
            if (sessionUp) {
                Toast.makeText(
                    context,
                    "Профиль выбран — переподключите туннель",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(sessionUp, hideIp) {
        while (sessionUp) {
            publicIp = EgressIpProbe.current()
            ipError = EgressIpProbe.lastError
            VpnLiveStats.sample()
            trafficRates = VpnLiveStats.formatCompactRateLine(VpnLiveStats.downBps, VpnLiveStats.upBps)
            trafficTotals = VpnLiveStats.formatBytesLine(VpnLiveStats.totalRx, VpnLiveStats.totalTx)
            delay(1_000)
        }
        publicIp = EgressIpProbe.current()
        ipError = EgressIpProbe.lastError
        trafficRates = "—"
        trafficTotals = "—"
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.scrollContentPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EdgeFeedTopInset()

            AppPageHeader(
                title = "ARDTT",
                subtitle = profile?.name?.takeIf { it.isNotBlank() } ?: "Туннель",
            )

            val ipDisplay = when {
                !publicIp.isNullOrBlank() -> publicIp!!
                !ipError.isNullOrBlank() && sessionUp -> "не удалось · нажмите"
                sessionUp -> "…"
                else -> "—"
            }
            val showCloudflareIp = hideIp || EgressIpProbe.isLikelyCloudflare(publicIp)

            TunnelInfoPanel(
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
                ipText = ipDisplay,
                showCloudflareIcon = showCloudflareIp && !publicIp.isNullOrBlank(),
                ipFailed = sessionUp && publicIp.isNullOrBlank() && !ipError.isNullOrBlank(),
                onIpClick = if (sessionUp) {
                    { conn.requestEgressIpRefresh() }
                } else {
                    null
                },
                trafficRates = if (sessionUp) trafficRates else "—",
                trafficTotals = if (sessionUp) trafficTotals else "—",
                errorText = ui.lastError?.takeIf { ui.state == ConnState.Error && it.isNotBlank() },
            )

            if (!admin && profile != null) {
                val active = profile!!.subscriptionActive
                val expiresText = when {
                    profile!!.expiresAt <= 0L -> "без срока"
                    else -> {
                        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru"))
                        fmt.format(java.util.Date(profile!!.expiresAt * 1000L))
                    }
                }
                AppSectionCard(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        2.dp,
                        if (active) NvpnColors.connected else MaterialTheme.colorScheme.error,
                    ),
                    shadowElevation = 0.dp,
                ) {
                    Text(
                        if (active) "Подписка активна" else "Подписка неактивна",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) NvpnColors.connected else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Действует до $expiresText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AppSectionCard(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Профиль",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (pickerFolders.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pickerFolders.forEach { folder ->
                            FilterChip(
                                selected = pickerFolder == folder,
                                onClick = { pickerFolder = folder },
                                label = { Text(folder) },
                            )
                        }
                    }
                }
                if (catalog.items.isEmpty()) {
                    Text(
                        "Импортируйте JSON с сервера или создайте клиента на вкладке VPS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pickerItems.forEach { item ->
                            ChoiceChipButton(
                                label = item.profile.name.ifBlank { item.id },
                                selected = item.id == catalog.activeId,
                                enabled = true,
                                onClick = { activateStored(item) },
                            )
                        }
                    }
                    if (pickerItems.isEmpty()) {
                        Text(
                            "В этой папке пока пусто",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showImport = true },
                    enabled = !importBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(if (importBusy) "Читаем…" else "Импорт JSON…")
                }
            }

            // ═══ Быстрые настройки ═══
            AppSectionCard(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Быстрые настройки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
                        selectedContainer = NvpnColors.pathDirect,
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
                        selectedContainer = NvpnColors.pathBypass,
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
                        selected = vkLoggedIn,
                        enabled = profile != null && !callBusy,
                        onClick = { onVkAction() },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Ручное",
                        selected = ui.hasCallHash && !vkLoggedIn,
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

        val cancelMode = connecting || probing
        StickyPrimaryButton(
            text = when {
                cancelMode -> "Отменить"
                sessionUp && pausedTrusted -> "Остановить (пауза Wi‑Fi)"
                sessionUp -> "Остановить"
                else -> "Подключить"
            },
            onClick = {
                when {
                    cancelMode || sessionUp -> conn.disconnect()
                    else -> onRequestConnect()
                }
            },
            enabled = cancelMode || (!busy && (sessionUp || ui.connectEnabled)),
            containerColor = when {
                cancelMode || sessionUp -> MaterialTheme.colorScheme.error
                else -> buttonColor
            },
            icon = when {
                cancelMode -> Icons.Default.Stop
                sessionUp -> Icons.Default.Stop
                else -> Icons.Default.PowerSettingsNew
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
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
    selectedContainer: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            colors = if (selectedContainer != null) {
                ButtonDefaults.buttonColors(
                    containerColor = selectedContainer,
                    contentColor = Color.White,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
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
            border = BorderStroke(
                1.dp,
                colors.outline.copy(alpha = 0.45f),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun TunnelInfoPanel(
    statusText: String,
    statusColor: Color,
    pathModeLabel: String,
    activePathLabel: String?,
    ipText: String,
    showCloudflareIcon: Boolean,
    ipFailed: Boolean = false,
    onIpClick: (() -> Unit)? = null,
    trafficRates: String,
    trafficTotals: String,
    errorText: String?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
    ) {
        Text(
            statusText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = statusColor,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Режим", style = MaterialTheme.typography.labelMedium, color = muted)
                Text(pathModeLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            activePathLabel?.let { path ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Путь", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(
                        path,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when (path) {
                            "Прямое" -> NvpnColors.pathDirect
                            "Обход" -> NvpnColors.pathBypass
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        HorizontalDivider(color = dividerColor)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Скорость", style = MaterialTheme.typography.labelMedium, color = muted)
            Text(
                trafficRates,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
            )
            Text(
                trafficTotals,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = muted,
            )
        }

        HorizontalDivider(color = dividerColor)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onIpClick != null) Modifier.clickable(onClick = onIpClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showCloudflareIcon) {
                Icon(
                    painter = painterResource(R.drawable.ic_cloudflare),
                    contentDescription = "Cloudflare",
                    modifier = Modifier.size(18.dp),
                    tint = Color.Unspecified,
                )
            }
            Text(
                ipText,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = if (ipFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        errorText?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ImportDialog(
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onPaste: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    NvpnDialog(
        title = "Импорт профиля",
        onDismissRequest = { if (!busy) onDismiss() },
        confirmAction = NvpnDialogAction(
            text = "Импорт",
            onClick = { onPaste(text) },
            enabled = text.isNotBlank() && !busy,
        ),
        dismissAction = NvpnDialogAction("Отмена", onDismiss, enabled = !busy),
        dismissOnBackPress = !busy,
        dismissOnClickOutside = !busy,
    ) {
        Text(
            "Выберите JSON с телефона или вставьте текст.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(if (busy) "Читаем…" else "Выбрать файл…", fontWeight = FontWeight.SemiBold)
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
}

@Composable
private fun HashDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    NvpnDialog(
        title = "Hash звонка",
        onDismissRequest = onDismiss,
        confirmAction = NvpnDialogAction(
            text = "Сохранить",
            onClick = { onSave(text) },
            enabled = text.isNotBlank(),
        ),
        dismissAction = NvpnDialogAction("Отмена", onDismiss),
        secondaryAction = NvpnDialogAction(
            text = "Очистить",
            onClick = onClear,
            destructive = true,
        ),
    ) {
        Text(
            "Ссылка vk.com/call/join/… или сам hash.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
        )
    }
}
