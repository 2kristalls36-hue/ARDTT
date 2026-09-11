package com.ardtt.app.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.BuildConfig
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.TrustedWifiAccessProblem
import com.ardtt.app.core.TrustedWifiPermissionAsk
import com.ardtt.app.core.hasTrustedWifiBackgroundPermission
import com.ardtt.app.core.hasTrustedWifiLocationPermission
import com.ardtt.app.core.needsNotificationPermission
import com.ardtt.app.core.nextTrustedWifiPermissionAsk
import com.ardtt.app.core.readConnectedWifiState
import com.ardtt.app.core.trustedWifiAccessProblem
import com.ardtt.app.core.trustedWifiAddButtonLabel
import com.ardtt.app.legal.TestingModeAgreement
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.telemetry.TelemetryRecorder
import com.ardtt.app.ui.HideIpCopy
import com.ardtt.app.ui.PathModeCopy
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.TestingSessionGuard
import com.ardtt.app.ui.commitHideIp
import com.ardtt.app.ui.commitPathMode
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttSettingBlock
import com.ardtt.app.ui.components.control.ArdttSwitchRow
import com.ardtt.app.ui.components.control.DialPathChipRow
import com.ardtt.app.ui.components.control.HideIpChipRow
import com.ardtt.app.ui.components.control.PathModeChipRow
import com.ardtt.app.ui.components.control.ThemeModeChipRow
import com.ardtt.app.ui.components.control.rememberArdttHaptics
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttDestinationRow
import com.ardtt.app.ui.components.layout.ArdttFeedScaffold
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.components.surface.ArdttSectionTitle
import com.ardtt.app.ui.components.surface.ArdttSettingsCard
import com.ardtt.app.ui.components.surface.sectionCardContourBorder
import com.ardtt.app.ui.connectionControlsLocked
import com.ardtt.app.ui.persistDialPath
import com.ardtt.app.ui.persistSilentRecreate
import com.ardtt.app.ui.persistThemeMode
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.connectedStatusColor
import com.ardtt.app.ui.tunnel.DonateSupportBanner
import com.ardtt.app.update.AppUpdateController
import com.ardtt.app.update.AppUpdateInfo
import com.ardtt.app.update.updateCardCopy
import com.ardtt.app.update.updatePrimaryActionLabel
import kotlinx.coroutines.launch

/** Strings of the admin-mode card and the mode line in the header. */
internal object AdminModeCopy {
    const val SECTION_TITLE = "Описание и доступ"
    const val ABOUT =
        "ARDTT представляет собой простой туннельный клиент для постоянного защищённого соединения с упрощённым сценарием touch&GO."
    const val ADMIN_SCOPE = "Открыты «Серверы», «Деплой» и диагностика."
    const val UNLOCK_HINT = "Переместите ползунок вправо до конца."
    const val UNLOCKED = "Режим администратора активирован."
    const val INCOMPLETE = "Доведите ползунок до конца."
    const val END_SESSION = "Завершить сессию администратора"
    const val END_SESSION_BODY =
        "Вкладки «Серверы» и «Диагностика» будут скрыты, приложение вернётся в пользовательский режим. " +
            "Серверы, профили и настройки сохранятся."
    const val END_SESSION_CONFIRM = "Завершить"
    const val END_SESSION_CANCEL = "Отмена"
    const val MODE_ADMIN = "администратор"
    const val MODE_USER = "пользователь"

    fun modeSubtitle(admin: Boolean): String = "Режим: ${if (admin) MODE_ADMIN else MODE_USER}"

    fun versionLine(versionName: String, admin: Boolean): String =
        "Версия $versionName" + if (admin) " · режим: $MODE_ADMIN" else ""
}

private object SettingsDefaults {
    /** Contour alpha the appearance card flashes to when opened from a deep link. */
    const val HighlightContourAlpha = 0.66f

    /** Track tint of the filling update button: surface mixed toward the accent. */
    const val UpdateTrackMix = 0.42f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettingsRepository,
    isRecording: Boolean = false,
    onOpenTesting: (() -> Unit)? = null,
    onOpenExceptions: (() -> Unit)? = null,
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
    val haptics = rememberArdttHaptics(uiHapticsEnabled)
    val openCallHash by PendingUiAction.openCallHashSettings.collectAsStateWithLifecycle()
    val callHashBringIntoView = remember { BringIntoViewRequester() }
    val openUpdateDownload by PendingUiAction.openUpdateDownload.collectAsStateWithLifecycle()
    val updateBringIntoView = remember { BringIntoViewRequester() }
    val openAppearanceSettings by PendingUiAction.openAppearanceSettings.collectAsStateWithLifecycle()
    val appearanceBringIntoView = remember { BringIntoViewRequester() }
    val scrollState = rememberScrollState()
    val vpnSessionActive = connUi.state.holdsUserSession()
    val vpnLocked = connectionControlsLocked(vpnSessionActive, unlockConnControls)
    val scope = rememberCoroutineScope()
    var adminHint by remember { mutableStateOf<String?>(null) }
    var testingHint by remember { mutableStateOf<String?>(null) }
    var showTestingAgreement by remember { mutableStateOf(false) }
    var showEndAdminConfirm by remember { mutableStateOf(false) }
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

    ArdttFeedScaffold(
        scrollState = scrollState,
        refreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
        header = {
            ArdttTabHeader(
                title = "Настройки приложения",
                subtitle = AdminModeCopy.modeSubtitle(admin),
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

        ArdttSettingsCard {
            ArdttSectionTitle("Подключение")
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
            ArdttSettingBlock(
                title = "Исходящий адрес",
                subtitle = HideIpCopy.subtitle(hideIp),
            ) {
                HideIpChipRow(
                    hideIp = hideIp,
                    enabled = !vpnLocked,
                    onSelect = { enabled ->
                        haptics.tick()
                        scope.launch { commitHideIp(settings, conn, enabled) }
                    },
                )
            }
            if (admin) {
                ArdttSwitchRow(
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
                ArdttSwitchRow(
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

        ArdttSettingsCard(
            modifier = Modifier.bringIntoViewRequester(callHashBringIntoView),
        ) {
            ArdttSectionTitle("Метод обхода")
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
            ArdttSwitchRow(
                title = "Обновлять звонок автоматически",
                subtitle = "Новый код звонка создаётся без подтверждения. Требуется активная сессия ВКонтакте.",
                checked = silent,
                onCheckedChange = { scope.launch { persistSilentRecreate(settings, it) } },
            )
            CallHashSettingsContent(showHeader = false)
        }

        if (admin && onOpenExceptions != null) {
            ArdttDestinationRow(
                icon = Icons.Outlined.FilterList,
                title = "Правила обхода",
                subtitle = "Приложения и сайты вне туннеля",
                onClick = onOpenExceptions,
            )
        }

        // User-facing WiFi pause controls should always be available in Settings.
        TrustedWifiSettingsCard(settings = settings)

        val appearanceHighlightAlpha by animateFloatAsState(
            targetValue = if (highlightAppearanceCard) 1f else 0f,
            animationSpec = tween(durationMillis = ArdttMotion.Standard),
            label = "appearance_card_highlight",
        )
        ArdttSettingsCard(
            modifier = Modifier.bringIntoViewRequester(appearanceBringIntoView),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
            border = sectionCardContourBorder(
                alpha = ArdttSectionCardDefaults.ContourAlpha +
                    (SettingsDefaults.HighlightContourAlpha - ArdttSectionCardDefaults.ContourAlpha) *
                    appearanceHighlightAlpha,
            ),
        ) {
            ArdttSectionTitle("Оформление")
            val appearance = settingsAppearanceSections(admin = admin, recordingActive = recordingActive)
            if (appearance.showThemeControls) {
                ThemeModeChipRow(
                    themeMode = themeMode,
                    onSelect = { mode -> scope.launch { persistThemeMode(settings, mode) } },
                )
                ArdttSwitchRow(
                    title = "Динамические цвета",
                    subtitle = if (dynamicColors) {
                        "Цвета кнопок и акцентов подстраиваются под текущие обои."
                    } else {
                        "Используется базовая палитра темы без адаптации к обоям."
                    },
                    checked = dynamicColors,
                    onCheckedChange = { scope.launch { settings.setDynamicColors(it) } },
                )
                ArdttSwitchRow(
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
                ArdttSwitchRow(
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
            ArdttSwitchRow(
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

        ArdttSettingsCard {
            ArdttSectionTitle("Тестирование")
            Text(
                if (admin) {
                    "Запись журналов — в разделе «Диагностика» после принятия соглашения."
                } else {
                    "Запись журналов — из журнала событий или отсюда, после принятия соглашения."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttSwitchRow(
                title = "Режим тестирования",
                subtitle = if (testingMode) {
                    "Журналы телеметрии доступны."
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
            if (testingMode && onOpenTesting != null) {
                ArdttDestinationRow(
                    icon = Icons.Outlined.Science,
                    title = "Открыть тестирование",
                    subtitle = "Запись, хранилище и отправка журналов",
                    onClick = onOpenTesting,
                )
            }
        }

        ArdttSettingsCard {
            ArdttSectionTitle(AdminModeCopy.SECTION_TITLE)
            Text(
                AdminModeCopy.ABOUT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                AdminModeCopy.versionLine(BuildConfig.VERSION_NAME, admin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (admin) AdminModeCopy.ADMIN_SCOPE else AdminModeCopy.UNLOCK_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!admin) {
                AdminUnlockSlider(
                    onUnlocked = {
                        scope.launch {
                            settings.unlockAdmin()
                            adminHint = AdminModeCopy.UNLOCKED
                            AppLog.i("Admin", "Unlocked via slider")
                        }
                    },
                    onIncomplete = {
                        adminHint = AdminModeCopy.INCOMPLETE
                    },
                )
            } else {
                ArdttButton(
                    text = AdminModeCopy.END_SESSION,
                    onClick = { showEndAdminConfirm = true },
                    variant = ArdttButtonVariant.Outlined,
                    fillMaxWidth = true,
                )
            }
            adminHint?.let {
                val hintColor = if (it == AdminModeCopy.UNLOCKED) {
                    connectedStatusColor()
                } else {
                    MaterialTheme.colorScheme.primary
                }
                Text(it, color = hintColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showEndAdminConfirm) {
        ArdttDialog(
            title = AdminModeCopy.END_SESSION,
            onDismissRequest = { showEndAdminConfirm = false },
            confirmAction = ArdttDialogAction(
                text = AdminModeCopy.END_SESSION_CONFIRM,
                onClick = {
                    showEndAdminConfirm = false
                    scope.launch {
                        settings.lockAdmin()
                        adminHint = null
                        AppLog.i("Admin", "Locked via settings")
                    }
                },
            ),
            dismissAction = ArdttDialogAction(
                text = AdminModeCopy.END_SESSION_CANCEL,
                onClick = { showEndAdminConfirm = false },
            ),
        ) {
            Text(
                AdminModeCopy.END_SESSION_BODY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    ArdttSettingsCard(modifier = modifier) {
        ArdttSectionTitle("Обновление")
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
                shape = ArdttShapes.Card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.SmallPlus),
                    verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
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
    val shape = ArdttShapes.Control
    val animatedProgress by animateFloatAsState(
        targetValue = if (filling) progress.coerceIn(0f, 1f) else 1f,
        label = "update_fill_progress",
    )
    val fillColor = if (installReady) ArdttColors.Connected else colors.primary
    val trackColor = lerp(colors.surface, fillColor, SettingsDefaults.UpdateTrackMix)
    // The label sits on the fill (or, while filling, on the track behind the
    // not-yet-filled part); pick whichever content color reads on both.
    val textColor = if (installReady) ArdttColors.OnConnected else colors.onPrimary
    val progressPercent = (animatedProgress * 100).toInt()
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
            .clickable(onClick = onClick, role = Role.Button)
            .semantics {
                contentDescription = text
                if (filling) stateDescription = "Загрузка $progressPercent%"
            },
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

    ArdttSettingsCard {
        ArdttSectionTitle("Доверенная WiFi")
        Text(
            "В этих сетях туннель приостанавливается. При выходе подключение восстанавливается. Добавляется только текущая сеть.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ArdttSwitchRow(
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
        if (wifi.connected && !wifi.ssidAvailable) {
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
        ssids.forEach { ssid ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ssid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                ArdttButton(
                    text = "Удалить",
                    onClick = { scope.launch { settings.removeTrustedWifiSsid(ssid) } },
                    variant = ArdttButtonVariant.Text,
                    size = ArdttButtonSize.Compact,
                    fillMaxWidth = false,
                )
            }
        }
        hint?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        ArdttButton(
            text = trustedWifiAddButtonLabel(wifi),
            onClick = {
                askTrustedWifiPermissions(wantBackground = false, addAfter = true)
            },
            enabled = enabled,
            variant = ArdttButtonVariant.Outlined,
            fillMaxWidth = true,
        )
    }
}

