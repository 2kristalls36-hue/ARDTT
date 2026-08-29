package com.nonamevpn.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
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
import com.nonamevpn.app.core.hasNearbyWifiDevicesPermission
import com.nonamevpn.app.core.hasTrustedWifiBackgroundPermission
import com.nonamevpn.app.core.hasTrustedWifiForegroundPermission
import com.nonamevpn.app.core.readConnectedWifiState
import com.nonamevpn.app.core.trustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiAccessProblem
import com.nonamevpn.app.legal.TestingModeAgreement
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedColumn
import com.nonamevpn.app.update.AppUpdateInfo
import com.nonamevpn.app.update.AppUpdateManager
import java.io.File
import kotlinx.coroutines.launch

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
    val themePalette by settings.themePaletteFlow.collectAsStateWithLifecycle(initialValue = "espresso")
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val connUi by conn.ui.collectAsStateWithLifecycle()
    val vpnLocked = connUi.state == ConnState.Connecting ||
        connUi.state == ConnState.Connected ||
        connUi.state == ConnState.PausedTrustedWifi ||
        connUi.state == ConnState.Disconnecting
    val scope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager(context) }
    var adminHint by remember { mutableStateOf<String?>(null) }
    var showTestingAgreement by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloadedUpdate by remember { mutableStateOf<File?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.i("NotifPrep", "settings POST_NOTIFICATIONS granted=$granted")
        conn.refreshVpnNotification()
        if (!granted) {
            adminHint = "Без разрешения система не сможет отображать уведомление о состоянии VPN."
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

    LaunchedEffect(Unit) {
        updateChecking = true
        val result = updateManager.check()
        updateChecking = false
        result.onSuccess { info ->
            updateInfo = info
            updateMessage = if (info.isNewer) {
                "Доступна версия ${info.versionName}"
            } else {
                "Установлена актуальная версия"
            }
        }.onFailure {
            updateMessage = it.message ?: "Не удалось проверить обновления"
        }
    }

    EdgeFeedColumn {
        val modeLabel = if (admin) "администратор" else "пользователь"
        AppTabPageHeader(
            tabTitle = "Настройки",
            subtitle = "Режим: $modeLabel · ${BuildConfig.VERSION_NAME}",
        )

        UpdateSettingsCard(
            info = updateInfo,
            checking = updateChecking,
            downloading = updateDownloading,
            progress = updateProgress,
            message = updateMessage,
            downloadedFile = downloadedUpdate,
            onCheck = {
                scope.launch {
                    updateChecking = true
                    updateMessage = "Проверяем обновления…"
                    val result = updateManager.check()
                    updateChecking = false
                    result.onSuccess { info ->
                        updateInfo = info
                        downloadedUpdate = null
                        updateMessage = if (info.isNewer) {
                            "Доступна версия ${info.versionName}"
                        } else {
                            "Установлена актуальная версия"
                        }
                    }.onFailure {
                        updateMessage = it.message ?: "Не удалось проверить обновления"
                    }
                }
            },
            onDownload = {
                val info = updateInfo ?: return@UpdateSettingsCard
                scope.launch {
                    updateDownloading = true
                    updateProgress = 0f
                    updateMessage = "Выполняется загрузка ${info.versionName}…"
                    val result = updateManager.download(info) { updateProgress = it }
                    updateDownloading = false
                    result.onSuccess { file ->
                        downloadedUpdate = file
                        updateMessage = "Файл загружен. Запускается установка."
                        runCatching { updateManager.install(file) }
                            .onFailure { updateMessage = it.message ?: "Не удалось открыть установщик" }
                    }.onFailure {
                        updateMessage = it.message ?: "Не удалось скачать APK"
                    }
                }
            },
            onInstall = {
                val file = downloadedUpdate ?: return@UpdateSettingsCard
                runCatching { updateManager.install(file) }
                    .onFailure { updateMessage = it.message ?: "Не удалось открыть установщик" }
            },
        )

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Тема оформления",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialChip("Системная", themeMode == "system", { scope.launch { settings.setThemeMode("system") } }, Modifier.weight(1f))
                DialChip("Светлая", themeMode == "light", { scope.launch { settings.setThemeMode("light") } }, Modifier.weight(1f))
                DialChip("Тёмная", themeMode == "dark", { scope.launch { settings.setThemeMode("dark") } }, Modifier.weight(1f))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Динамические цвета", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Material You",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = { scope.launch { settings.setDynamicColor(it) } },
                    )
                }
            }
            if (!dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(
                    "Цветовая палитра",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaletteCircle("indigo", 0xFF5B588D, themePalette) {
                        scope.launch { settings.setThemePalette(it) }
                    }
                    PaletteCircle("forest", 0xFF5F5D68, themePalette) {
                        scope.launch { settings.setThemePalette(it) }
                    }
                    PaletteCircle("espresso", 0xFF6D4C41, themePalette) {
                        scope.launch { settings.setThemePalette(it) }
                    }
                }
            }
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (vpnLocked) {
                    "Изменение маршрута и исходящего адреса недоступно, пока установлено соединение."
                } else {
                    "В автоматическом режиме используется прямое подключение; " +
                        "при его недоступности выполняется переход на обход. " +
                        "Маршрут можно задать принудительно."
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
                    { scope.launch { settings.setPathMode("bypass") } },
                    Modifier.weight(1f),
                    enabled = !vpnLocked,
                )
            }
            Text(
                when (pathMode) {
                    "direct" -> "Используется только прямое подключение."
                    "bypass" -> "Используется только обход. Требуется код звонка."
                    else -> "Приоритет прямого подключения, резерв — обход."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            RowSetting(
                title = "Сокрытие исходящего адреса",
                subtitle = if (hideIp) {
                    "Исходящий трафик направляется через Cloudflare WARP."
                } else {
                    "Исходящий трафик использует адрес сервера."
                },
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
                title = "Уведомление о состоянии VPN",
                subtitle = if (notifVisible) {
                    "В области уведомлений отображаются состояние, скорость и команда остановки."
                } else {
                    "Уведомление скрыто из области уведомлений. Система может оставлять служебную запись без звука."
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

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Обход", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Способ получения параметров обхода. Автоматический режим использует vkcalls " +
                    "и при необходимости резервный вариант. Подключение выполняется по сохранённому коду звонка. " +
                    "Код звонка задаётся в разделе ниже.",
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
                title = "Автоматическое обновление звонка",
                subtitle = "При недействительном коде звонок создаётся без дополнительного подтверждения. Требуется сессия ВКонтакте.",
                checked = silent,
                onCheckedChange = { scope.launch { settings.setSilentRecreate(it) } },
            )
        }

        CallHashSettingsCard()

        TrustedWifiSettingsCard(settings = settings)

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Администратор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (admin) {
                    "Доступны разделы «Серверы», «Деплой» и «Журналы». Для вкладки телеметрии включите режим тестирования."
                } else {
                    "Переместите ползунок вправо до конца шкалы, чтобы открыть функции администратора."
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
                        adminHint = "Переместите ползунок до конца шкалы."
                    },
                )
            } else {
                RowSetting(
                    title = "Тестирование",
                    subtitle = "Диагностические журналы доступны только после принятия соглашения.",
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
                            adminHint = "Сессия администратора завершена."
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
    checking: Boolean,
    downloading: Boolean,
    progress: Float,
    message: String?,
    downloadedFile: File?,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Обновления", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Текущая версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (info != null) {
            Surface(
                color = if (info.isNewer) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (info.isNewer) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (info.isNewer) {
                            "Доступна ${info.versionName} (${info.versionCode})"
                        } else {
                            "Обновлений нет"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (info.sizeBytes > 0) {
                        Text(
                            "Размер: ${formatUpdateSize(info.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (info.notes.isNotBlank()) {
                        Text(info.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (downloading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCheck,
                enabled = !checking && !downloading,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(if (checking) "Проверка…" else "Проверить")
            }
            if (downloadedFile != null) {
                OutlinedButton(
                    onClick = onInstall,
                    enabled = !checking && !downloading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Установить")
                }
            } else if (info?.isNewer == true) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = !checking && !downloading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Скачать")
                }
            }
        }
    }
}

private fun formatUpdateSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
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

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshWifi()
        hint = if (granted) {
            "Фоновый доступ к локации разрешён"
        } else {
            "Без фоновой локации VPN не увидит SSID в фоне — для добавления в настройках хватает обычной локации"
        }
    }
    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshWifi()
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !hasTrustedWifiBackgroundPermission(context) &&
                !hasNearbyWifiDevicesPermission(context)
            ) {
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                hint = "Разрешение геолокации получено. Можно добавить текущую сеть."
            }
        } else {
            hint = "Для определения имени сети Wi‑Fi требуется геолокация или доступ к устройствам поблизости."
        }
    }
    val nearbyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshWifi()
        if (granted) {
            hint = "Имя сети Wi‑Fi можно определять без геолокации."
        } else if (!hasTrustedWifiForegroundPermission(context)) {
            fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            hint = "Без доступа к Wi‑Fi имя сети может быть недоступно."
        }
    }

    fun requestSsidPermission() {
        when {
            hasTrustedWifiForegroundPermission(context) -> Unit
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNearbyWifiDevicesPermission(context) ->
                nearbyLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Доверенная Wi‑Fi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "В указанных сетях VPN приостанавливается автоматически. При выходе из сети, отключении параметра или удалении записи подключение восстанавливается. Добавляется только текущая сеть Wi‑Fi. Пока имя сети не определено, автоматический режим не переводит обход на прямое подключение.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowSetting(
            title = "Включить",
            subtitle = when (val p = trustedWifiAccessProblem(context, requireBackground = false)) {
                TrustedWifiAccessProblem.ForegroundPermission ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        "Требуется разрешение «Устройства поблизости» или доступ к геолокации."
                    } else {
                        "Требуется разрешение геолокации."
                    }
                TrustedWifiAccessProblem.LocationDisabled -> "Включите геолокацию в системе."
                TrustedWifiAccessProblem.BackgroundPermission -> "Требуется фоновая геолокация."
                null -> when {
                    hasNearbyWifiDevicesPermission(context) ->
                        if (ssids.isEmpty()) "Добавьте хотя бы одну сеть." else "${ssids.size} сетей"
                    !hasTrustedWifiBackgroundPermission(context) ->
                        "Для автоматической паузы в фоне предоставьте геолокацию «Всегда»."
                    ssids.isEmpty() -> "Добавьте хотя бы одну сеть"
                    else -> "${ssids.size} сетей"
                }
            },
            checked = enabled,
            onCheckedChange = { on ->
                if (on) requestSsidPermission()
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    hasTrustedWifiForegroundPermission(context) &&
                    !hasNearbyWifiDevicesPermission(context) &&
                    !hasTrustedWifiBackgroundPermission(context)
                ) {
                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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
                        "Сеть Wi‑Fi подключена, но нет разрешения на определение имени."
                    TrustedWifiAccessProblem.LocationDisabled -> "Сеть Wi‑Fi подключена, но геолокация выключена."
                    else -> "Сеть Wi‑Fi подключена, имя сети недоступно. Предоставьте доступ к Wi‑Fi."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = {
                if (!hasTrustedWifiForegroundPermission(context)) {
                    requestSsidPermission()
                    return@OutlinedButton
                }
                val fresh = readConnectedWifiState(context, requireBackground = false)
                wifi = fresh
                val ssid = fresh.ssid
                if (ssid.isBlank()) {
                    hint = when (fresh.accessProblem) {
                        TrustedWifiAccessProblem.LocationDisabled -> "Включите геолокацию."
                        TrustedWifiAccessProblem.ForegroundPermission -> "Предоставьте разрешение геолокации."
                        else -> "Имя сети не определено. Подключитесь к Wi‑Fi и предоставьте доступ к геолокации."
                    }
                } else {
                    scope.launch {
                        settings.addTrustedWifiSsid(ssid)
                        hint = "Добавлено: $ssid"
                    }
                }
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
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
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

@Composable
private fun PaletteCircle(
    paletteId: String,
    colorHex: Long,
    selectedId: String,
    onClick: (String) -> Unit,
) {
    val selected = paletteId == selectedId
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(colorHex))
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable { onClick(paletteId) },
    )
}
