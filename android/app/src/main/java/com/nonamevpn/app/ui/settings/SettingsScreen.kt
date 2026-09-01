package com.nonamevpn.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.nonamevpn.app.core.BypassWorkers
import com.nonamevpn.app.core.hasTrustedWifiBackgroundPermission
import com.nonamevpn.app.core.hasTrustedWifiLocationPermission
import com.nonamevpn.app.core.nextTrustedWifiPermissionAsk
import com.nonamevpn.app.core.readConnectedWifiState
import com.nonamevpn.app.core.trustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiPermissionAsk
import com.nonamevpn.app.ui.HideIpCopy
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.ui.TestingSessionGuard
import com.nonamevpn.app.ui.connectionControlsLocked
import com.nonamevpn.app.legal.TestingModeAgreement
import com.nonamevpn.app.telemetry.TelemetryRecorder
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.rememberSmartHaptics
import com.nonamevpn.app.ui.theme.NvpnColors
import com.nonamevpn.app.update.AppUpdateController
import kotlinx.coroutines.launch

/** Full-screen settings (kept for compatibility). Prefer [SettingsSheet] from Tunnel gear. */
@Composable
fun SettingsScreen(settings: AppSettingsRepository) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsContent(settings = settings)
    }
}

/** Dialog host for settings opened from Tunnel gear. */
@Composable
fun SettingsSheet(
    settings: AppSettingsRepository,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Настройки",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                SettingsContent(settings = settings)
            }
        }
    }
}

@Composable
fun SettingsContent(settings: AppSettingsRepository) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val updates = remember { AppUpdateController.get(context) }
    val updateUi by updates.ui.collectAsStateWithLifecycle()
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val hasPin by settings.hasAdminPin.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val notifVisible by settings.vpnNotificationVisibleFlow.collectAsStateWithLifecycle(initialValue = true)
    val hideTunnelQuickSettings by settings.hideTunnelQuickSettingsFlow.collectAsStateWithLifecycle(initialValue = false)
    val unlockConnControls by settings.unlockConnControlsFlow.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val dynamicColors by settings.dynamicColorsFlow.collectAsStateWithLifecycle(initialValue = true)
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val connUi by conn.ui.collectAsStateWithLifecycle()
    val haptics = rememberSmartHaptics(uiHapticsEnabled)
    val openCallHash by PendingUiAction.openCallHashSettings.collectAsStateWithLifecycle()
    val callHashBringIntoView = remember { BringIntoViewRequester() }
    val openUpdateDownload by PendingUiAction.openUpdateDownload.collectAsStateWithLifecycle()
    val updateBringIntoView = remember { BringIntoViewRequester() }
    val openAppearanceSettings by PendingUiAction.openAppearanceSettings.collectAsStateWithLifecycle()
    val appearanceBringIntoView = remember { BringIntoViewRequester() }
    val scrollState = rememberScrollState()
    var dialCardOffsetY by remember { mutableFloatStateOf(-1f) }
    val vpnSessionActive = connUi.state == ConnState.Connecting ||
        connUi.state == ConnState.Connected ||
        connUi.state == ConnState.PausedTrustedWifi ||
        connUi.state == ConnState.Disconnecting
    val vpnLocked = connectionControlsLocked(vpnSessionActive, unlockConnControls)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var adminHint by remember { mutableStateOf<String?>(null) }
    var showTestingAgreement by remember { mutableStateOf(false) }
    var showBypassMethodDialog by remember { mutableStateOf(false) }
    var highlightBypassDialog by remember { mutableStateOf(false) }
    var highlightAppearanceCard by remember { mutableStateOf(false) }
    val refuseLeaveTestingSession: () -> Unit = {
        adminHint = TestingSessionGuard.STOP_RECORDING_FIRST
        Toast.makeText(
            context,
            TestingSessionGuard.STOP_RECORDING_FIRST,
            Toast.LENGTH_LONG,
        ).show()
    }

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
        conn.setWorkers(BypassWorkers.DEFAULT)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
        conn.setPathMode(ConnPathMode.fromSetting(pathMode))
    }
    LaunchedEffect(openUpdateDownload) {
        if (!openUpdateDownload) return@LaunchedEffect
        PendingUiAction.consumeOpenUpdateDownload()
        updates.checkInBackground()
        scope.launch {
            kotlinx.coroutines.delay(80)
            runCatching { updateBringIntoView.bringIntoView() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    vpnLocked -> "Недоступно во время соединения."
                    vpnSessionActive && unlockConnControls ->
                        "Соединение активно. Изменение маршрута применяется сразу, без отключения."
                    else -> "Автоматический режим: приоритет прямого подключения, резервный маршрут — обход. Доступно ручное переключение."
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
                    {
                        scope.launch {
                            settings.setPathMode("auto")
                            conn.setPathMode(ConnPathMode.Auto, switchLive = true)
                        }
                    },
                    Modifier.weight(1f),
                    enabled = !vpnLocked,
                )
                DialChip(
                    "Прямое",
                    pathMode == "direct",
                    {
                        scope.launch {
                            settings.setPathMode("direct")
                            conn.setPathMode(ConnPathMode.Direct, switchLive = true)
                        }
                    },
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
                            scope.launch {
                                settings.setPathMode("bypass")
                                conn.setPathMode(ConnPathMode.Bypass, switchLive = true)
                            }
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
            Text(
                "Исходящий адрес",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                HideIpCopy.subtitle(hideIp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialChip(
                    label = HideIpCopy.SERVER_CHIP,
                    selected = !hideIp,
                    onClick = {
                        if (uiHapticsEnabled) haptics.tick()
                        scope.launch {
                            settings.setHideIp(false)
                            conn.setHideIp(false)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !vpnLocked,
                )
                DialChip(
                    label = HideIpCopy.HIDDEN_CHIP,
                    selected = hideIp,
                    onClick = {
                        if (uiHapticsEnabled) haptics.tick()
                        scope.launch {
                            settings.setHideIp(true)
                            conn.setHideIp(true)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !vpnLocked,
                )
            }
            RowSetting(
                title = "Скрыть быстрые настройки",
                subtitle = if (hideTunnelQuickSettings) {
                    "Раздел «Параметры подключения» на вкладке «Туннель» скрыт."
                } else {
                    "На вкладке «Туннель» показаны маршрут, адрес и доверенная Wi‑Fi."
                },
                checked = hideTunnelQuickSettings,
                onCheckedChange = { hidden ->
                    scope.launch { settings.setHideTunnelQuickSettings(hidden) }
                },
            )
            RowSetting(
                title = "Кнопки во время соединения",
                subtitle = if (unlockConnControls) {
                    "Маршрут и исходящий адрес можно изменять без отключения туннеля."
                } else {
                    "Пока туннель активен, изменение маршрута и исходящего адреса недоступно."
                },
                checked = unlockConnControls,
                onCheckedChange = { scope.launch { settings.setUnlockConnControls(it) } },
            )
        }

        // User-facing WiFi pause controls should always be available in Settings.
        TrustedWifiSettingsCard(settings = settings)

        AppSectionCard(
            modifier = Modifier
                .bringIntoViewRequester(callHashBringIntoView)
                .onGloballyPositioned { coordinates ->
                    dialCardOffsetY = coordinates.positionInParent().y
                },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Метод обхода", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Источник параметров обхода и код звонка. Авто — vkcalls, иначе резерв.",
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
                subtitle = "Новый код звонка создаётся без подтверждения. Требуется активная сессия ВКонтакте.",
                checked = silent,
                onCheckedChange = { scope.launch { settings.setSilentRecreate(it) } },
            )
            CallHashSettingsContent(showHeader = false)
        }

        val appearanceHighlightAlpha by animateFloatAsState(
            targetValue = if (highlightAppearanceCard) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 420),
            label = "appearance_card_highlight",
        )
        AppSectionCard(
            modifier = Modifier.bringIntoViewRequester(appearanceBringIntoView),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f + 0.50f * appearanceHighlightAlpha),
            ),
        ) {
            if (!recordingActive) {
                Text("Оформление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialChip("Система", themeMode == "system", { scope.launch { settings.setThemeMode("system") } }, Modifier.weight(1f))
                    DialChip("Светлая", themeMode == "light", { scope.launch { settings.setThemeMode("light") } }, Modifier.weight(1f))
                    DialChip("Тёмная", themeMode == "dark", { scope.launch { settings.setThemeMode("dark") } }, Modifier.weight(1f))
                }
                RowSetting(
                    title = "Динамические цвета",
                    subtitle = if (dynamicColors) {
                        "Цвета кнопок и акцентов подстраиваются под текущие обои."
                    } else {
                        "Используется базовая палитра темы без адаптации к обоям."
                    },
                    checked = dynamicColors,
                    onCheckedChange = { scope.launch { settings.setDynamicColors(it) } },
                )
                if (admin) {
                    RowSetting(
                        title = "Виброотклик",
                        subtitle = if (uiHapticsEnabled) {
                            "Короткая тактильная отдача на ключевых действиях интерфейса."
                        } else {
                            "Виброотклик отключён."
                        },
                        checked = uiHapticsEnabled,
                        onCheckedChange = {
                            if (uiHapticsEnabled) haptics.tick()
                            scope.launch { settings.setUiHapticsEnabled(it) }
                        },
                    )
                }
            }
            RowSetting(
                title = "Уведомление",
                subtitle = if (notifVisible) {
                    "Состояние, скорость и остановка в уведомлениях."
                } else {
                    "Скрыто. Система может оставить служебную запись."
                },
                checked = notifVisible,
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

        UpdateSettingsCard(
            modifier = Modifier.bringIntoViewRequester(updateBringIntoView),
            info = updateUi.available,
            downloading = updateUi.downloading,
            progress = updateUi.progress,
            message = updateUi.message,
            downloadedFile = updateUi.downloadedFile != null,
            onDownload = { updates.download() },
            onCancel = { updates.cancel() },
            onInstall = { updates.install() },
        )

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Описание и доступ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "ARDTT представляет собой простой туннельный клиент для постоянного защищённого соединения с упрощённым сценарием touch&GO.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Версия ${BuildConfig.VERSION_NAME} · режим: ${if (admin) "администратор" else "пользователь"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (admin) {
                    "Открыты Серверы и Логи."
                } else if (hasPin) {
                    "Введите PIN для доступа к разделам «Серверы» и «Логи»."
                } else {
                    "Установите PIN администратора (при первом вводе PIN будет создан)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!admin) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (pin.length < 4) {
                                adminHint = "PIN не короче 4 цифр"
                                return@launch
                            }
                            val ok = settings.unlockAdmin(pin)
                            adminHint = if (ok) "Режим администратора активирован" else "Неверный PIN"
                            if (ok) {
                                pin = ""
                                AppLog.i("Admin", "Unlocked via PIN")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(if (hasPin) "Разблокировать админа" else "Создать PIN и войти")
                }
            } else {
                RowSetting(
                    title = "Тестирование",
                    subtitle = "Журналы — после принятия соглашения.",
                    checked = testingMode,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            if (!TestingSessionGuard.canLeaveTestingSession(recordingActive)) {
                                refuseLeaveTestingSession()
                            } else {
                                scope.launch { settings.setTestingMode(false) }
                            }
                        } else {
                            showTestingAgreement = true
                        }
                    },
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settings.lockAdmin()
                            adminHint = "Снова режим пользователя"
                            pin = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Завершить сессию администратора")
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("Новый PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (pin.length < 4) {
                                adminHint = "Новый PIN не короче 4 цифр"
                                return@launch
                            }
                            settings.setAdminPin(pin)
                            pin = ""
                            adminHint = "PIN обновлён"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Сменить PIN")
                }
            }
            adminHint?.let {
                val hintColor = if (it == "Режим администратора активирован") {
                    NvpnColors.connected
                } else {
                    MaterialTheme.colorScheme.primary
                }
                Text(it, color = hintColor, style = MaterialTheme.typography.bodySmall)
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

    if (showBypassMethodDialog) {
        val highlightAlpha by animateFloatAsState(
            targetValue = if (highlightBypassDialog) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 500),
            label = "bypass_dialog_highlight",
        )
        NvpnDialog(
            title = "Метод обхода",
            onDismissRequest = { showBypassMethodDialog = false },
            dismissAction = NvpnDialogAction(
                text = "Закрыть",
                onClick = { showBypassMethodDialog = false },
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f + 0.52f * highlightAlpha),
                ),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(modifier = Modifier.padding(12.dp)) {
                    CallHashSettingsContent(showHeader = false)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Text(
                "В этом окне можно выполнить авторизацию, создать код звонка через ВКонтакте или ввести его вручную.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateSettingsCard(
    modifier: Modifier = Modifier,
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
        modifier = modifier,
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
                installReady = downloadedFile && !downloading,
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
    installReady: Boolean,
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
    val fillColor = if (installReady) NvpnColors.connected else colors.primary
    val trackColor = lerp(colors.surface, fillColor, 0.42f)
    val textColor = if (installReady) Color.White else colors.onPrimary
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
            color = textColor,
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
            "Без фонового доступа к геолокации туннель может не распознавать сеть в фоновом режиме. Для добавления текущей сети достаточно стандартного доступа к геолокации."
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        refreshWifi()
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            hint = "Разрешение геолокации предоставлено. Теперь можно добавить текущую сеть."
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
        Text("Доверенная WiFi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
