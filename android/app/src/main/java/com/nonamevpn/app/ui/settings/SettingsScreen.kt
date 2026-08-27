package com.nonamevpn.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
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
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.hasTrustedWifiBackgroundPermission
import com.nonamevpn.app.core.hasTrustedWifiForegroundPermission
import com.nonamevpn.app.core.readConnectedWifiState
import com.nonamevpn.app.core.trustedWifiAccessProblem
import com.nonamevpn.app.core.TrustedWifiAccessProblem
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettingsRepository,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val economy by settings.economyWorkersEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val notifVisible by settings.vpnNotificationVisibleFlow.collectAsStateWithLifecycle(initialValue = true)
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val themePalette by settings.themePaletteFlow.collectAsStateWithLifecycle(initialValue = "espresso")
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var adminHint by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.i("NotifPrep", "settings POST_NOTIFICATIONS granted=$granted")
        conn.refreshVpnNotification()
        if (!granted) {
            adminHint = "Без разрешения Android плашка в шторке не появится"
        }
    }

    LaunchedEffect(silent, economy, dial, pathMode) {
        conn.setSilentRecreate(silent)
        conn.setWorkers(if (economy) 1 else 3)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
        conn.setPathMode(ConnPathMode.fromSetting(pathMode))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Режим: ${if (admin) "администратор" else "пользователь"} · ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                DialChip("Сист.", themeMode == "system", { scope.launch { settings.setThemeMode("system") } }, Modifier.weight(1f))
                DialChip("Свет.", themeMode == "light", { scope.launch { settings.setThemeMode("light") } }, Modifier.weight(1f))
                DialChip("Темн.", themeMode == "dark", { scope.launch { settings.setThemeMode("dark") } }, Modifier.weight(1f))
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
                "Авто — прямое (AmneziaWG), при недоступности резерв обход (RAW через TURN). " +
                    "Можно принудительно выбрать один путь для теста.",
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
                )
                DialChip(
                    "Прямое",
                    pathMode == "direct",
                    { scope.launch { settings.setPathMode("direct") } },
                    Modifier.weight(1f),
                )
                DialChip(
                    "Обход",
                    pathMode == "bypass",
                    { scope.launch { settings.setPathMode("bypass") } },
                    Modifier.weight(1f),
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
                DialChip("Авто", dial == "auto", { scope.launch { settings.setDialPath("auto") } }, Modifier.weight(1f))
                DialChip("vkcalls", dial == "vkcalls", { scope.launch { settings.setDialPath("vkcalls") } }, Modifier.weight(1f))
                DialChip("Капча", dial == "legacy", { scope.launch { settings.setDialPath("legacy") } }, Modifier.weight(1f))
            }
            RowSetting(
                title = "Тихий recreate звонка",
                subtitle = "Без диалога, если hash «умер» (нужна сессия VK)",
                checked = silent,
                onCheckedChange = { scope.launch { settings.setSilentRecreate(it) } },
            )
            RowSetting(
                title = "Экономия workers",
                subtitle = "1 вместо 3",
                checked = economy,
                onCheckedChange = { scope.launch { settings.setEconomyWorkers(it) } },
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
                    "Открыты Серверы / Деплой / Логи."
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
                    waitForUpOrCancellation()
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
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DialChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
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
