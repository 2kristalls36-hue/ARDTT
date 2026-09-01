package com.nonamevpn.app.ui.tunnel

import android.os.Build
import android.telephony.SubscriptionManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.isSystemInDarkTheme
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.R
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.core.NetcheckClient
import com.nonamevpn.app.core.NetcheckItem
import com.nonamevpn.app.core.NetcheckReport
import com.nonamevpn.app.core.NetcheckTone
import com.nonamevpn.app.core.NetcheckUiRow
import com.nonamevpn.app.core.NetworkClass
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.core.readUnderlayAccessLabel
import com.nonamevpn.app.core.underlayIdentity
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.ProfileCatalog
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.HideIpCopy
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.ui.connectionControlsLocked
import com.nonamevpn.app.ui.tunnelConnectionParamsVisible
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.rememberSmartHaptics
import com.nonamevpn.app.ui.components.WarpIcon
import com.nonamevpn.app.ui.settings.SettingsSheet
import com.nonamevpn.app.ui.theme.NvpnColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun TunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    onRequestConnect: () -> Unit,
    onNavigateToDialSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val catalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showHash by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var callBusy by remember { mutableStateOf(false) }
    var callMessage by remember { mutableStateOf<String?>(null) }
    var vkLoggedIn by remember { mutableStateOf(VkSession.hasSessionCookie()) }

    suspend fun refreshUnderlayStats(forceProviderIp: Boolean) {
        accessLabel = readUnderlayAccessLabel(context)
        val id = underlayIdentity(context)
        if (!forceProviderIp && id == lastUnderlayId) {
            providerIp = EgressIpProbe.currentUnderlay() ?: providerIp
            providerIpError = EgressIpProbe.lastUnderlayError
            return
        }
        if (id != lastUnderlayId) {
            AppLog.v("Tunnel", "underlay identity $lastUnderlayId → $id")
            EgressIpProbe.invalidateUnderlay()
            providerIp = null
            providerIpError = null
        }
        lastUnderlayId = id
        val ip = runCatching { EgressIpProbe.refreshUnderlay(context) }.getOrNull()
        providerIp = ip ?: EgressIpProbe.currentUnderlay()
        providerIpError = EgressIpProbe.lastUnderlayError
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
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val wallpaperVariant by settings.tunnelWallpaperVariantFlow.collectAsStateWithLifecycle(initialValue = 0)
    val unlockConnControls by settings.unlockConnControlsFlow.collectAsStateWithLifecycle(initialValue = false)
    val hideTunnelQuickSettings by settings.hideTunnelQuickSettingsFlow.collectAsStateWithLifecycle(initialValue = false)
    val trustedWifiEnabled by settings.trustedWifiEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val haptics = rememberSmartHaptics(uiHapticsEnabled)
    var previousConnState by remember { mutableStateOf(ui.state) }
    var connStateInitialized by remember { mutableStateOf(false) }
    var previousHasCallHash by remember { mutableStateOf(ui.hasCallHash) }
    var callHashInitialized by remember { mutableStateOf(false) }
    val showConnectionParams = tunnelConnectionParamsVisible(hideTunnelQuickSettings)
    var showConnectionHint by rememberSaveable { mutableStateOf(true) }
    val openUpdateDownload by PendingUiAction.openUpdateDownload.collectAsStateWithLifecycle()
    LaunchedEffect(profile?.deviceId, hideIp) {
        if (profile == null) return@LaunchedEffect
        conn.setHideIp(hideIp)
    }
    LaunchedEffect(openUpdateDownload) {
        if (!openUpdateDownload) return@LaunchedEffect
        showSettings = true
        PendingUiAction.consumeOpenUpdateDownload()
    }
    LaunchedEffect(ui.state) {
        if (connStateInitialized &&
            previousConnState != ConnState.Connected &&
            ui.state == ConnState.Connected
        ) {
            haptics.success()
        }
        previousConnState = ui.state
        connStateInitialized = true
    }
    LaunchedEffect(ui.hasCallHash) {
        if (callHashInitialized && !previousHasCallHash && ui.hasCallHash) {
            haptics.success()
        }
        previousHasCallHash = ui.hasCallHash
        callHashInitialized = true
    }

    val connecting = ui.state == ConnState.Connecting
    val pausedTrusted = ui.state == ConnState.PausedTrustedWifi
    val connected = ui.state == ConnState.Connected
    val sessionUp = connected || pausedTrusted
    val probing = ui.state == ConnState.Probing
    val disconnecting = ui.state == ConnState.Disconnecting
    val busy = probing || connecting || disconnecting
    val vpnLocked = connectionControlsLocked(
        sessionActive = connecting || connected || pausedTrusted || disconnecting,
        unlockWhileConnected = unlockConnControls,
    )
    var netcheck by remember { mutableStateOf<NetcheckReport?>(null) }
    /** Service probes only while the tunnel is up — not on pause / idle. */
    val netcheckActive = connected

    suspend fun refreshNetcheck(force: Boolean) {
        if (!netcheckActive) {
            netcheck = null
            return
        }
        val report = NetcheckClient.fetch(
            context = context,
            provisionBaseUrl = profile?.provisionBaseUrl,
            deviceId = profile?.deviceId,
            hideIp = hideIp,
            refresh = force,
        )
        netcheck = report ?: NetcheckReport(
            ok = false,
            viaWarp = hideIp,
            cached = false,
            items = NetcheckClient.slots.map { (id, label) ->
                NetcheckItem(id, label, "error", "не удалось проверить")
            },
        )
    }

    LaunchedEffect(ui.state, hideIp) {
        val watchEgress =
            ui.state == ConnState.Connecting ||
                ui.state == ConnState.Connected ||
                ui.state == ConnState.PausedTrustedWifi
        if (!watchEgress) {
            publicIp = EgressIpProbe.current()
            return@LaunchedEffect
        }
        while (true) {
            publicIp = EgressIpProbe.current()
            delay(500)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshUnderlayStats(forceProviderIp = false)
            delay(1_500)
        }
    }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                scope.launch { refreshUnderlayStats(forceProviderIp = true) }
            }
        }
        if (sm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sm.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                sm.addOnSubscriptionsChangedListener(listener)
            }
        }
        onDispose {
            runCatching { sm?.removeOnSubscriptionsChangedListener(listener) }
        }
    }

    LaunchedEffect(sessionUp, ui.probe?.networkClass, ui.probe?.elapsedMs) {
        refreshUnderlayStats(forceProviderIp = true)
    }

    LaunchedEffect(netcheckActive, hideIp, profile?.provisionBaseUrl, profile?.deviceId) {
        refreshNetcheck(force = false)
    }

    val buttonColor by animateColorAsState(
        targetValue = when {
            sessionUp -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "btn_color",
    )

    val pull = rememberPullRefresh {
        refreshUnderlayStats(forceProviderIp = true)
        val ip = runCatching {
            EgressIpProbe.refresh(
                hideIp = hideIp,
                provisionBaseUrl = profile?.provisionBaseUrl,
                deviceId = profile?.deviceId,
                context = context,
                viaVpn = sessionUp,
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Настройки",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        refreshNetcheck(force = true)
    }

    val autoBypassDetected = pathMode == "auto" && (
        ui.probe?.networkClass == NetworkClass.NeedBypass ||
            ui.probe?.networkClass == NetworkClass.OpenNeedBypass
        )
    val whitelistDetected = autoBypassDetected || pathMode == "bypass"
    val isDarkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    if (!admin) {
        UserTunnelSimpleScreen(
            ui = ui,
            catalogItems = catalog.items,
            activeProfileId = catalog.activeId,
            whitelistDetected = whitelistDetected,
            isDarkTheme = isDarkTheme,
            wallpaperVariant = wallpaperVariant,
            themeMode = themeMode,
            onSwitchThemeMode = {
                scope.launch {
                    val nextThemeMode = when (themeMode) {
                        "system" -> "light"
                        "light" -> "dark"
                        else -> "system"
                    }
                    settings.setThemeMode(nextThemeMode)
                }
            },
            onToggleTunnel = {
                haptics.tick()
                when (ui.state) {
                    ConnState.Connected,
                    ConnState.Connecting,
                    ConnState.Probing,
                    ConnState.Disconnecting,
                    ConnState.PausedTrustedWifi -> conn.disconnect()
                    else -> onRequestConnect()
                }
            },
            onSelectPreviousProfile = {
                val items = catalog.items
                if (items.size <= 1) return@UserTunnelSimpleScreen
                haptics.tick()
                val currentIndex = items.indexOfFirst { it.id == catalog.activeId }.let { if (it < 0) 0 else it }
                val target = items[(currentIndex - 1 + items.size) % items.size]
                scope.launch {
                    profiles.setActive(target.id)
                    settings.setProfileName(target.profile.name)
                    conn.updateProfile(target.profile)
                }
            },
            onSelectNextProfile = {
                val items = catalog.items
                if (items.size <= 1) return@UserTunnelSimpleScreen
                haptics.tick()
                val currentIndex = items.indexOfFirst { it.id == catalog.activeId }.let { if (it < 0) 0 else it }
                val target = items[(currentIndex + 1) % items.size]
                scope.launch {
                    profiles.setActive(target.id)
                    settings.setProfileName(target.profile.name)
                    conn.updateProfile(target.profile)
                }
            },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullRefreshHost(
            refreshing = pull.refreshing,
            onRefresh = pull.onRefresh,
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.scrollContentPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppTabPageHeader(
                title = "Подключение",
            )
            val missingCallHashHint = !ui.hasCallHash
            if (missingCallHashHint || showConnectionHint) {
                TunnelConnectionHintBanner(
                    missingCallHashHint = missingCallHashHint,
                    vpnLocked = vpnLocked,
                    sessionSwitchingEnabled = (connected || connecting || pausedTrusted) && unlockConnControls,
                    quickSettingsHidden = hideTunnelQuickSettings,
                    onDismiss = { showConnectionHint = false },
                )
            }

            if (!admin && profile != null) {
                val active = profile!!.subscriptionActive
                val expiresText = when {
                    profile!!.expiresAt <= 0L -> "срок не ограничен"
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

            Text(
                text = when {
                    profile == null -> "Профиль не выбран"
                    profile!!.name.isBlank() -> "Профиль выбран"
                    else -> "Профиль: ${profile!!.name}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            if (showConnectionParams) {
                AppSectionCard(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(
                    "Параметры подключения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                QuickSettingRow(
                    title = "Маршрут",
                    subtitle = when {
                        pathMode == "direct" -> "Только прямое подключение."
                        pathMode == "bypass" && ui.hasCallHash ->
                            "Только обход. Код звонка — в настройках."
                        pathMode == "bypass" ->
                            "Код звонка не задан. Для перехода к настройке нажмите «Обход»."
                        else -> "Сначала прямое, при недоступности — обход."
                    },
                    compact = true,
                ) {
                    ChoiceChipButton(
                        label = "Авто",
                        selected = pathMode == "auto",
                        enabled = !vpnLocked,
                        onClick = {
                            haptics.tick()
                            scope.launch {
                                settings.setPathMode("auto")
                                conn.setPathMode(ConnPathMode.Auto, switchLive = true)
                                AppLog.i("PathMode", "auto")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 40.dp,
                    )
                    ChoiceChipButton(
                        label = "Прямое",
                        selected = pathMode == "direct",
                        enabled = !vpnLocked,
                        selectedContainer = NvpnColors.pathDirect,
                        onClick = {
                            haptics.tick()
                            scope.launch {
                                settings.setPathMode("direct")
                                conn.setPathMode(ConnPathMode.Direct, switchLive = true)
                                AppLog.i("PathMode", "direct")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 40.dp,
                    )
                    ChoiceChipButton(
                        label = "Обход",
                        selected = pathMode == "bypass",
                        enabled = !vpnLocked,
                        dimmed = !ui.hasCallHash,
                        selectedContainer = NvpnColors.pathBypass,
                        onClick = {
                            haptics.tick()
                            if (!ui.hasCallHash) {
                                onNavigateToDialSettings()
                                return@ChoiceChipButton
                            }
                            scope.launch {
                                settings.setPathMode("bypass")
                                conn.setPathMode(ConnPathMode.Bypass, switchLive = true)
                                AppLog.i("PathMode", "bypass")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 40.dp,
                    )
                }

                QuickSettingRow(
                    title = "Исходящий адрес",
                    subtitle = HideIpCopy.subtitle(hideIp),
                    compact = true,
                ) {
                    ChoiceChipButton(
                        label = HideIpCopy.SERVER_CHIP,
                        selected = !hideIp,
                        enabled = !vpnLocked,
                        onClick = {
                            haptics.tick()
                            scope.launch {
                                settings.setHideIp(false)
                                conn.setHideIp(false)
                                AppLog.i("HideIP", "disabled (server address)")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 40.dp,
                    )
                    ChoiceChipButton(
                        label = HideIpCopy.HIDDEN_CHIP,
                        selected = hideIp,
                        enabled = !vpnLocked,
                        onClick = {
                            haptics.tick()
                            scope.launch {
                                settings.setHideIp(true)
                                conn.setHideIp(true)
                                AppLog.i("HideIP", "enabled (hidden address)")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 40.dp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Доверенная WiFi",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (trustedWifiEnabled) {
                                "В сохранённых сетях туннель приостанавливается. Список сетей доступен в разделе «Настройки»."
                            } else {
                                "Приостановка в Wi‑Fi отключена. Список сетей доступен в разделе «Настройки»."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = trustedWifiEnabled,
                        onCheckedChange = { on ->
                            haptics.tick()
                            scope.launch { settings.setTrustedWifiEnabled(on) }
                        },
                    )
                }
            }
            }

            // ═══ Статус сессии — структурированная панель ═══
            TunnelStatusPanel(
                statusText = sessionCardStatusText(ui.state, publicIp, ui.lastError),
                statusColor = when {
                    pausedTrusted -> NvpnColors.warning
                    connected -> NvpnColors.connected
                    ui.state == ConnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                selectedModeLabel = selectedModeLabel(pathMode),
                currentModeLabel = currentModeLabel(ui.state, ui.activePath),
                currentModeColor = when (ui.activePath) {
                    VpnPath.Direct -> NvpnColors.pathDirect
                    VpnPath.Bypass -> NvpnColors.pathBypass
                    null -> null
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.probe?.let { p ->
                Text(
                    detailLine(
                        p.networkClass,
                        p.yandexOk,
                        p.bigtechOk,
                        p.vpsUdpOk,
                        p.provisionOk,
                        p.elapsedMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            ui.softInfo?.let { info ->
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            ui.lastError?.takeIf { ui.state == ConnState.Error }?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { conn.startInitialProbe() },
                    enabled = !busy && !sessionUp,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Сеть", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        haptics.tick()
                        if (sessionUp) conn.disconnect() else onRequestConnect()
                    },
                    enabled = !busy && (sessionUp || ui.connectEnabled),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            sessionUp && pausedTrusted -> "Остановить (пауза Wi‑Fi)"
                            sessionUp -> "Остановить"
                            connecting -> "Подключение…"
                            ui.state == ConnState.Probing -> "Проверка…"
                            else -> "Подключить"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Звонок (обход)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (ui.hasCallHash) "Код звонка сохранён на этом устройстве" else "Код звонка не задан — он требуется для режима «Обход»",
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.hasCallHash) {
                    NvpnColors.connected
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            callMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (!vkLoggedIn) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            callBusy = true
                            callMessage = "Открываем вход VK…"
                            AppLog.i("VK", "Login button pressed")
                            val activityCtx = context.findActivity() ?: context
                            val r = runCatching { VkLoginActivity.login(activityCtx) }
                                .getOrElse { Result.failure(it) }
                            callBusy = false
                            vkLoggedIn = VkSession.hasSessionCookie()
                            callMessage = when {
                                r.isSuccess && vkLoggedIn -> "Авторизация выполнена. Теперь можно создать код звонка."
                                r.isSuccess -> "Сессия не подтверждена. Повторите попытку."
                                else -> r.exceptionOrNull()?.message ?: "Вход отменён"
                            }
                            AppLog.i("VK", "Login result success=${r.isSuccess} cookie=$vkLoggedIn")
                        }
                    },
                    // Allow login even while connected (session is for hash recreate).
                    enabled = profile != null && !callBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Войти в VK…", fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            callBusy = true
                            callMessage = "Создаём звонок…"
                            val r = VkCallHashGenerator.generateOne(context)
                            callBusy = false
                            r.onSuccess { hash ->
                                conn.saveCallHash(hash)
                                callMessage = "Код звонка создан и сохранён."
                            }.onFailure { e ->
                                callMessage = e.message ?: "Не удалось создать звонок"
                                vkLoggedIn = VkSession.hasSessionCookie()
                            }
                        }
                    },
                    enabled = profile != null && !sessionUp && !callBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        if (ui.hasCallHash) "Создать новый звонок" else "Создать звонок",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = {
                        VkSession.clear()
                        vkLoggedIn = false
                        callMessage = "Сессия ВКонтакте завершена."
                    },
                    enabled = !sessionUp && !callBusy,
                ) {
                    Text("Выйти из VK")
                }
            }
            OutlinedButton(
                onClick = { showHash = true },
                enabled = profile != null && !sessionUp && !callBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    if (ui.hasCallHash) "Вставить hash вручную…" else "Сохранить hash вручную…",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WarpIcon()
                RowSwitch(
                    title = "Скрытие IP-адреса",
                    subtitle = if (hideIp) {
                        "Включено: исходящий трафик направляется через Cloudflare WARP (вместо IP-адреса VPS)."
                    } else {
                        "Отключено: используется исходящий IP-адрес сервера."
                    },
                    checked = hideIp,
                    enabled = !connecting && ui.state != ConnState.Disconnecting,
                    onCheckedChange = { on ->
                        scope.launch {
                            settings.setHideIp(on)
                            conn.setHideIp(on)
                            AppLog.i("HideIP", if (on) "enabled" else "disabled")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onDismiss = { showSettings = false },
        )
    }

    val bgRes = resolveUserTunnelWallpaper(
        variant = wallpaperVariant,
        isDark = isDarkTheme,
        whitelistDetected = showingWhitelistScene,
    )
    val connectingLike = ui.state == ConnState.Connecting || ui.state == ConnState.Probing
    val connected = ui.state == ConnState.Connected
    val disconnecting = ui.state == ConnState.Disconnecting
    val activeItem = catalogItems.find { it.id == activeProfileId } ?: catalogItems.firstOrNull()

    val modeBadge = when (themeMode) {
        "light" -> "light"
        "dark" -> "dark"
        else -> "auto"
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = bgRes,
            animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            label = "tunnel_wallpaper_crossfade",
        ) { resId ->
            Image(
                painter = painterResource(resId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (showingWhitelistScene) {
            WhitelistSkyAnimation(
                restartToken = animationRestartToken,
                blowAway = dronesBlowAway,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.37f)
                    .offset(y = 18.dp)
                    .align(Alignment.TopCenter),
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp)
                .size(38.dp)
                .combinedClickable(
                    onClick = onSwitchThemeMode,
                ),
            shape = RoundedCornerShape(19.dp),
            color = NvpnFloatingShell.shellColor(),
            border = NvpnFloatingShell.shellBorder(),
            shadowElevation = NvpnFloatingShell.shadowElevation,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (modeBadge) {
                    "light" -> Icon(
                        imageVector = Icons.Outlined.WbSunny,
                        contentDescription = "Светлая тема",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    "dark" -> Icon(
                        imageVector = Icons.Outlined.DarkMode,
                        contentDescription = "Тёмная тема",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    else -> Text(
                        "A",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))

            TunnelPowerToggle(
                connected = connected,
                paused = ui.state == ConnState.PausedTrustedWifi,
                busy = connectingLike || disconnecting,
                showBusyGlow = ui.state != ConnState.Probing,
                onClick = onToggleTunnel,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(198.dp),
            )

            Spacer(modifier = Modifier.height(26.dp))

            ProfileSwitcherBar(
                activeItem = activeItem,
                canSwitch = catalogItems.size > 1,
                onPrev = onSelectPreviousProfile,
                onNext = onSelectNextProfile,
                busy = connectingLike,
            )
        }
    }
}

@Composable
private fun TunnelPowerToggle(
    connected: Boolean,
    paused: Boolean,
    busy: Boolean,
    showBusyGlow: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeGlow = connected || paused || (busy && showBusyGlow)
    val shellColor = NvpnFloatingShell.shellColor()
    val accentColor = if (connected || paused || (busy && showBusyGlow)) {
        Color(0xFF35C759)
    } else {
        Color.White.copy(alpha = 0.75f)
    }
    val pulseScale by animateFloatAsState(
        targetValue = if (activeGlow) 1.08f else 1f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "awg_pulse_scale",
    )
    val pulseAlpha by animateFloatAsState(
        targetValue = if (activeGlow) 0.28f else 0.12f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "awg_pulse_alpha",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(198.dp * pulseScale)
                .background(
                    color = accentColor.copy(alpha = pulseAlpha),
                    shape = CircleShape,
                ),
        )
        Surface(
            modifier = Modifier
                .size(180.dp)
                .clickable(enabled = !busy) {
                    runCatching { onClick() }
                        .onFailure { t -> AppLog.e("TunnelToggle", "toggle failed: ${t.message}") }
                },
            color = shellColor,
            border = NvpnFloatingShell.shellBorder(),
            shape = CircleShape,
            shadowElevation = NvpnFloatingShell.shadowElevation,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (paused) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(62.dp)
                                .background(accentColor, shape = RoundedCornerShape(10.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(62.dp)
                                .background(accentColor, shape = RoundedCornerShape(10.dp)),
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = if (connected) "Отключить туннель" else "Подключить туннель",
                        tint = accentColor,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
        }
    }
}

private data class FlightAssetSpec(
    val resId: Int,
    val sizeDp: Int,
    val startXFrac: Float,
    val startYFrac: Float,
    val anchorXFrac: Float,
    val anchorYFrac: Float,
    val orbitRadiusXFrac: Float,
    val orbitRadiusYFrac: Float,
    val orbitDurationMs: Int,
    val delayMs: Long,
    val phaseRad: Float,
    val windStrength: Float,
    val gustFreqMul: Float,
    val gustPhase: Float,
    val compensationStrength: Float,
    val dragLimitXFrac: Float,
    val dragLimitYFrac: Float,
    /** Sprite alpha-center offset from geometric center in normalized square size units. */
    val centerBiasX: Float = 0f,
    val centerBiasY: Float = 0f,
)

@Composable
private fun WhitelistSkyAnimation(
    restartToken: Int,
    blowAway: Boolean,
    modifier: Modifier = Modifier,
) {
    val assets = remember {
        listOf(
            FlightAssetSpec(
                resId = R.drawable.tunnel_drone_far,
                sizeDp = 74,
                startXFrac = 1.26f,
                startYFrac = 0.24f,
                anchorXFrac = 0.72f,
                anchorYFrac = 0.20f,
                orbitRadiusXFrac = 0.018f,
                orbitRadiusYFrac = 0.014f,
                orbitDurationMs = 11_200,
                delayMs = 140L,
                phaseRad = 2.2f,
                windStrength = 0.86f,
                gustFreqMul = 1.43f,
                gustPhase = 2.05f,
                compensationStrength = 0.34f,
                dragLimitXFrac = 0.065f,
                dragLimitYFrac = 0.05f,
                centerBiasX = 0.000f,
                centerBiasY = -0.008f,
            ),
            FlightAssetSpec(
                resId = R.drawable.tunnel_drone_near,
                sizeDp = 228,
                startXFrac = 0.40f,
                startYFrac = -0.66f,
                anchorXFrac = 0.37f,
                anchorYFrac = 0.24f,
                orbitRadiusXFrac = 0.027f,
                orbitRadiusYFrac = 0.021f,
                orbitDurationMs = 9_200,
                delayMs = 80L,
                phaseRad = 0.4f,
                windStrength = 1.26f,
                gustFreqMul = 0.92f,
                gustPhase = 0.25f,
                compensationStrength = 0.18f,
                dragLimitXFrac = 0.09f,
                dragLimitYFrac = 0.06f,
                centerBiasX = 0.009f,
                centerBiasY = 0.028f,
            ),
            FlightAssetSpec(
                resId = R.drawable.tunnel_drone_mid,
                sizeDp = 114,
                startXFrac = -0.42f,
                startYFrac = 0.20f,
                anchorXFrac = 0.13f,
                anchorYFrac = 0.18f,
                orbitRadiusXFrac = 0.023f,
                orbitRadiusYFrac = 0.017f,
                orbitDurationMs = 10_100,
                delayMs = 0L,
                phaseRad = 1.3f,
                windStrength = 1.02f,
                gustFreqMul = 1.18f,
                gustPhase = 1.1f,
                compensationStrength = 0.26f,
                dragLimitXFrac = 0.08f,
                dragLimitYFrac = 0.055f,
                centerBiasX = 0.023f,
                centerBiasY = -0.033f,
            ),
        )
    }
    BoxWithConstraints(modifier = modifier) {
        val sceneWidthPx = constraints.maxWidth.toFloat()
        val sceneHeightPx = constraints.maxHeight.toFloat()
        assets.forEachIndexed { index, spec ->
            AnimatedFlightAsset(
                spec = spec,
                index = index,
                restartToken = restartToken,
                blowAway = blowAway,
                sceneWidthPx = sceneWidthPx,
                sceneHeightPx = sceneHeightPx,
            )
        }
    }
}

@Composable
private fun AnimatedFlightAsset(
    spec: FlightAssetSpec,
    index: Int,
    restartToken: Int,
    blowAway: Boolean,
    sceneWidthPx: Float,
    sceneHeightPx: Float,
) {
    val density = LocalDensity.current
    var launchStarted by remember(spec.resId, restartToken) { mutableStateOf(false) }
    var dragging by remember(spec.resId, restartToken) { mutableStateOf(false) }
    var rawDragDx by remember(spec.resId, restartToken) { mutableStateOf(0f) }
    var rawDragDy by remember(spec.resId, restartToken) { mutableStateOf(0f) }
    LaunchedEffect(spec.resId, restartToken) {
        delay(spec.delayMs)
        launchStarted = true
    }
    val arrivalProgress by animateFloatAsState(
        targetValue = if (launchStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 4_200, easing = LinearOutSlowInEasing),
        label = "flight_arrival_$index",
    )
    val orbitBlend by animateFloatAsState(
        targetValue = if (arrivalProgress > 0.985f) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "flight_orbit_blend_$index",
    )
    val blowAwayProgress by animateFloatAsState(
        targetValue = if (blowAway) 1f else 0f,
        animationSpec = tween(durationMillis = 980, easing = FastOutLinearInEasing),
        label = "flight_blow_away_$index",
    )
    val dragDx by animateFloatAsState(
        targetValue = if (dragging) rawDragDx else 0f,
        animationSpec = if (dragging) {
            tween(durationMillis = 45, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "flight_drag_dx_$index",
    )
    val dragDy by animateFloatAsState(
        targetValue = if (dragging) rawDragDy else 0f,
        animationSpec = if (dragging) {
            tween(durationMillis = 45, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "flight_drag_dy_$index",
    )
    val orbit by produceState(
        initialValue = 0f,
        key1 = spec.orbitDurationMs,
        key2 = restartToken,
    ) {
        val periodNs = spec.orbitDurationMs.toLong() * 1_000_000L
        val startNs = withFrameNanos { it }
        while (true) {
            val nowNs = withFrameNanos { it }
            val elapsedNs = (nowNs - startNs).coerceAtLeast(0L)
            val phase = if (periodNs <= 0L) 0f else {
                ((elapsedNs % periodNs).toDouble() / periodNs.toDouble()).toFloat()
            }
            value = ((Math.PI * 2.0) * phase).toFloat()
        }
    }

    val xFrac = spec.startXFrac + (spec.anchorXFrac - spec.startXFrac) * arrivalProgress
    val yFrac = spec.startYFrac + (spec.anchorYFrac - spec.startYFrac) * arrivalProgress
    val base = orbit + spec.phaseRad
    val windCarrier = sin((base * spec.gustFreqMul + spec.gustPhase).toDouble()).toFloat()
    val xPrimary = sin(base.toDouble()).toFloat()
    val xCompensation = sin((base * 2f + 0.9f).toDouble()).toFloat()
    val xMicro = sin((base * 3f + 1.6f).toDouble()).toFloat()
    val yPrimary = sin((base + 1.2f).toDouble()).toFloat()
    val yCompensation = sin((base * 2f + 0.35f).toDouble()).toFloat()
    val yMicro = sin((base * 3f + 2.1f).toDouble()).toFloat()
    val windAmp = ((0.78f + 0.22f * windCarrier) * spec.windStrength).coerceAtLeast(0.05f) * orbitBlend
    val comp = spec.compensationStrength.coerceIn(0.05f, 0.45f)
    val micro = (0.10f + comp * 0.35f).coerceAtMost(0.22f)
    val primary = (1f - comp - micro).coerceAtLeast(0.45f)
    val orbitX = (xPrimary * primary + xCompensation * comp + xMicro * micro) *
        (sceneWidthPx * spec.orbitRadiusXFrac) * windAmp
    val orbitY = (yPrimary * (primary - 0.06f).coerceAtLeast(0.38f) + yCompensation * (comp + 0.04f) + yMicro * micro) *
        (sceneHeightPx * spec.orbitRadiusYFrac) * windAmp
    val wobbleRotation = (
        sin((base + 0.2f).toDouble()).toFloat() * 0.9f +
            sin((base * 2f + 1.4f).toDouble()).toFloat() * 0.35f
        ) * orbitBlend
    val windKickX = -sceneWidthPx * (0.36f + 0.12f * spec.windStrength) * blowAwayProgress
    val windKickY = -sceneHeightPx * 0.10f * blowAwayProgress
    val alpha = ((0.22f + 0.78f * arrivalProgress) * (1f - blowAwayProgress * 0.98f)).coerceIn(0f, 1f)
    val blowRotation = -18f * blowAwayProgress
    val dragLimitX = sceneWidthPx * spec.dragLimitXFrac
    val dragLimitY = sceneHeightPx * spec.dragLimitYFrac
    val baseX = xFrac * sceneWidthPx + orbitX + windKickX + dragDx
    val baseY = yFrac * sceneHeightPx + orbitY + windKickY + dragDy
    val layoutX = baseX.roundToInt()
    val layoutY = baseY.roundToInt()
    val drawOffsetX = baseX - layoutX
    val drawOffsetY = baseY - layoutY

    val touchSizeDp = (spec.sizeDp * 1.35f).dp
    val imageSizePx = with(density) { spec.sizeDp.dp.toPx() }
    val spriteFixX = -spec.centerBiasX * imageSizePx
    val spriteFixY = -spec.centerBiasY * imageSizePx
    Box(
        modifier = Modifier
            .size(touchSizeDp)
            .offset { IntOffset(layoutX, layoutY) }
            .pointerInput(spec.resId, restartToken, blowAway, dragLimitX, dragLimitY) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        rawDragDx = 0f
                        rawDragDy = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        rawDragDx = 0f
                        rawDragDy = 0f
                    },
                ) { change, dragAmount ->
                    if (blowAway) return@detectDragGestures
                    change.consume()
                    rawDragDx = (rawDragDx + dragAmount.x).coerceIn(-dragLimitX, dragLimitX)
                    rawDragDy = (rawDragDy + dragAmount.y).coerceIn(-dragLimitY, dragLimitY)
                }
            }
            .graphicsLayer {
                translationX = drawOffsetX
                translationY = drawOffsetY
                this.alpha = alpha
                rotationZ = wobbleRotation + blowRotation
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    translationX = spriteFixX
                    translationY = spriteFixY
                }
                .drawBehind {
                    val flightBlend = arrivalProgress.coerceIn(0f, 1f)
                    val glowColor = Color(0xFF66D8FF).copy(alpha = 0.10f + 0.08f * flightBlend)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor, Color.Transparent),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension * 0.56f,
                        ),
                        radius = size.minDimension * 0.56f,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                },
        )
        Image(
            painter = painterResource(spec.resId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(spec.sizeDp.dp),
        )
    }
}

@Composable
private fun ProfileSwitcherBar(
    activeItem: StoredProfile?,
    canSwitch: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    busy: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NvpnBottomChrome.navigationReserve() + 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val commonButtonColors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
        )
        if (canSwitch) {
            Button(
                onClick = onPrev,
                enabled = !busy,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .height(NvpnBottomChrome.ButtonHeight)
                    .width(62.dp),
                colors = commonButtonColors,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущий профиль")
            }
        }
        Button(
            onClick = { if (canSwitch) onNext() },
            enabled = activeItem != null && !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .weight(1f)
                .height(NvpnBottomChrome.ButtonHeight),
            colors = commonButtonColors,
        ) {
            Text(
                activeItem?.profile?.name?.ifBlank { "Профиль" } ?: "Выбрать профиль",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canSwitch) {
            Button(
                onClick = onNext,
                enabled = !busy,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .height(NvpnBottomChrome.ButtonHeight)
                    .width(62.dp),
                colors = commonButtonColors,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующий профиль")
            }
        }
    }
}

@Composable
private fun TunnelConnectionHintBanner(
    missingCallHashHint: Boolean,
    vpnLocked: Boolean,
    sessionSwitchingEnabled: Boolean,
    quickSettingsHidden: Boolean,
    onDismiss: () -> Unit,
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp),
            )
            Text(
                "Информация о подключении",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (!missingCallHashHint) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Закрыть информационное сообщение",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            when {
                missingCallHashHint ->
                    "Необходимо добавить код звонка для режима «Обход»: «Настройки» → «Метод обхода» → «Создать код» " +
                        "или «Вставить hash». До сохранения кода это сообщение остаётся закреплённым."
                vpnLocked ->
                    "Во время активного соединения параметры заблокированы. " +
                        "Разблокировка доступна в «Настройках» → «Подключение»."
                sessionSwitchingEnabled ->
                    "Маршрут можно переключать без разрыва текущего соединения: «Прямое» и «Обход» применяются сразу."
                quickSettingsHidden ->
                    "Быстрые параметры скрыты. Для отображения откройте «Настройки» и отключите пункт «Скрыть быстрые настройки»."
                else ->
                    "Здесь вы управляете маршрутом, исходящим адресом и доверенной Wi‑Fi. " +
                        "Код звонка для обхода настраивается на вкладке «Настройки»."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickSettingRow(
    title: String,
    subtitle: String?,
    compact: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
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
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
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
    dimmed: Boolean = false,
    minWidth: Dp? = null,
    height: Dp = 44.dp,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = NvpnFloatingShell.isDarkTheme()
    val defaultSelectedBg = if (isDark) {
        colors.primary.copy(alpha = 0.22f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.94f)
    }
    val defaultSelectedContent = colors.primary
    val widthModifier = if (minWidth != null) {
        modifier.height(height).widthIn(min = minWidth)
    } else {
        modifier.height(height)
    }

    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = widthModifier,
            shape = RoundedCornerShape(16.dp),
            colors = if (selectedContainer != null) {
                ButtonDefaults.buttonColors(
                    containerColor = selectedContainer,
                    contentColor = Color.White,
                    disabledContainerColor = selectedContainer.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = defaultSelectedBg,
                    contentColor = defaultSelectedContent,
                )
            },
            border = if (selectedContainer == null) {
                BorderStroke(
                    1.dp,
                    if (isDark) colors.primary.copy(alpha = 0.35f) else colors.primary.copy(alpha = 0.25f),
                )
            } else {
                null
            },
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = if (dimmed) Color.White.copy(alpha = 0.7f) else Color.Unspecified,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = widthModifier,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                colors.outline.copy(alpha = if (dimmed) 0.22f else 0.45f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (dimmed) {
                    colors.onSurface.copy(alpha = 0.45f)
                } else {
                    colors.onSurface
                },
            ),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun TunnelStatusPanel(
    statusText: String,
    statusColor: Color,
    selectedModeLabel: String,
    currentModeLabel: String,
    currentModeColor: Color?,
    publicIp: String,
    ipPending: Boolean = false,
    ipFailed: Boolean = false,
    onIpClick: (() -> Unit)? = null,
    accessLabel: String,
    providerIp: String,
    providerIpPending: Boolean = false,
    providerIpFailed: Boolean = false,
    onProviderIpClick: (() -> Unit)? = null,
    showWarpIcon: Boolean = false,
    profileName: String?,
    version: String,
    directEndpoint: String?,
    bypassPeer: String?,
    provisionLine: String?,
    netcheckRows: List<NetcheckUiRow>,
    softInfo: String?,
    errorText: String?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusFactRow(
                label = "Статус",
                value = statusText,
                valueColor = statusColor,
            )
            StatusFactRow(label = "Выбран режим", value = selectedModeLabel)
        }

        HorizontalDivider(color = dividerColor)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusFactRow(
                label = "Текущий режим",
                value = currentModeLabel,
                valueColor = if (currentModeLabel == "—") muted else currentModeColor,
            )
            StatusFactRow(label = "Оператор", value = accessLabel)
            StatusFactRow(
                label = "IP провайдера",
                value = providerIp,
                pending = providerIpPending,
                valueColor = if (providerIpFailed) MaterialTheme.colorScheme.error else null,
                onClick = onProviderIpClick,
            )
            StatusFactRow(
                label = "IP туннеля",
                value = publicIp,
                pending = ipPending,
                valueColor = if (ipFailed) MaterialTheme.colorScheme.error else null,
                onClick = onIpClick,
                valueLeadingIcon = if (showWarpIcon) R.drawable.ic_cloudflare else null,
                valueLeadingContentDescription = if (showWarpIcon) HideIpCopy.STATUS_HIDDEN else null,
            )
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
                    "Узлы",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    fontWeight = FontWeight.Medium,
                )
                directEndpoint?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Прямое", value = it)
                }
                bypassPeer?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Обход", value = it)
                }
                provisionLine?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Управление", value = it)
                }
            }
        }

        HorizontalDivider(color = dividerColor)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Проверка сети",
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                fontWeight = FontWeight.Medium,
            )
            netcheckRows.forEach { row ->
                StatusFactRow(
                    label = row.label,
                    value = row.value,
                    pending = row.pending,
                    valueColor = when (row.tone) {
                        NetcheckTone.Ok -> NvpnColors.connected
                        NetcheckTone.Warn -> NvpnColors.warning
                        NetcheckTone.Error -> MaterialTheme.colorScheme.error
                        NetcheckTone.Neutral -> null
                    },
                )
                StatusFactRow(label = "Сеть", value = p.networkClass.name)
                StatusFactRow(
                    label = "77.88.8.8",
                    value = if (p.yandexOk) "ok" else "—",
                )
                StatusFactRow(
                    label = "1.1.1.1",
                    value = if (p.bigtechOk) "ok" else "—",
                )
                StatusFactRow(label = "VPS", value = if (p.provisionOk) "ok" else "—")
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
    }
}

@Composable
private fun StatusFactRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    valueLeadingIcon: Int? = null,
    valueLeadingContentDescription: String? = null,
    pending: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(148.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                if (valueLeadingIcon != null) {
                    Image(
                        painter = painterResource(valueLeadingIcon),
                        contentDescription = valueLeadingContentDescription,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp),
                    )
                }
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
