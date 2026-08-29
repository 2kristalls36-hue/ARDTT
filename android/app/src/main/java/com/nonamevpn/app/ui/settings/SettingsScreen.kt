package com.nonamevpn.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Build
import com.nonamevpn.app.core.needsNotificationPermission
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.hasTrustedWifiBackgroundPermission
import com.nonamevpn.app.core.hasTrustedWifiLocationPermission
import com.nonamevpn.app.core.nextTrustedWifiPermissionAsk
import com.nonamevpn.app.core.readConnectedWifiState
import com.nonamevpn.app.core.trustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiPermissionAsk
import com.nonamevpn.app.ui.HideIpCopy
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.legal.TestingModeAgreement
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedColumn
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.update.AppUpdateController
import com.nonamevpn.app.update.AppUpdateInfo
import com.nonamevpn.app.update.updateCardCopy
import com.nonamevpn.app.update.updatePrimaryActionLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettingsRepository,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val testingMode by settings.testingModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val notifVisible by settings.vpnNotificationVisibleFlow.collectAsStateWithLifecycle(initialValue = true)
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val connUi by conn.ui.collectAsStateWithLifecycle()
    val openCallHash by PendingUiAction.openCallHashSettings.collectAsStateWithLifecycle()
    val callHashBringIntoView = remember { BringIntoViewRequester() }
    val vpnLocked = connUi.state == ConnState.Connecting ||
        connUi.state == ConnState.Connected ||
        connUi.state == ConnState.PausedTrustedWifi ||
        connUi.state == ConnState.Disconnecting
    val scope = rememberCoroutineScope()
    val updates = remember { AppUpdateController.get(context) }
    val updateUi by updates.ui.collectAsStateWithLifecycle()
    var adminHint by remember { mutableStateOf<String?>(null) }
    var showTestingAgreement by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.i("NotifPrep", "settings POST_NOTIFICATIONS granted=$granted")
        conn.refreshVpnNotification()
        if (!granted) {
            adminHint = "Без разрешения система не сможет отображать уведомление о состоянии подключения."
        }
    }

    LaunchedEffect(silent, dial, pathMode) {
        conn.setSilentRecreate(silent)
        conn.setWorkers(3)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
        conn.setPathMode(ConnPathMode.fromSetting(pathMode))
    }

    LaunchedEffect(openCallHash) {
        if (!openCallHash) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { callHashBringIntoView.bringIntoView() }
        PendingUiAction.consumeCallHashSettings()
    }

    LaunchedEffect(Unit) {
        updates.checkInBackground()
    }

    EdgeFeedColumn {
        val modeLabel = if (admin) "администратор" else "пользователь"
        AppTabPageHeader(
            title = "Настройки приложения",
            subtitle = "Режим: $modeLabel · ${BuildConfig.VERSION_NAME}",
        )

        if (updateUi.visible) {
            UpdateSettingsCard(
                info = updateUi.available,
                downloading = updateUi.downloading,
                progress = updateUi.progress,
                message = updateUi.message,
                downloadedFile = updateUi.downloadedFile != null,
                onDownload = { updates.download() },
                onCancel = { updates.cancel() },
                onInstall = { updates.install() },
            )
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (vpnLocked) {
                    "Недоступно во время соединения."
                } else {
                    "Авто: прямое подключение, иначе обход. Маршрут можно задать вручную."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialChip(
                    "Авто",
                    pathMode == "auto",
                    { scope.launch { settings.setPathMode("auto") } },
                    Modifier.weight(1f),
                    enabled = !vpnLocked,
                )
                DialChip(
                    "Прямое",
                    pathMode == "direct",
                    { scope.launch { settings.setPathMode("direct") } },
                    Modifier.weight(1f),
                    enabled = !vpnLocked,
                )
                DialChip(
                    "Обход",
                    pathMode == "bypass",
                    {
                        if (!connUi.hasCallHash) {
                            PendingUiAction.requestCallHashSettings()
                            scope.launch {
                                kotlinx.coroutines.delay(80)
                                runCatching { callHashBringIntoView.bringIntoView() }
                            }
                        } else {
                            scope.launch { settings.setPathMode("bypass") }
                        }
                    },
                    Modifier.weight(1f),
                    enabled = !vpnLocked,
                    dimmed = !connUi.hasCallHash,
                )
            }
            Text(
                when (pathMode) {
                    "direct" -> "Используется только прямое подключение."
                    "bypass" -> if (connUi.hasCallHash) {
                        "Используется только обход. Требуется код звонка."
                    } else {
                        "Код звонка не задан. Нажмите «Обход», чтобы открыть карточку."
                    }
                    else -> "Приоритет прямого подключения, резерв — обход."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            RowSetting(
                title = "Скрыть адрес",
                subtitle = HideIpCopy.subtitle(hideIp),
                checked = hideIp,
                enabled = !vpnLocked,
                onCheckedChange = {
                    scope.launch {
                        settings.setHideIp(it)
                        conn.setHideIp(it)
                    }
                },
            )
            RowSetting(
                title = "Уведомление",
                subtitle = if (notifVisible) {
                    "Состояние, скорость и остановка в уведомлениях."
                } else {
                    "Скрыто. Система может оставить служебную запись."
                },
                checked = notifVisible,
                enabled = true,
                onCheckedChange = {
                    scope.launch {
                        settings.setVpnNotificationVisible(it)
                        if (it && needsNotificationPermission(context) &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            conn.refreshVpnNotification()
                        }
                    }
                },
            )
        }

        TrustedWifiSettingsCard(settings = settings)

        CallHashSettingsCard(
            modifier = Modifier.bringIntoViewRequester(callHashBringIntoView),
        )

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Обход", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Источник параметров обхода. Авто — vkcalls, иначе резерв. Нужен код звонка выше.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialChip("Авто", dial == "auto", { scope.launch { settings.setDialPath("auto") } }, Modifier.weight(1f))
                DialChip("vkcalls", dial == "vkcalls", { scope.launch { settings.setDialPath("vkcalls") } }, Modifier.weight(1f))
                DialChip("Капча", dial == "legacy", { scope.launch { settings.setDialPath("legacy") } }, Modifier.weight(1f))
            }
            RowSetting(
                title = "Обновлять звонок автоматически",
                subtitle = "Новый звонок без подтверждения. Нужна сессия ВКонтакте.",
                checked = silent,
                onCheckedChange = { scope.launch { settings.setSilentRecreate(it) } },
            )
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialChip("Система", themeMode == "system", { scope.launch { settings.setThemeMode("system") } }, Modifier.weight(1f))
                DialChip("Светлая", themeMode == "light", { scope.launch { settings.setThemeMode("light") } }, Modifier.weight(1f))
                DialChip("Тёмная", themeMode == "dark", { scope.launch { settings.setThemeMode("dark") } }, Modifier.weight(1f))
            }
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Администратор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (admin) {
                    "Открыты «Сервера», «Деплой» и «Журналы». Телеметрия — после включения тестирования."
                } else {
                    "Переместите ползунок вправо до конца."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!admin) {
                AdminUnlockSlider(
                    onUnlocked = {
                        scope.launch {
                            settings.unlockAdmin()
                            adminHint = "Режим администратора включён."
                            AppLog.i("Admin", "Unlocked via slider")
                        }
                    },
                    onIncomplete = {
                        adminHint = "Доведите ползунок до конца."
                    },
                )
            } else {
                RowSetting(
                    title = "Тестирование",
                    subtitle = "Журналы — после принятия соглашения.",
                    checked = testingMode,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            scope.launch { settings.setTestingMode(false) }
                        } else {
                            showTestingAgreement = true
                        }
                    },
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settings.lockAdmin()
                            adminHint = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Завершить сессию администратора")
                }
            }
            adminHint?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showTestingAgreement) {
        TestingModeAgreementDialog(
            onAccept = {
                showTestingAgreement = false
                scope.launch {
                    settings.setTestingAgreementVersion(TestingModeAgreement.VERSION)
                    settings.setTestingMode(true)
                    AppLog.i("Testing", "agreement v${TestingModeAgreement.VERSION} accepted")
                }
            },
            onDismiss = { showTestingAgreement = false },
        )
    }
}

@Composable
private fun UpdateSettingsCard(
    info: AppUpdateInfo?,
    downloading: Boolean,
    progress: Float,
    message: String?,
    downloadedFile: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Обновление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (info != null) {
            val copy = updateCardCopy(
                installedVersionName = BuildConfig.VERSION_NAME,
                versionName = info.versionName,
                sizeBytes = info.sizeBytes,
                notes = info.notes,
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        copy.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    copy.installedLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    copy.sizeLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    copy.notes?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (downloading || downloadedFile || info?.isNewer == true) {
            UpdateFillButton(
                text = updatePrimaryActionLabel(downloading, downloadedFile),
                filling = downloading,
                progress = progress,
                onClick = {
                    when {
                        downloading -> onCancel()
                        downloadedFile -> onInstall()
                        else -> onDownload()
                    }
                },
            )
        }
    }
}

@Composable
private fun UpdateFillButton(
    text: String,
    filling: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val animatedProgress by animateFloatAsState(
        targetValue = if (filling) progress.coerceIn(0f, 1f) else 1f,
        label = "update_fill_progress",
    )
    val trackColor = lerp(colors.surface, colors.primary, 0.42f)
    val fillColor = colors.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NvpnBottomChrome.ButtonHeight)
            .clip(shape)
            .drawBehind {
                drawRect(if (filling) trackColor else fillColor)
                if (filling) {
                    drawRect(
                        color = fillColor,
                        size = Size(size.width * animatedProgress, size.height),
                    )
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.onPrimary,
            style = MaterialTheme.typography.titleMedium.copy(
                background = Color.Transparent,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun TrustedWifiSettingsCard(settings: AppSettingsRepository) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val enabled by settings.trustedWifiEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val ssids by settings.trustedWifiSsidsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    var hint by remember { mutableStateOf<String?>(null) }
    var wifi by remember {
        mutableStateOf(readConnectedWifiState(context, requireBackground = false))
    }

    fun refreshWifi() {
        wifi = readConnectedWifiState(context, requireBackground = false)
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshWifi()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled, ssids) { refreshWifi() }

    var pendingAddAfterLocation by remember { mutableStateOf(false) }
    var pendingBackgroundAfterLocation by remember { mutableStateOf(false) }

    fun tryAddCurrentSsid() {
        val fresh = readConnectedWifiState(context, requireBackground = false)
        wifi = fresh
        val ssid = fresh.ssid
        if (ssid.isBlank()) {
            hint = when (fresh.accessProblem) {
                TrustedWifiAccessProblem.LocationDisabled -> "Включите геолокацию в системе."
                TrustedWifiAccessProblem.ForegroundPermission -> "Предоставьте разрешение геолокации."
                else -> "Имя сети не определено. Подключитесь к Wi‑Fi и предоставьте доступ к геолокации."
            }
        } else {
            scope.launch {
                settings.addTrustedWifiSsid(ssid)
                hint = "Добавлено: $ssid"
            }
        }
    }

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshWifi()
        hint = if (granted) {
            "Фоновый доступ к геолокации разрешён"
        } else {
            "Без фоновой геолокации туннель может не увидеть сеть в фоне. Для добавления текущей сети достаточно обычной геолокации."
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        refreshWifi()
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            hint = "Разрешение геолокации получено. Можно добавить текущую сеть."
            if (pendingAddAfterLocation) {
                pendingAddAfterLocation = false
                tryAddCurrentSsid()
            }
            if (pendingBackgroundAfterLocation &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !hasTrustedWifiBackgroundPermission(context)
            ) {
                pendingBackgroundAfterLocation = false
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            pendingAddAfterLocation = false
            pendingBackgroundAfterLocation = false
            hint = "Для определения имени сети Wi‑Fi требуется разрешение геолокации."
        }
    }

    fun askTrustedWifiPermissions(wantBackground: Boolean, addAfter: Boolean) {
        pendingAddAfterLocation = addAfter
        pendingBackgroundAfterLocation = wantBackground
        when (
            nextTrustedWifiPermissionAsk(
                hasLocation = hasTrustedWifiLocationPermission(context),
                hasBackground = hasTrustedWifiBackgroundPermission(context),
                sdkInt = Build.VERSION.SDK_INT,
                wantBackground = wantBackground,
            )
        ) {
            TrustedWifiPermissionAsk.Location -> locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            TrustedWifiPermissionAsk.Background -> {
                pendingAddAfterLocation = false
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            TrustedWifiPermissionAsk.None -> {
                pendingAddAfterLocation = false
                pendingBackgroundAfterLocation = false
                if (addAfter) tryAddCurrentSsid()
            }
        }
    }

    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Доверенная Wi‑Fi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "В этих сетях туннель приостанавливается. При выходе подключение восстанавливается. Добавляется только текущая сеть.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowSetting(
            title = "Включить",
            subtitle = when (val p = trustedWifiAccessProblem(context, requireBackground = false)) {
                TrustedWifiAccessProblem.ForegroundPermission ->
                    "Требуется разрешение геолокации, чтобы определить имя сети."
                TrustedWifiAccessProblem.LocationDisabled -> "Включите геолокацию в системе."
                TrustedWifiAccessProblem.BackgroundPermission -> "Требуется фоновая геолокация."
                null -> when {
                    !hasTrustedWifiBackgroundPermission(context) ->
                        "Для автоматической паузы в фоне предоставьте геолокацию «Всегда»."
                    ssids.isEmpty() -> "Добавьте хотя бы одну сеть."
                    else -> "${ssids.size} сетей"
                }
            },
            checked = enabled,
            onCheckedChange = { on ->
                if (on) {
                    askTrustedWifiPermissions(wantBackground = true, addAfter = false)
                }
                scope.launch { settings.setTrustedWifiEnabled(on) }
            },
        )
        if (wifi.connected && wifi.ssidAvailable) {
            Text(
                "Сейчас: «${wifi.ssid}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (wifi.connected) {
            Text(
                when (wifi.accessProblem) {
                    TrustedWifiAccessProblem.ForegroundPermission ->
                        "Сеть Wi‑Fi подключена, но нет разрешения геолокации на определение имени."
                    TrustedWifiAccessProblem.LocationDisabled -> "Сеть Wi‑Fi подключена, но геолокация выключена."
                    else -> "Сеть Wi‑Fi подключена, имя сети недоступно. Предоставьте доступ к Wi‑Fi."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = {
                askTrustedWifiPermissions(wantBackground = false, addAfter = true)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            enabled = enabled,
        ) {
            Text(
                if (wifi.ssidAvailable) "Добавить «${wifi.ssid}»"
                else "Добавить текущую Wi‑Fi",
            )
        }
        ssids.forEach { ssid ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ssid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { scope.launch { settings.removeTrustedWifiSsid(ssid) } },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Удалить") }
            }
        }
        hint?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DialChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            labelColor = if (dimmed) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    )
}

@Composable
private fun RowSetting(
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
                if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
