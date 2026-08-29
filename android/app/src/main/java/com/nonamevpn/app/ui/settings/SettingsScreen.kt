package com.nonamevpn.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.nonamevpn.app.update.AppUpdateController
import com.nonamevpn.app.update.AppUpdateInfo
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
    val connUi by conn.ui.collectAsStateWithLifecycle()
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
                title = "Скрыть адрес",
                subtitle = if (hideIp) {
                    "Выход через Cloudflare WARP."
                } else {
                    "Выход с адреса сервера."
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
                title = "Уведомление VPN",
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

        CallHashSettingsCard()

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
    downloading: Boolean,
    progress: Float,
    message: String?,
    downloadedFile: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    AppSectionCard(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Обновление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (info != null) {
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
                        "Доступна ${info.versionName}",
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
        if (downloadedFile) {
            OutlinedButton(
                onClick = onInstall,
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Установить")
            }
        } else if (info?.isNewer == true) {
            OutlinedButton(
                onClick = onDownload,
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(if (downloading) "Загрузка…" else "Скачать")
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
            "В этих сетях VPN приостанавливается. При выходе подключение восстанавливается. Добавляется только текущая сеть.",
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
