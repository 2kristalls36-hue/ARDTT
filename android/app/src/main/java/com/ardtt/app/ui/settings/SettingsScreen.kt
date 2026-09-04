package com.ardtt.app.ui.settings

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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Build
import com.ardtt.app.core.needsNotificationPermission
import com.ardtt.app.BuildConfig
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.hasTrustedWifiBackgroundPermission
import com.ardtt.app.core.hasTrustedWifiLocationPermission
import com.ardtt.app.core.nextTrustedWifiPermissionAsk
import com.ardtt.app.core.readConnectedWifiState
import com.ardtt.app.core.trustedWifiAccessProblem
import com.ardtt.app.core.TrustedWifiAccessProblem
import com.ardtt.app.core.TrustedWifiPermissionAsk
import com.ardtt.app.ui.HideIpCopy
import com.ardtt.app.ui.PathModeCopy
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.TestingSessionGuard
import com.ardtt.app.ui.connectionControlsLocked
import com.ardtt.app.ui.commitHideIp
import com.ardtt.app.ui.commitPathMode
import com.ardtt.app.ui.persistDialPath
import com.ardtt.app.ui.persistSilentRecreate
import com.ardtt.app.ui.persistThemeMode
import com.ardtt.app.legal.TestingModeAgreement
import com.ardtt.app.telemetry.TelemetryRecorder
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.AppSectionCard
import com.ardtt.app.ui.components.DialPathChipRow
import com.ardtt.app.ui.components.HideIpChipRow
import com.ardtt.app.ui.components.PathModeChipRow
import com.ardtt.app.ui.components.TabPageHeader
import com.ardtt.app.ui.components.ThemeModeChipRow
import com.ardtt.app.ui.components.EdgeFeedColumn
import com.ardtt.app.ui.components.ArdttBottomChrome
import com.ardtt.app.ui.components.rememberPullRefresh
import com.ardtt.app.ui.tunnel.DonateSupportBanner
import com.ardtt.app.ui.components.rememberSmartHaptics
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.update.AppUpdateController
import com.ardtt.app.update.AppUpdateInfo
import com.ardtt.app.update.updateCardCopy
import com.ardtt.app.update.updatePrimaryActionLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettingsRepository,
    isRecording: Boolean = false,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val updates = remember { AppUpdateController.get(context) }
    val updateUi by updates.ui.collectAsStateWithLifecycle()
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val testingMode by settings.testingModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val recorder = remember { TelemetryRecorder.get(context) }
    val recorderActive by recorder.isRecording.collectAsStateWithLifecycle()
    val recordingActive = isRecording || recorderActive
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val notifVisible by settings.vpnNotificationVisibleFlow.collectAsStateWithLifecycle(initialValue = true)
    val hideTunnelQuickSettings by settings.hideTunnelQuickSettingsFlow.collectAsStateWithLifecycle(initialValue = false)
    val unlockConnControls by settings.unlockConnControlsFlow.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val classicAppearance by settings.classicAppearanceEnabled.collectAsStateWithLifecycle(initialValue = false)
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
    val vpnSessionActive = connUi.state == ConnState.Connecting ||
        connUi.state == ConnState.Connected ||
        connUi.state == ConnState.PausedTrustedWifi ||
        connUi.state == ConnState.Disconnecting
    val vpnLocked = connectionControlsLocked(vpnSessionActive, unlockConnControls)
    val scope = rememberCoroutineScope()
    var adminHint by remember { mutableStateOf<String?>(null) }
    var testingHint by remember { mutableStateOf<String?>(null) }
    var showTestingAgreement by remember { mutableStateOf(false) }
    var highlightAppearanceCard by remember { mutableStateOf(false) }
    val refuseLeaveTestingSession: () -> Unit = {
        testingHint = TestingSessionGuard.STOP_RECORDING_FIRST
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

    LaunchedEffect(openUpdateDownload, updateUi.visible, updateUi.downloading, updateUi.downloadedFile) {
        if (!openUpdateDownload) return@LaunchedEffect
        if (!updateUi.visible) {
            updates.checkAndWait()
        }
        kotlinx.coroutines.delay(120)
        runCatching { updateBringIntoView.bringIntoView() }
        if (
            !updateUi.downloading &&
            updateUi.downloadedFile == null &&
            updateUi.available?.isNewer == true
        ) {
            updates.download()
        }
        PendingUiAction.consumeOpenUpdateDownload()
    }
    LaunchedEffect(openAppearanceSettings) {
        if (!openAppearanceSettings) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { appearanceBringIntoView.bringIntoView() }
        highlightAppearanceCard = true
        kotlinx.coroutines.delay(550)
        highlightAppearanceCard = false
        PendingUiAction.consumeOpenAppearanceSettings()
    }

    LaunchedEffect(Unit) {
        updates.checkInBackground()
    }

    val pull = rememberPullRefresh {
        updates.checkAndWait()
    }

    LaunchedEffect(openCallHash) {
        if (!openCallHash) return@LaunchedEffect
        PendingUiAction.consumeCallHashSettings()
        scope.launch {
            kotlinx.coroutines.delay(80)
            runCatching { callHashBringIntoView.bringIntoView() }
        }
    }

    EdgeFeedColumn(
        scrollState = scrollState,
        refreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
        header = {
            TabPageHeader(
                title = "Настройки приложения",
                subtitle = "Режим: ${if (admin) "администратор" else "пользователь"}",
            )
        },
    ) {
        if (updateUi.visible) {
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
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !admin && vpnLocked ->
                        "Во время соединения настройки подключения недоступны."
                    !admin ->
                        "Выберите режим подключения. Рекомендуется «Авто»."
                    vpnLocked -> "Недоступно во время соединения."
                    vpnSessionActive && unlockConnControls ->
                        "Соединение активно. Изменение маршрута применяется сразу, без отключения."
                    else -> "Автоматический режим: в Wi‑Fi всегда прямое, в мобильной сети прямое с резервом обхода. Доступно ручное переключение."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PathModeChipRow(
                pathMode = pathMode,
                hasCallHash = connUi.hasCallHash,
                enabled = !vpnLocked,
                onSelect = { mode ->
                    haptics.tick()
                    scope.launch { commitPathMode(settings, conn, mode) }
                },
                onNeedCallHash = {
                    haptics.tick()
                    scope.launch {
                        kotlinx.coroutines.delay(80)
                        runCatching { callHashBringIntoView.bringIntoView() }
                    }
                },
            )
            Text(
                PathModeCopy.help(pathMode, connUi.hasCallHash, compact = false),
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
            HideIpChipRow(
                hideIp = hideIp,
                enabled = !vpnLocked,
                onSelect = { enabled ->
                    haptics.tick()
                    scope.launch { commitHideIp(settings, conn, enabled) }
                },
            )
            if (admin) {
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
        }

        // User-facing WiFi pause controls should always be available in Settings.
        TrustedWifiSettingsCard(settings = settings)

        AppSectionCard(
            modifier = Modifier.bringIntoViewRequester(callHashBringIntoView),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Метод обхода",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (admin) {
                    "Источник параметров обхода и код звонка. Авто — vkcalls, иначе резерв."
                } else {
                    "Код звонка для режима «Обход». Создайте его через ВКонтакте или введите вручную."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (admin) {
                DialPathChipRow(
                    dial = dial,
                    onSelect = { path -> scope.launch { persistDialPath(settings, path) } },
                )
            }
            RowSetting(
                title = "Обновлять звонок автоматически",
                subtitle = "Новый код звонка создаётся без подтверждения. Требуется активная сессия ВКонтакте.",
                checked = silent,
                onCheckedChange = { scope.launch { persistSilentRecreate(settings, it) } },
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
            Text("Оформление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val appearance = settingsAppearanceSections(admin = admin, recordingActive = recordingActive)
            if (appearance.showThemeControls) {
                ThemeModeChipRow(
                    themeMode = themeMode,
                    onSelect = { mode -> scope.launch { persistThemeMode(settings, mode) } },
                )
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
            if (appearance.showClassicLook) {
                RowSetting(
                    title = "Классический вид",
                    subtitle = if (classicAppearance) {
                        "Стандартный градиент, без обоев и дронов."
                    } else {
                        "Выключен. На вкладках — иллюстрации, на туннеле — дроны при обходе."
                    },
                    checked = classicAppearance,
                    onCheckedChange = { scope.launch { settings.setClassicAppearanceEnabled(it) } },
                )
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

        DonateSupportBanner()

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Тестирование", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Вкладка «Тест» и запись журналов — после принятия соглашения. Доступно в любом режиме.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowSetting(
                title = "Режим тестирования",
                subtitle = if (testingMode) {
                    "Вкладка «Тест» открыта."
                } else {
                    "Выключен. Журналы телеметрии скрыты."
                },
                checked = testingMode,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        if (!TestingSessionGuard.canLeaveTestingSession(recordingActive)) {
                            refuseLeaveTestingSession()
                        } else {
                            testingHint = null
                            scope.launch { settings.setTestingMode(false) }
                        }
                    } else {
                        testingHint = null
                        showTestingAgreement = true
                    }
                },
            )
            testingHint?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }

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
                "Версия ${BuildConfig.VERSION_NAME}${if (admin) " · режим: администратор" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (admin) {
                    "Открыты «Сервера», «Деплой» и «Журналы»."
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
                            adminHint = "Режим администратора активирован."
                            AppLog.i("Admin", "Unlocked via slider")
                        }
                    },
                    onIncomplete = {
                        adminHint = "Доведите ползунок до конца."
                    },
                )
            } else {
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
                val hintColor = if (it == "Режим администратора активирован") {
                    ArdttColors.connected
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
    val fillColor = if (installReady) ArdttColors.connected else colors.primary
    val trackColor = lerp(colors.surface, fillColor, 0.42f)
    val textColor = if (installReady) Color.White else colors.onPrimary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ArdttBottomChrome.ButtonHeight)
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
