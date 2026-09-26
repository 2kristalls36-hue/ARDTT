package com.ardtt.app.ui.tunnel

import android.os.Build
import android.telephony.SubscriptionManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ardtt.app.BuildConfig
import com.ardtt.app.QsProfileSwitch
import com.ardtt.app.R
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.EgressIpProbe
import com.ardtt.app.core.IpApiLookup
import com.ardtt.app.core.VpnPath
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.core.isConfirmedConnected
import com.ardtt.app.core.readUnderlayAccessLabel
import com.ardtt.app.core.readUnderlaySignal
import com.ardtt.app.core.UnderlaySignalReading
import com.ardtt.app.core.formatUnderlaySignalDbm
import com.ardtt.app.core.underlaySignalCellularEmphasized
import com.ardtt.app.core.underlaySignalWifiEmphasized
import com.ardtt.app.core.wifiSignalQuality
import com.ardtt.app.core.cellularSignalQuality
import com.ardtt.app.core.UnderlaySignalQuality
import com.ardtt.app.core.underlayIdentity
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.profile.ProfileCatalog
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.StoredProfile
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.HideIpCopy
import com.ardtt.app.ui.PathModeCopy
import com.ardtt.app.ui.commitHideIp
import com.ardtt.app.ui.commitPathMode
import com.ardtt.app.ui.components.control.ArdttChoiceChip
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.control.ArdttSettingBlock
import com.ardtt.app.ui.components.control.ArdttSwitchRow
import com.ardtt.app.ui.components.control.HideIpChipRow
import com.ardtt.app.ui.components.control.PathModeChipRow
import com.ardtt.app.ui.components.control.RisingEdgeSuccessHaptic
import com.ardtt.app.ui.components.control.rememberArdttHaptics
import com.ardtt.app.ui.components.feedback.ArdttInlineFactRow
import com.ardtt.app.ui.components.layout.ArdttFeedScaffold
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttMessageCard
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.performConnectionUiAction
import com.ardtt.app.ui.tunnelChromeActions
import com.ardtt.app.ui.connectionControlsLocked
import com.ardtt.app.ui.qsProfileTileLabel
import com.ardtt.app.ui.settings.BypassMethodDialog
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.connectedStatusColor
import com.ardtt.app.ui.theme.warningStatusColor
import com.ardtt.app.ui.tunnelConnectionParamsVisible
import com.ardtt.app.ui.tunnelQuickSettingsProfileHelp
import com.ardtt.app.ui.tunnelStickyCtaEnabled
import com.ardtt.app.ui.tunnelStickyCtaIsDestructive
import com.ardtt.app.ui.tunnelStickyCtaLabel
import com.ardtt.app.ui.vpnSessionBlocksProfileSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Refresh cadence of the admin tunnel status panel; both loops run only while STARTED. */
internal object TunnelPollDefaults {
    /** Cached egress IP read (in-memory) while a session is up. */
    const val EgressIpMs = 500L

    /** Underlay identity / access label re-read. */
    const val UnderlayMs = 1_500L
}

@Composable
fun TunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    onRequestConnect: () -> Unit,
    isAdmin: Boolean,
    classicAppearance: Boolean,
) {
    // Use session flags from AppRoot — a fresh collectAsState(false) here flashes the
    // user-mode round power button for a frame every time this tab is composed.
    if (tunnelSessionChrome(isAdmin, classicAppearance) == TunnelSessionChrome.User) {
        UserTunnelScreen(
            settings = settings,
            profiles = profiles,
            onRequestConnect = onRequestConnect,
        )
        return
    }
    val admin = isAdmin

    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val profileCatalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = serversRepo.snapshot())
    val profileHost = remember(profile) {
        profile?.let {
            NetworkEndpoint.hostOf(it.direct.endpoint) ?: NetworkEndpoint.hostOf(it.bypass.peer)
        }
    }
    val matchingServer = remember(servers, profileHost) {
        DeployHop.matchingServer(servers, profileHost)
    }
    val exitProvisionUrl = remember(matchingServer) {
        DeployHop.warpExitProvisionUrl(matchingServer)
    }
    val rejectProviderIps = remember(profileHost, matchingServer) {
        DeployHop.knownPublicHosts(profileHost, matchingServer)
    }
    val scope = rememberCoroutineScope()
    var publicIp by remember { mutableStateOf(EgressIpProbe.current()) }
    var providerIp by remember { mutableStateOf(EgressIpProbe.currentUnderlay()) }
    var providerIpError by remember { mutableStateOf(EgressIpProbe.lastUnderlayError) }
    var showBypassMethodDialog by remember { mutableStateOf(false) }
    var highlightBypassDialog by remember { mutableStateOf(false) }
    var accessLabel by remember { mutableStateOf(readUnderlayAccessLabel(context)) }
    var signal by remember { mutableStateOf(readUnderlaySignal(context)) }
    var lastUnderlayId by remember { mutableStateOf("") }
    var lastIpFetchKey by remember { mutableStateOf("") }

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
    val unlockConnControls by settings.unlockConnControlsFlow.collectAsStateWithLifecycle(initialValue = false)
    val hideTunnelQuickSettings by settings.hideTunnelQuickSettingsFlow.collectAsStateWithLifecycle(initialValue = false)
    val trustedWifiEnabled by settings.trustedWifiEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val donateBannerDismissed by settings.donateBannerDismissedFlow.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val haptics = rememberArdttHaptics(uiHapticsEnabled)
    val showConnectionParams = tunnelConnectionParamsVisible(hideTunnelQuickSettings)
    var showConnectionHint by rememberSaveable { mutableStateOf(true) }
    RisingEdgeSuccessHaptic(haptics, ui.state == ConnState.Connected)
    RisingEdgeSuccessHaptic(haptics, ui.hasCallHash)

    val connecting = ui.state == ConnState.Connecting
    val pausedTrusted = ui.state == ConnState.PausedTrustedWifi
    val connected = ui.state == ConnState.Connected
    val sessionUp = connected || pausedTrusted
    val viaVpn = ui.state.isConfirmedConnected()
    val disconnecting = ui.state == ConnState.Disconnecting
    val showDonateBanner = DonateSupport.bannerVisible(donateBannerDismissed, ui.state)
    val vpnLocked = connectionControlsLocked(
        sessionActive = ui.state.holdsUserSession(),
        unlockWhileConnected = unlockConnControls,
    )
    val probeTunnelIp = connecting || connected

    suspend fun refreshPublicIps(force: Boolean) {
        accessLabel = readUnderlayAccessLabel(context)
        signal = readUnderlaySignal(context)
        val id = underlayIdentity(context)
        val key = listOf(
            id,
            hideIp,
            profile?.provisionBaseUrl.orEmpty(),
            exitProvisionUrl.orEmpty(),
            profile?.deviceId.orEmpty(),
            viaVpn,
            probeTunnelIp,
        ).joinToString("|")
        if (!force && key == lastIpFetchKey) {
            providerIp = EgressIpProbe.currentUnderlay() ?: providerIp
            providerIpError = EgressIpProbe.lastUnderlayError
            publicIp = EgressIpProbe.current() ?: publicIp
            return
        }
        if (id != lastUnderlayId) {
            AppLog.v("Tunnel", "underlay identity $lastUnderlayId → $id")
            EgressIpProbe.invalidateUnderlay()
            providerIp = null
            providerIpError = null
        }
        lastUnderlayId = id
        lastIpFetchKey = key
        val snapshot = runCatching {
            IpApiLookup.fetchPublicIps(
                context = context,
                hideIp = hideIp,
                provisionBaseUrl = profile?.provisionBaseUrl,
                exitProvisionBaseUrl = exitProvisionUrl,
                deviceId = profile?.deviceId,
                viaVpn = viaVpn,
                rejectIps = rejectProviderIps,
                probeTunnel = probeTunnelIp,
                force = force,
            )
        }.getOrElse {
            AppLog.w("Tunnel", "public ip lookup failed: ${it.message}")
            null
        }
        if (snapshot == null) {
            providerIp = EgressIpProbe.currentUnderlay()
            providerIpError = EgressIpProbe.lastUnderlayError
            publicIp = EgressIpProbe.current()
            return
        }
        val providerAddress = snapshot.provider.ip.takeIf { it.isNotBlank() }
        providerIp = providerAddress ?: EgressIpProbe.currentUnderlay()
        providerIpError = when {
            !providerAddress.isNullOrBlank() -> null
            else -> snapshot.provider.error ?: EgressIpProbe.lastUnderlayError
        }
        val tunnelAddress = snapshot.tunnel.ip.takeIf { it.isNotBlank() }
        publicIp = tunnelAddress ?: EgressIpProbe.current()
    }

    // Both pollers pause while the activity is stopped: LaunchedEffect alone
    // keeps ticking in the background as long as the tab stays composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, ui.state, hideIp) {
        val watchEgress =
            ui.state == ConnState.Connecting ||
                ui.state == ConnState.Connected ||
                ui.state == ConnState.PausedTrustedWifi
        if (!watchEgress) {
            publicIp = EgressIpProbe.current()
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                publicIp = EgressIpProbe.current()
                delay(TunnelPollDefaults.EgressIpMs)
            }
        }
    }

    LaunchedEffect(
        lifecycleOwner,
        hideIp,
        profile?.provisionBaseUrl,
        exitProvisionUrl,
        profile?.deviceId,
        viaVpn,
        probeTunnelIp,
    ) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refreshPublicIps(force = false)
                delay(TunnelPollDefaults.UnderlayMs)
            }
        }
    }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                scope.launch { refreshPublicIps(force = true) }
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

    LaunchedEffect(sessionUp, hideIp, ui.probe?.networkClass, ui.probe?.elapsedMs) {
        refreshPublicIps(force = true)
    }

    val pull = rememberPullRefresh {
        refreshPublicIps(force = true)
        val skipProbe = connecting || connected || pausedTrusted || disconnecting
        if (!skipProbe) {
            conn.startInitialProbe()
            withTimeoutOrNull(12_000) {
                conn.ui.first { it.state != ConnState.Probing }
            }
        }
    }

    ArdttFeedScaffold(
        refreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
        modifier = Modifier.fillMaxSize(),
        header = {
            ArdttTabHeader(title = "Подключение")
        },
        stickyContent = {
            // Sticky «Подключить» / «Остановить» (same button) above tab bar.
            // Idle Probing is not Cancel — that was flashing red Stop on tab open.
            ArdttPrimaryButton(
                text = tunnelStickyCtaLabel(ui.state),
                onClick = {
                    haptics.tick()
                    when {
                        tunnelStickyCtaIsDestructive(ui.state) -> conn.disconnect()
                        else -> onRequestConnect()
                    }
                },
                enabled = tunnelStickyCtaEnabled(ui.state, ui.connectEnabled),
                containerColor = when {
                    tunnelStickyCtaIsDestructive(ui.state) -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                icon = when {
                    ui.state == ConnState.Connecting -> Icons.Default.Stop
                    pausedTrusted -> Icons.Default.Pause
                    sessionUp -> Icons.Default.Stop
                    else -> Icons.Default.PowerSettingsNew
                },
            )
        },
    ) {
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
            if (showDonateBanner) {
                DonateSupportBanner(
                    onDismiss = { scope.launch { settings.setDonateBannerDismissed(true) } },
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
                ArdttSectionCard(
                    contentPadding = PaddingValues(horizontal = ArdttSpacing.LargePlus, vertical = ArdttSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(ArdttSpacing.TinyPlus),
                    shape = ArdttShapes.Panel,
                    border = BorderStroke(
                        ArdttSectionCardDefaults.ContourWidth,
                        if (active) ArdttColors.Connected else MaterialTheme.colorScheme.error,
                    ),
                    shadowElevation = ArdttElevation.None,
                ) {
                    Text(
                        if (active) "Подписка активна" else "Подписка неактивна",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) connectedStatusColor() else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Действует до $expiresText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showConnectionParams) {
                val switchLocked = vpnSessionBlocksProfileSwitch(ui.state)
                ArdttSectionCard(
                contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
                shape = ArdttShapes.Menu,
            ) {
                Text(
                    "Параметры подключения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ArdttSettingBlock(
                    title = "Профиль",
                    subtitle = tunnelQuickSettingsProfileHelp(
                        count = profileCatalog.items.size,
                        locked = switchLocked,
                    ),
                    compact = true,
                ) {
                    TunnelQuickSettingsProfileRow(
                        items = profileCatalog.items,
                        activeId = profileCatalog.activeId,
                        enabled = !switchLocked,
                        onSelect = { item ->
                            haptics.tick()
                            scope.launch { QsProfileSwitch.activate(context, item) }
                        },
                    )
                }
                ArdttSettingBlock(
                    title = "Маршрут",
                    subtitle = PathModeCopy.help(pathMode, ui.hasCallHash, compact = true),
                    compact = true,
                ) {
                    PathModeChipRow(
                        pathMode = pathMode,
                        hasCallHash = ui.hasCallHash,
                        enabled = !vpnLocked,
                        chipHeight = ArdttSize.ChipCompact,
                        coloredSelection = true,
                        onSelect = { mode ->
                            haptics.tick()
                            scope.launch { commitPathMode(settings, conn, mode) }
                        },
                        onNeedCallHash = {
                            haptics.tick()
                            showBypassMethodDialog = true
                            highlightBypassDialog = true
                        },
                    )
                }

                ArdttSettingBlock(
                    title = "Исходящий адрес",
                    subtitle = HideIpCopy.subtitle(hideIp),
                    compact = true,
                ) {
                    HideIpChipRow(
                        hideIp = hideIp,
                        enabled = !vpnLocked,
                        chipHeight = ArdttSize.ChipCompact,
                        onSelect = { enabled ->
                            haptics.tick()
                            scope.launch { commitHideIp(settings, conn, enabled) }
                        },
                    )
                }

                ArdttSwitchRow(
                    title = "Доверенная WiFi",
                    subtitle = if (trustedWifiEnabled) {
                        "В сохранённых сетях туннель приостанавливается. " +
                            "Список сетей доступен в разделе «Настройки»."
                    } else {
                        "Приостановка в Wi‑Fi отключена. " +
                            "Список сетей доступен в разделе «Настройки»."
                    },
                    checked = trustedWifiEnabled,
                    onCheckedChange = { on ->
                        haptics.tick()
                        scope.launch { settings.setTrustedWifiEnabled(on) }
                    },
                )
            }
            }

            // ═══ Статус сессии — структурированная панель ═══
            TunnelStatusPanel(
                statusText = ui.uiModel.message.ifBlank {
                    sessionCardStatusText(ui.state, publicIp, ui.lastError)
                },
                statusColor = when {
                    pausedTrusted -> warningStatusColor()
                    connected -> connectedStatusColor()
                    ui.state == ConnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                currentModeLabel = currentModeLabel(ui.state, ui.activePath),
                currentModeColor = when (ui.activePath) {
                    VpnPath.Direct -> ArdttColors.PathDirect
                    VpnPath.Bypass -> ArdttColors.PathBypass
                    null -> null
                },
                publicIp = when {
                    pausedTrusted -> "—"
                    !publicIp.isNullOrBlank() -> publicIp!!
                    connecting || connected -> ""
                    else -> "—"
                },
                ipPending = (connecting || connected) && publicIp.isNullOrBlank(),
                onIpClick = if (connecting || connected) {
                    {
                        scope.launch {
                            EgressIpProbe.invalidate()
                            publicIp = null
                            refreshPublicIps(force = true)
                        }
                    }
                } else {
                    null
                },
                accessLabel = accessLabel,
                signal = signal,
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
                        lastIpFetchKey = ""
                        refreshPublicIps(force = true)
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
                softInfo = ui.softInfo?.takeIf { it.isNotBlank() },
                errorText = ui.lastError?.takeIf { ui.state == ConnState.Error && it.isNotBlank() },
            )
            ConnectionActionChips(
                actions = tunnelChromeActions(ui.uiModel.actions),
                onAction = { action ->
                    haptics.tick()
                    performConnectionUiAction(context, conn, action)
                },
            )
    }
    BypassMethodDialog(
        visible = showBypassMethodDialog,
        highlight = highlightBypassDialog,
        onDismiss = {
            showBypassMethodDialog = false
            highlightBypassDialog = false
        },
    )
}

@Composable
private fun TunnelConnectionHintBanner(
    missingCallHashHint: Boolean,
    vpnLocked: Boolean,
    sessionSwitchingEnabled: Boolean,
    quickSettingsHidden: Boolean,
    onDismiss: () -> Unit,
) {
    ArdttMessageCard(
        title = "Информация о подключении",
        body = when {
            missingCallHashHint ->
                "Необходимо добавить код звонка для режима «Обход»: нажмите «Обход» в параметрах подключения " +
                    "или откройте «Настройки» → «Метод обхода». До сохранения кода это сообщение остаётся закреплённым."
            vpnLocked ->
                "Во время активного соединения параметры заблокированы. " +
                    "Разблокировка доступна в «Настройках» → «Подключение»."
            sessionSwitchingEnabled ->
                "Маршрут можно переключать без разрыва текущего соединения: «Прямое» и «Обход» применяются сразу."
            quickSettingsHidden ->
                "Быстрые параметры скрыты. Для отображения откройте «Настройки» и отключите пункт «Скрыть быстрые настройки»."
            else ->
                "Здесь вы управляете профилем, маршрутом, исходящим адресом и доверенной Wi‑Fi. " +
                    "Код звонка для обхода настраивается на вкладке «Настройки»."
        },
        icon = Icons.Outlined.Info,
        onDismiss = if (missingCallHashHint) null else onDismiss,
        dismissDescription = "Закрыть информационное сообщение",
    )
}

@Composable
private fun TunnelQuickSettingsProfileRow(
    items: List<StoredProfile>,
    activeId: String?,
    enabled: Boolean,
    onSelect: (StoredProfile) -> Unit,
) {
    if (items.isEmpty()) return
    val selectedId = activeId ?: items.first().id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
    ) {
        items.forEach { item ->
            ArdttChoiceChip(
                label = qsProfileTileLabel(item.profile.name),
                selected = item.id == selectedId,
                enabled = enabled,
                height = ArdttSize.ChipCompact,
                onClick = {
                    if (item.id != selectedId) onSelect(item)
                },
            )
        }
    }
}

@Composable
private fun TunnelStatusPanel(
    statusText: String,
    statusColor: Color,
    currentModeLabel: String,
    currentModeColor: Color?,
    publicIp: String,
    ipPending: Boolean = false,
    onIpClick: (() -> Unit)? = null,
    accessLabel: String,
    signal: UnderlaySignalReading,
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
    softInfo: String?,
    errorText: String?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ArdttAlpha.Divider)
    ArdttSectionCard(
        contentPadding = PaddingValues(horizontal = ArdttSpacing.LargePlus, vertical = ArdttSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.MediumPlus),
        shape = ArdttShapes.Panel,
        shadowElevation = ArdttElevation.None,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
            StatusFactRow(
                label = "Статус",
                value = statusText,
                valueColor = statusColor,
            )
            StatusFactRow(
                label = "Текущий режим",
                value = currentModeLabel,
                valueColor = if (currentModeLabel == "—") muted else currentModeColor,
            )
        }

        HorizontalDivider(color = dividerColor)

        Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
            StatusFactRow(label = "Оператор", value = accessLabel)
            StatusSignalRow(signal = signal)
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
            Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
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
private fun StatusSignalRow(signal: UnderlaySignalReading) {
    val wifiOn = underlaySignalWifiEmphasized(signal.wifiConnected)
    val cellOn = underlaySignalCellularEmphasized(signal.wifiConnected, signal.cellularConnected)
    check(statusSignalPlacement() == StatusSignalPlacement.LabelStart)
    ArdttInlineFactRow(
        label = TunnelPanelCopy.SIGNAL_LABEL,
        value = "",
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalMetric(
                    icon = Icons.Outlined.Wifi,
                    text = formatUnderlaySignalDbm(signal.wifiDbm),
                    emphasized = wifiOn,
                    quality = wifiSignalQuality(signal.wifiDbm),
                    contentDescription = "Wi‑Fi",
                )
                SignalMetric(
                    icon = Icons.Outlined.SignalCellularAlt,
                    text = formatUnderlaySignalDbm(signal.cellularDbm),
                    emphasized = cellOn,
                    quality = cellularSignalQuality(signal.cellularDbm),
                    contentDescription = "Сотовая сеть",
                )
            }
        },
    )
}

@Composable
private fun SignalMetric(
    icon: ImageVector,
    text: String,
    emphasized: Boolean,
    quality: UnderlaySignalQuality,
    contentDescription: String,
) {
    val color = when (quality) {
        UnderlaySignalQuality.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = ArdttAlpha.Muted,
        )
        UnderlaySignalQuality.Poor -> MaterialTheme.colorScheme.error
        UnderlaySignalQuality.Fair -> warningStatusColor()
        UnderlaySignalQuality.Good,
        UnderlaySignalQuality.Excellent -> connectedStatusColor()
    }
    val underlined = signalMetricMark(emphasized) == SignalMetricMark.Underline
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = color,
                modifier = Modifier.size(ArdttSize.IconSmall),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            )
        }
        Box(
            modifier = Modifier
                .padding(top = ArdttSpacing.Hairline)
                .fillMaxWidth()
                .height(ArdttSize.Stroke)
                .background(if (underlined) color else Color.Transparent),
        )
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
    ArdttInlineFactRow(
        label = label,
        value = value,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        valueColor = valueColor ?: MaterialTheme.colorScheme.onSurface,
        pending = pending,
        leadingIcon = valueLeadingIcon?.let { icon ->
            {
                Image(
                    painter = painterResource(icon),
                    contentDescription = valueLeadingContentDescription,
                    modifier = Modifier.size(ArdttSize.IconSmall),
                )
            }
        },
    )
}
