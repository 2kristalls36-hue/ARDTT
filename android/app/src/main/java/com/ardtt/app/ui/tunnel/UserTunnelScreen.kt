package com.ardtt.app.ui.tunnel

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.VpnPath
import com.ardtt.app.profile.ProfileCatalog
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.StoredProfile
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.RisingEdgeSuccessHaptic
import com.ardtt.app.ui.components.control.rememberArdttHaptics
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.nextThemeMode
import com.ardtt.app.ui.persistThemeMode
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.themeModeVisualKey
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.tunnelPowerBusy
import com.ardtt.app.ui.tunnelPowerClickDisconnects
import com.ardtt.app.ui.tunnelPowerContentDescription
import com.ardtt.app.ui.tunnelPowerSessionLit
import com.ardtt.app.ui.tunnelPowerToggleEnabled
import com.ardtt.app.ui.tunnelProfileCenterOpensImport
import com.ardtt.app.ui.vpnSessionBlocksProfileSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Illustrated user-mode tunnel: power control, drones, profile switcher. */
@Composable
fun UserTunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    onRequestConnect: () -> Unit,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val catalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val scope = rememberCoroutineScope()
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val donateBannerDismissed by settings.donateBannerDismissedFlow.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val haptics = rememberArdttHaptics(uiHapticsEnabled)

    LaunchedEffect(profile) {
        conn.updateProfile(profile)
        settings.setProfileName(profile?.name.orEmpty())
        conn.startInitialProbe()
    }
    RisingEdgeSuccessHaptic(haptics, ui.state == ConnState.Connected)
    RisingEdgeSuccessHaptic(haptics, ui.hasCallHash)

    val bypassActive = wallpaperBypassActive(
        pathMode = ConnPathMode.fromSetting(pathMode),
        activePath = ui.activePath,
        networkClass = ui.probe?.networkClass,
    )

    UserTunnelSimpleScreen(
        ui = ui,
        catalogItems = catalog.items,
        activeProfileId = catalog.activeId,
        bypassActive = bypassActive,
        themeMode = themeMode,
        onSwitchThemeMode = {
            scope.launch { persistThemeMode(settings, nextThemeMode(themeMode)) }
        },
        onToggleTunnel = {
            haptics.tick()
            if (tunnelPowerClickDisconnects(ui.state)) {
                conn.disconnect()
            } else {
                onRequestConnect()
            }
        },
        onSelectPreviousProfile = {
            if (vpnSessionBlocksProfileSwitch(ui.state)) return@UserTunnelSimpleScreen
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
            if (vpnSessionBlocksProfileSwitch(ui.state)) return@UserTunnelSimpleScreen
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
        onOpenProfiles = {
            haptics.tick()
            if (tunnelProfileCenterOpensImport(catalog.items.size)) {
                PendingUiAction.requestOpenProfileAdd()
            } else {
                PendingUiAction.requestOpenProfiles()
            }
        },
        showDonateBanner = DonateSupport.bannerVisible(donateBannerDismissed, ui.state),
        onDismissDonate = { scope.launch { settings.setDonateBannerDismissed(true) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserTunnelSimpleScreen(
    ui: com.ardtt.app.core.ConnUiState,
    catalogItems: List<StoredProfile>,
    activeProfileId: String?,
    bypassActive: Boolean,
    themeMode: String,
    onSwitchThemeMode: () -> Unit,
    onToggleTunnel: () -> Unit,
    onSelectPreviousProfile: () -> Unit,
    onSelectNextProfile: () -> Unit,
    onOpenProfiles: () -> Unit,
    showDonateBanner: Boolean,
    onDismissDonate: () -> Unit,
) {
    val droneExitDurationMs = 980L
    val lifecycleOwner = LocalLifecycleOwner.current
    var animationRestartToken by remember { mutableStateOf(0) }
    var showingBypassScene by remember { mutableStateOf(bypassActive) }
    var dronesBlowAway by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                animationRestartToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(bypassActive) {
        if (bypassActive) {
            dronesBlowAway = false
            showingBypassScene = true
            return@LaunchedEffect
        }
        if (showingBypassScene) {
            dronesBlowAway = true
            delay(droneExitDurationMs)
            dronesBlowAway = false
            showingBypassScene = false
        }
    }

    val connected = ui.state == ConnState.Connected
    val activeItem = catalogItems.find { it.id == activeProfileId } ?: catalogItems.firstOrNull()
    val modeBadge = themeModeVisualKey(themeMode)
    Box(modifier = Modifier.fillMaxSize()) {
        if (showingBypassScene) {
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
                .padding(top = ArdttSpacing.Small, end = ArdttSpacing.Medium)
                .size(ArdttSize.TouchTarget)
                .combinedClickable(onClick = onSwitchThemeMode),
            shape = CircleShape,
            color = ArdttFloatingShell.shellColor(),
            border = ArdttFloatingShell.shellBorder(),
            shadowElevation = ArdttFloatingShell.shadowElevation,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (modeBadge) {
                    "light" -> Icon(
                        imageVector = Icons.Outlined.WbSunny,
                        contentDescription = "Светлая тема",
                        tint = Color.White,
                        modifier = Modifier.size(ArdttSize.IconCompact),
                    )
                    "dark" -> Icon(
                        imageVector = Icons.Outlined.DarkMode,
                        contentDescription = "Тёмная тема",
                        tint = Color.White,
                        modifier = Modifier.size(ArdttSize.IconCompact),
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
                .padding(horizontal = ArdttSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TunnelPowerToggle(
                connected = connected,
                paused = ui.state == ConnState.PausedTrustedWifi,
                busy = tunnelPowerBusy(ui.state),
                sessionLit = tunnelPowerSessionLit(ui.state),
                enabled = tunnelPowerToggleEnabled(ui.state, ui.connectEnabled),
                contentDescription = tunnelPowerContentDescription(ui.state, connected),
                onClick = onToggleTunnel,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(198.dp),
            )
            UserConnectStatusBlock(
                state = ui.state,
                softInfo = ui.softInfo,
                activePath = ui.activePath,
                hideIp = ui.hideIp,
                lastError = ui.lastError,
                hasCallHash = ui.hasCallHash,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (showDonateBanner) {
                DonateSupportBanner(onDismiss = onDismissDonate)
            }
            Spacer(modifier = Modifier.height(ArdttSpacing.MediumPlus))
            ProfileSwitcherBar(
                activeItem = activeItem,
                canSwitch = catalogItems.size > 1,
                switchEnabled = catalogItems.size > 1 && !vpnSessionBlocksProfileSwitch(ui.state),
                onPrev = onSelectPreviousProfile,
                onNext = onSelectNextProfile,
                onOpenProfiles = onOpenProfiles,
            )
        }
    }
}

@Composable
private fun UserConnectStatusBlock(
    state: ConnState,
    softInfo: String?,
    activePath: VpnPath?,
    hideIp: Boolean,
    lastError: String?,
    hasCallHash: Boolean,
    modifier: Modifier = Modifier,
) {
    val connectedLike = state == ConnState.Connected || state == ConnState.PausedTrustedWifi
    val primaryLine = userModeStatusPrimary(state, activePath)
    val details = when {
        !hasCallHash && !connectedLike && (
            activePath == VpnPath.Bypass ||
                softInfo?.contains("обход", ignoreCase = true) == true ||
                softInfo?.contains("звонка", ignoreCase = true) == true ||
                softInfo?.contains("hash", ignoreCase = true) == true
            ) ->
            "Для режима «Обход» добавьте код звонка в настройках."
        else -> userModeStatusDetails(state, softInfo, lastError, activePath, hideIp)
    }
    val statusShadow = Shadow(
        color = Color.Black.copy(alpha = 0.45f),
        offset = Offset(0f, 1f),
        blurRadius = 6f,
    )
    val statusTextStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        shadow = statusShadow,
    )
    val detailsTextStyle = MaterialTheme.typography.bodyMedium.copy(
        shadow = statusShadow,
    )
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .padding(horizontal = ArdttSpacing.SmallPlus),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.TinyPlus),
    ) {
        Text(
            text = primaryLine,
            style = statusTextStyle,
            color = Color.White,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = details.orEmpty(),
            style = detailsTextStyle,
            color = Color.White.copy(alpha = if (details != null) 0.92f else 0f),
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TunnelPowerToggle(
    connected: Boolean,
    paused: Boolean,
    busy: Boolean,
    sessionLit: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shellColor = ArdttFloatingShell.shellColor()
    val accentColor = if (sessionLit) {
        Color(0xFF35C759)
    } else {
        Color.White.copy(alpha = 0.75f)
    }
    val pulseScale by animateFloatAsState(
        targetValue = if (sessionLit) 1.08f else 1f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "awg_pulse_scale",
    )
    val pulseAlpha by animateFloatAsState(
        targetValue = if (sessionLit) 0.28f else 0.12f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "awg_pulse_alpha",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(198.dp),
                color = accentColor,
                strokeWidth = 4.dp,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(198.dp * pulseScale)
                    .background(
                        color = accentColor.copy(alpha = pulseAlpha),
                        shape = CircleShape,
                    ),
            )
        }
        Surface(
            modifier = Modifier
                .size(180.dp)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                }
                .clickable(enabled = enabled, role = Role.Button) {
                    runCatching { onClick() }
                        .onFailure { t -> AppLog.e("TunnelToggle", "toggle failed: ${t.message}") }
                },
            color = shellColor,
            border = ArdttFloatingShell.shellBorder(),
            shape = CircleShape,
            shadowElevation = ArdttFloatingShell.shadowElevation,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (paused) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSwitcherBar(
    activeItem: StoredProfile?,
    canSwitch: Boolean,
    switchEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenProfiles: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ArdttBottomChrome.navigationReserve() + ArdttSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus),
    ) {
        val scheme = MaterialTheme.colorScheme
        val sessionLocked = canSwitch && !switchEnabled
        val lockedContainer = scheme.surface.copy(alpha = ArdttAlpha.Strong)
        val lockedContent = scheme.onSurface.copy(alpha = ArdttAlpha.Muted)
        val label = activeItem?.profile?.name?.ifBlank { "Профиль" } ?: "Выбрать профиль"
        if (canSwitch) {
            ArdttButton(
                onClick = onPrev,
                enabled = switchEnabled,
                variant = ArdttButtonVariant.Primary,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Предыдущий профиль",
                containerColor = if (sessionLocked) lockedContainer else null,
                contentColor = if (sessionLocked) lockedContent else null,
                modifier = Modifier.width(ArdttSize.ButtonCluster),
            )
        }
        ArdttButton(
            text = label,
            onClick = onOpenProfiles,
            enabled = true,
            variant = ArdttButtonVariant.Primary,
            fillMaxWidth = false,
            icon = if (sessionLocked) Icons.Filled.Lock else null,
            contentDescription = if (sessionLocked) {
                "Смена профиля недоступна. $label"
            } else {
                label
            },
            containerColor = if (sessionLocked) lockedContainer else null,
            contentColor = if (sessionLocked) lockedContent else null,
            modifier = Modifier.weight(1f),
        )
        if (canSwitch) {
            ArdttButton(
                onClick = onNext,
                enabled = switchEnabled,
                variant = ArdttButtonVariant.Primary,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Следующий профиль",
                containerColor = if (sessionLocked) lockedContainer else null,
                contentColor = if (sessionLocked) lockedContent else null,
                modifier = Modifier.width(ArdttSize.ButtonCluster),
            )
        }
    }
}
