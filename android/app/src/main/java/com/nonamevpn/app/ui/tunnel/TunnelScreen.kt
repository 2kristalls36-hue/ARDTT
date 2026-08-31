package com.nonamevpn.app.ui.tunnel

import android.os.Build
import android.telephony.SubscriptionManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.core.readUnderlayAccessLabel
import com.nonamevpn.app.core.underlayIdentity
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.ProfileCatalog
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.HideIpCopy
import com.nonamevpn.app.ui.connectionControlsLocked
import com.nonamevpn.app.ui.tunnelConnectionParamsVisible
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.PullRefreshHost
import com.nonamevpn.app.ui.components.StickyPrimaryButton
import com.nonamevpn.app.ui.components.rememberPullRefresh
import com.nonamevpn.app.ui.theme.NvpnColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun TunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    onRequestConnect: () -> Unit,
    onOpenCallHashSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val catalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val scope = rememberCoroutineScope()
    var publicIp by remember { mutableStateOf(EgressIpProbe.current()) }
    var providerIp by remember { mutableStateOf(EgressIpProbe.currentUnderlay()) }
    var providerIpError by remember { mutableStateOf(EgressIpProbe.lastUnderlayError) }
    var accessLabel by remember { mutableStateOf(readUnderlayAccessLabel(context)) }
    var lastUnderlayId by remember { mutableStateOf("") }

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
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val isWhitelist by settings.appsWhitelistModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val whitelistApps by settings.excludedAppsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val whitelistHosts by settings.excludedHostsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val unlockConnControls by settings.unlockConnControlsFlow.collectAsStateWithLifecycle(initialValue = false)
    val hideTunnelQuickSettings by settings.hideTunnelQuickSettingsFlow.collectAsStateWithLifecycle(initialValue = false)
    val trustedWifiEnabled by settings.trustedWifiEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val showConnectionParams = tunnelConnectionParamsVisible(hideTunnelQuickSettings)
    var showConnectionHint by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(profile?.deviceId, hideIp) {
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
        }.getOrNull()
        publicIp = ip ?: EgressIpProbe.current()
        val skipProbe = connecting || connected || pausedTrusted || disconnecting
        if (!skipProbe) {
            conn.startInitialProbe()
            withTimeoutOrNull(12_000) {
                conn.ui.first { it.state != ConnState.Probing }
            }
        }
        refreshNetcheck(force = true)
    }

    val whitelistDetected = isWhitelist && (whitelistApps.isNotEmpty() || whitelistHosts.isNotEmpty())
    if (!admin) {
        UserTunnelSimpleScreen(
            ui = ui,
            catalogItems = catalog.items,
            activeProfileId = catalog.activeId,
            whitelistDetected = whitelistDetected,
            onToggleTunnel = {
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
            if (showConnectionHint) {
                TunnelConnectionHintBanner(
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
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Параметры подключения",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                QuickSettingRow(
                    title = "Маршрут",
                    subtitle = when {
                        pathMode == "direct" -> "Только прямое подключение."
                        pathMode == "bypass" && ui.hasCallHash ->
                            "Только обход. Код звонка — в настройках."
                        pathMode == "bypass" ->
                            "Код звонка не задан. Нажмите «Обход», чтобы открыть карточку."
                        else -> "Сначала прямое, при недоступности — обход."
                    },
                ) {
                    ChoiceChipButton(
                        label = "Авто",
                        selected = pathMode == "auto",
                        enabled = !vpnLocked,
                        onClick = {
                            scope.launch {
                                settings.setPathMode("auto")
                                conn.setPathMode(ConnPathMode.Auto, switchLive = true)
                                AppLog.i("PathMode", "auto")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Прямое",
                        selected = pathMode == "direct",
                        enabled = !vpnLocked,
                        selectedContainer = NvpnColors.pathDirect,
                        onClick = {
                            scope.launch {
                                settings.setPathMode("direct")
                                conn.setPathMode(ConnPathMode.Direct, switchLive = true)
                                AppLog.i("PathMode", "direct")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Обход",
                        selected = pathMode == "bypass",
                        enabled = !vpnLocked,
                        dimmed = !ui.hasCallHash,
                        selectedContainer = NvpnColors.pathBypass,
                        onClick = {
                            if (!ui.hasCallHash) {
                                onOpenCallHashSettings()
                                return@ChoiceChipButton
                            }
                            scope.launch {
                                settings.setPathMode("bypass")
                                conn.setPathMode(ConnPathMode.Bypass, switchLive = true)
                                AppLog.i("PathMode", "bypass")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                QuickSettingRow(
                    title = "Исходящий адрес",
                    subtitle = HideIpCopy.subtitle(hideIp),
                ) {
                    ChoiceChipButton(
                        label = HideIpCopy.SERVER_CHIP,
                        selected = !hideIp,
                        enabled = !vpnLocked,
                        onClick = {
                            scope.launch {
                                settings.setHideIp(false)
                                conn.setHideIp(false)
                                AppLog.i("HideIP", "disabled (server address)")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = HideIpCopy.HIDDEN_CHIP,
                        selected = hideIp,
                        enabled = !vpnLocked,
                        onClick = {
                            scope.launch {
                                settings.setHideIp(true)
                                conn.setHideIp(true)
                                AppLog.i("HideIP", "enabled (hidden address)")
                            }
                        },
                        modifier = Modifier.weight(1f),
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
                            .padding(end = 12.dp),
                    ) {
                        Text(
                            "Доверенная Wi‑Fi",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (trustedWifiEnabled) {
                                "В сохранённых сетях туннель ставится на паузу. Список сетей — в «Настройках»."
                            } else {
                                "Пауза в Wi‑Fi выключена. Список сетей — в «Настройках»."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = trustedWifiEnabled,
                        onCheckedChange = { on ->
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
                publicIp = when {
                    pausedTrusted -> "—"
                    !publicIp.isNullOrBlank() -> publicIp!!
                    connecting || connected -> ""
                    else -> "—"
                },
                ipPending = (connecting || connected) && publicIp.isNullOrBlank(),
                ipFailed = false,
                onIpClick = if (connecting || connected) {
                    { conn.requestEgressIpRefresh() }
                } else {
                    null
                },
                accessLabel = accessLabel,
                providerIp = when {
                    !providerIp.isNullOrBlank() -> providerIp!!
                    !providerIpError.isNullOrBlank() -> "не удалось определить"
                    else -> ""
                },
                providerIpPending = providerIp.isNullOrBlank() && providerIpError.isNullOrBlank(),
                providerIpFailed = providerIp.isNullOrBlank() && !providerIpError.isNullOrBlank(),
                onProviderIpClick = {
                    scope.launch {
                        EgressIpProbe.invalidateUnderlay()
                        providerIp = null
                        providerIpError = null
                        val ip = runCatching { EgressIpProbe.refreshUnderlay(context) }.getOrNull()
                        providerIp = ip ?: EgressIpProbe.currentUnderlay()
                        providerIpError = EgressIpProbe.lastUnderlayError
                        lastUnderlayId = underlayIdentity(context)
                        accessLabel = readUnderlayAccessLabel(context)
                    }
                },
                showWarpIcon = !publicIp.isNullOrBlank() && (
                    (hideIp && sessionUp) || EgressIpProbe.isLikelyCloudflare(publicIp)
                    ),
                profileName = profile?.name?.takeIf { it.isNotBlank() },
                version = BuildConfig.VERSION_NAME,
                directEndpoint = profile?.direct?.endpoint,
                bypassPeer = profile?.bypass?.peer,
                provisionLine = profile?.let { p ->
                    p.provisionBaseUrl?.let { base -> "$base · host ${p.hostId}" }
                },
                netcheckRows = NetcheckClient.uiRows(netcheck, probeActive = netcheckActive),
                softInfo = ui.softInfo?.takeIf { it.isNotBlank() },
                errorText = ui.lastError?.takeIf { ui.state == ConnState.Error && it.isNotBlank() },
            )
        }
        }

        // Sticky «Подключить» / «Отменить» (same button) above tab bar
        val cancelMode = connecting || probing
        StickyPrimaryButton(
            text = when {
                cancelMode -> "Отменить"
                sessionUp -> "Отключить"
                else -> "Подключиться"
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
                pausedTrusted -> Icons.Default.Pause
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
}

@Composable
private fun UserTunnelSimpleScreen(
    ui: com.nonamevpn.app.core.ConnUiState,
    catalogItems: List<StoredProfile>,
    activeProfileId: String?,
    whitelistDetected: Boolean,
    onToggleTunnel: () -> Unit,
    onSelectPreviousProfile: () -> Unit,
    onSelectNextProfile: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val bgRes = when {
        whitelistDetected -> R.drawable.tunnel_user_whitelist
        isDark -> R.drawable.tunnel_user_night
        else -> R.drawable.tunnel_user_day
    }
    val connectingLike = ui.state == ConnState.Connecting || ui.state == ConnState.Probing
    val connected = ui.state == ConnState.Connected
    val trackColor = if (connected) Color(0xFF35C759) else Color(0xFF9AA0A8)
    val activeItem = catalogItems.find { it.id == activeProfileId } ?: catalogItems.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(bgRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTabPageHeader(
                title = "Подключение",
                subtitle = when {
                    whitelistDetected -> "Белые списки обнаружены"
                    activeItem == null -> "Профиль не выбран"
                    else -> activeItem.profile.name
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Switch(
                    checked = connected,
                    onCheckedChange = { onToggleTunnel() },
                    enabled = ui.state != ConnState.Disconnecting,
                    thumbContent = {
                        Text(
                            if (connected) "ON" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = trackColor,
                        uncheckedTrackColor = trackColor,
                        checkedThumbColor = Color.White,
                        uncheckedThumbColor = Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedBorderColor = Color.Transparent,
                        disabledUncheckedTrackColor = Color(0xFF9AA0A8),
                        disabledCheckedTrackColor = Color(0xFF9AA0A8),
                    ),
                    modifier = Modifier
                        .padding(top = 56.dp)
                        .height(46.dp)
                        .width(112.dp),
                )
            }

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
        OutlinedButton(
            onClick = onPrev,
            enabled = canSwitch && !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .height(NvpnBottomChrome.ButtonHeight)
                .width(62.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущий профиль")
        }
        Button(
            onClick = { if (canSwitch) onNext() },
            enabled = activeItem != null && !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .weight(1f)
                .height(NvpnBottomChrome.ButtonHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
            ),
        ) {
            Text(
                activeItem?.profile?.name?.ifBlank { "Профиль" } ?: "Выбрать профиль",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = onNext,
            enabled = canSwitch && !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .height(NvpnBottomChrome.ButtonHeight)
                .width(62.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующий профиль")
        }
    }
}

@Composable
private fun TunnelConnectionHintBanner(
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
                "Подсказка по подключению",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Скрыть подсказку",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            when {
                vpnLocked ->
                    "Во время активного соединения параметры заблокированы. " +
                        "Разблокировка доступна в «Настройках» → «Подключение»."
                sessionSwitchingEnabled ->
                    "Маршрут можно переключать на лету: «Прямое» и «Обход» применяются без отключения туннеля."
                quickSettingsHidden ->
                    "Быстрые параметры скрыты. Включите их в «Настройках» через пункт «Скрыть быстрые настройки»."
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
    dimmed: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val lookEnabled = enabled
    if (selected) {
        Button(
            onClick = onClick,
            enabled = lookEnabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            colors = if (selectedContainer != null) {
                ButtonDefaults.buttonColors(
                    containerColor = selectedContainer,
                    contentColor = Color.White,
                    disabledContainerColor = selectedContainer.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            contentPadding = PaddingValues(horizontal = 8.dp),
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
            enabled = lookEnabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                colors.outline.copy(alpha = if (dimmed) 0.22f else 0.45f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (dimmed) {
                    colors.onSurface.copy(alpha = 0.45f)
                } else {
                    colors.primary
                },
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
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
