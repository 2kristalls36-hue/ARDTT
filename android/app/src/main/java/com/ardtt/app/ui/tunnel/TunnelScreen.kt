package com.ardtt.app.ui.tunnel

import android.os.Build
import android.telephony.SubscriptionManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.BuildConfig
import com.ardtt.app.QsProfileSwitch
import com.ardtt.app.R
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.EgressIpProbe
import com.ardtt.app.core.NetcheckClient
import com.ardtt.app.core.NetcheckItem
import com.ardtt.app.core.NetcheckReport
import com.ardtt.app.core.NetcheckTone
import com.ardtt.app.core.NetcheckUiRow
import com.ardtt.app.core.VpnPath
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.core.readUnderlayAccessLabel
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
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
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
    val exitProvisionUrl = remember(servers, profileHost) {
        DeployHop.exitProvisionUrl(DeployHop.matchingServer(servers, profileHost))
    }
    val scope = rememberCoroutineScope()
    var publicIp by remember { mutableStateOf(EgressIpProbe.current()) }
    var providerIp by remember { mutableStateOf(EgressIpProbe.currentUnderlay()) }
    var providerIpError by remember { mutableStateOf(EgressIpProbe.lastUnderlayError) }
    var showBypassMethodDialog by remember { mutableStateOf(false) }
    var highlightBypassDialog by remember { mutableStateOf(false) }
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
    val disconnecting = ui.state == ConnState.Disconnecting
    val showDonateBanner = DonateSupport.bannerVisible(donateBannerDismissed, ui.state)
    val vpnLocked = connectionControlsLocked(
        sessionActive = ui.state.holdsUserSession(),
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
                exitProvisionBaseUrl = exitProvisionUrl,
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
                    else -> buttonColor
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
                selectedModeLabel = selectedModeLabel(pathMode),
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
    ArdttSectionCard(
        contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        shape = ArdttShapes.Control,
        shadowElevation = ArdttElevation.None,
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
                    .padding(end = ArdttSpacing.Small)
                    .size(ArdttSize.IconCompact),
            )
            Text(
                "Информация о подключении",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (!missingCallHashHint) {
                ArdttButton(
                    onClick = onDismiss,
                    variant = ArdttButtonVariant.Icon,
                    icon = Icons.Outlined.Close,
                    contentDescription = "Закрыть информационное сообщение",
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            when {
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    selectedModeLabel: String,
    currentModeLabel: String,
    currentModeColor: Color?,
    publicIp: String,
    ipPending: Boolean = false,
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
            StatusFactRow(label = "Выбран режим", value = selectedModeLabel)
        }

        HorizontalDivider(color = dividerColor)

        Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
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

        HorizontalDivider(color = dividerColor)
        Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus)) {
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
                        NetcheckTone.Ok -> connectedStatusColor()
                        NetcheckTone.Warn -> warningStatusColor()
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
