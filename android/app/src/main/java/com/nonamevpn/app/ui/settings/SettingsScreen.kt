package com.nonamevpn.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.lerp
import com.nonamevpn.app.ui.components.NvpnFloatingShell
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Build
import com.nonamevpn.app.core.needsNotificationPermission
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.bypass.DialPath
import androidx.compose.foundation.animateScrollTo
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.hasTrustedWifiBackgroundPermission
import com.nonamevpn.app.core.hasTrustedWifiForegroundPermission
import com.nonamevpn.app.core.readConnectedWifiState
import com.nonamevpn.app.core.trustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiAccessProblem
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedColumn
import com.nonamevpn.app.update.AppUpdateInfo
import com.nonamevpn.app.update.AppUpdateManager
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettingsRepository,
    isRecording: Boolean = false,
    scrollToDial: Boolean = false,
    onScrolledToDial: () -> Unit = {},
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
    val scope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager(context) }
    var adminHint by remember { mutableStateOf<String?>(null) }
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
            adminHint = "Без разрешения Android плашка в шторке не появится"
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

    val scrollState = rememberScrollState()
    var dialCardOffsetY by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(scrollToDial, dialCardOffsetY) {
        if (scrollToDial && dialCardOffsetY >= 0f) {
            scrollState.animateScrollTo(dialCardOffsetY.toInt().coerceAtLeast(0))
            onScrolledToDial()
        }
    }

    EdgeFeedColumn(scrollState = scrollState) {
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
                    updateMessage = "Скачиваем ${info.versionName}…"
                    val result = updateManager.download(info) { updateProgress = it }
                    updateDownloading = false
                    result.onSuccess { file ->
                        downloadedUpdate = file
                        updateMessage = "APK скачан — запускаем установку"
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

        if (!isRecording) {
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
                    ChoiceChipButton(
                        label = "Сист.",
                        selected = themeMode == "system",
                        enabled = true,
                        onClick = { scope.launch { settings.setThemeMode("system") } },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Свет.",
                        selected = themeMode == "light",
                        enabled = true,
                        onClick = { scope.launch { settings.setThemeMode("light") } },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Темн.",
                        selected = themeMode == "dark",
                        enabled = true,
                        onClick = { scope.launch { settings.setThemeMode("dark") } },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                "Авто — прямое (AmneziaWG), при недоступности резерв обход (RAW через TURN). " +
                    "Можно принудительно выбрать один путь для теста.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChipButton(
                    label = "Авто",
                    selected = pathMode == "auto",
                    enabled = true,
                    onClick = { scope.launch { settings.setPathMode("auto") } },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Прямое",
                    selected = pathMode == "direct",
                    enabled = true,
                    onClick = { scope.launch { settings.setPathMode("direct") } },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Обход",
                    selected = pathMode == "bypass",
                    enabled = true,
                    onClick = { scope.launch { settings.setPathMode("bypass") } },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                when (pathMode) {
                    "direct" -> "Только AmneziaWG (AWG)."
                    "bypass" -> "Только обход RAW через звонок (нужен hash)."
                    else -> "Приоритет AWG, резерв RAW/обход."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            RowSetting(
                title = "Скрыть свой IP",
                subtitle = if (hideIp) {
                    "Включено — выход через Cloudflare (не IP VPS)"
                } else {
                    "Выход в интернет через Cloudflare вместо адреса VPS"
                },
                checked = hideIp,
                enabled = true,
                onCheckedChange = {
                    scope.launch {
                        settings.setHideIp(it)
                        conn.setHideIp(it)
                    }
                },
            )
            RowSetting(
                title = "Плашка VPN в шторке",
                subtitle = if (notifVisible) {
                    "Живой статус, скорость и кнопка «Остановить»"
                } else {
                    "Скрыта из основной шторки; Android всё равно оставляет тихую запись службы в «Без звука»"
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
            modifier = Modifier.onGloballyPositioned { coordinates ->
                dialCardOffsetY = coordinates.positionInParent().y
            },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Обход (дозвон)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Как получать TURN для обхода (RAW). Авто: vkcalls → legacy. Connect анонимный по hash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChipButton(
                    label = "Авто",
                    selected = dial == "auto",
                    enabled = true,
                    onClick = { scope.launch { settings.setDialPath("auto") } },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "vkcalls",
                    selected = dial == "vkcalls",
                    enabled = true,
                    onClick = { scope.launch { settings.setDialPath("vkcalls") } },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChipButton(
                    label = "Капча",
                    selected = dial == "legacy",
                    enabled = true,
                    onClick = { scope.launch { settings.setDialPath("legacy") } },
                    modifier = Modifier.weight(1f),
                )
            }
            RowSetting(
                title = "Тихий recreate звонка",
                subtitle = "Без диалога, если hash «умер» (нужна сессия VK)",
                checked = silent,
                onCheckedChange = { scope.launch { settings.setSilentRecreate(it) } },
            )
        }

        TrustedWifiSettingsCard(settings = settings)

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Администратор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (admin) {
                    "Открыты Серверы / Деплой / Логи. Включите «Тестирование» для вкладки телеметрии."
                } else {
                    "Короткое нажатие — подсказка. Удерживайте кнопку 4 секунды."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!admin) {
                AdminHoldButton(
                    hint = adminHint,
                    onHint = { adminHint = it },
                    onUnlocked = {
                        scope.launch {
                            settings.unlockAdmin()
                            adminHint = "Режим админа включён"
                            AppLog.i("Admin", "Unlocked via 4s hold")
                        }
                    },
                )
            } else {
                RowSetting(
                    title = "Тестирование",
                    subtitle = "Вкладка с полной телеметрией и записью логов",
                    checked = testingMode,
                    onCheckedChange = { scope.launch { settings.setTestingMode(it) } },
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settings.lockAdmin()
                            adminHint = "Снова режим пользователя"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Выйти из режима админа")
                }
            }
            adminHint?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
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
                !hasTrustedWifiBackgroundPermission(context)
            ) {
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                hint = "Локация разрешена — можно добавить текущую сеть"
            }
        } else {
            hint = "Нужна локация, чтобы читать имя Wi‑Fi"
        }
    }

    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Доверенная Wi‑Fi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "В этих сетях VPN сам выключается. При выходе, отключении опции или удалении сети — поднимается снова. Добавляется только текущая Wi‑Fi (списка всех сетей нет).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowSetting(
            title = "Включить",
            subtitle = when (val p = trustedWifiAccessProblem(context, requireBackground = false)) {
                TrustedWifiAccessProblem.ForegroundPermission -> "Нужно разрешение локации"
                TrustedWifiAccessProblem.LocationDisabled -> "Включите геолокацию в системе"
                TrustedWifiAccessProblem.BackgroundPermission -> "Нужна фоновая локация"
                null -> when {
                    !hasTrustedWifiBackgroundPermission(context) ->
                        "Для авто-паузы в фоне выдайте «Локация → Всегда»"
                    ssids.isEmpty() -> "Добавьте хотя бы одну сеть"
                    else -> "${ssids.size} сетей"
                }
            },
            checked = enabled,
            onCheckedChange = { on ->
                if (on && !hasTrustedWifiForegroundPermission(context)) {
                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
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
                    TrustedWifiAccessProblem.ForegroundPermission -> "Wi‑Fi есть, но нет разрешения локации"
                    TrustedWifiAccessProblem.LocationDisabled -> "Wi‑Fi есть, но геолокация выключена"
                    else -> "Wi‑Fi есть, имя сети недоступно — выдайте локацию или включите геолокацию"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = {
                if (!hasTrustedWifiForegroundPermission(context)) {
                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    return@OutlinedButton
                }
                val fresh = readConnectedWifiState(context, requireBackground = false)
                wifi = fresh
                val ssid = fresh.ssid
                if (ssid.isBlank()) {
                    hint = when (fresh.accessProblem) {
                        TrustedWifiAccessProblem.LocationDisabled -> "Включите геолокацию"
                        TrustedWifiAccessProblem.ForegroundPermission -> "Выдайте локацию"
                        else -> "Имя сети не прочиталось — подключитесь к Wi‑Fi и выдайте локацию"
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
                ) { Text("Убрать") }
            }
        }
        hint?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AdminHoldButton(
    hint: String?,
    onHint: (String) -> Unit,
    onUnlocked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val startedAt = System.currentTimeMillis()
                    holdJob?.cancel()
                    holdJob = scope.launch {
                        for (left in 4 downTo 1) {
                            onHint("Удерживайте… ещё $left с")
                            delay(1_000)
                        }
                        onUnlocked()
                    }
                    // Only release cancels the hold — sliding off the button must not.
                    waitForPointerUpIgnoringBounds()
                    val heldMs = System.currentTimeMillis() - startedAt
                    val finished = holdJob?.isCompleted == true
                    holdJob?.cancel()
                    holdJob = null
                    if (!finished) {
                        onHint(
                            if (heldMs < 350) {
                                "Удерживайте кнопку 4 секунды для режима админа"
                            } else {
                                "Отпущено рано — держите полные 4 секунды"
                            },
                        )
                    }
                }
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = hint?.takeIf { it.startsWith("Удерживайте") } ?: "Удерживать 4 сек — админ",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Like [androidx.compose.foundation.gestures.waitForUpOrCancellation], but does
 * **not** cancel when the finger slides outside the hit bounds. Hold continues
 * until the pointer is actually released.
 */
private suspend fun AwaitPointerEventScope.waitForPointerUpIgnoringBounds() {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.all { it.changedToUp() }) {
            event.changes.forEach { it.consume() }
            return
        }
        if (event.changes.none { it.pressed }) {
            return
        }
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
    val isDark = NvpnFloatingShell.isDarkTheme()
    val defaultSelectedBg = if (isDark) {
        colors.primary.copy(alpha = 0.22f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.94f)
    }
    val defaultSelectedContent = colors.primary

    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = defaultSelectedBg,
                contentColor = defaultSelectedContent,
            ),
            border = BorderStroke(
                1.dp,
                if (isDark) colors.primary.copy(alpha = 0.35f) else colors.primary.copy(alpha = 0.25f),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
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
            contentPadding = PaddingValues(horizontal = 16.dp),
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
