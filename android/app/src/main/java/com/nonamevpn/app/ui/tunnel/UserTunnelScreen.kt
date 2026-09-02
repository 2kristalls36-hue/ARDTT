package com.nonamevpn.app.ui.tunnel

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.ProfileCatalog
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.NvpnFloatingShell
import com.nonamevpn.app.ui.components.rememberSmartHaptics
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
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val haptics = rememberSmartHaptics(uiHapticsEnabled)
    var previousConnState by remember { mutableStateOf(ui.state) }
    var connStateInitialized by remember { mutableStateOf(false) }
    var previousHasCallHash by remember { mutableStateOf(ui.hasCallHash) }
    var callHashInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        conn.updateProfile(profile)
        settings.setProfileName(profile?.name.orEmpty())
        conn.startInitialProbe()
    }
    LaunchedEffect(profile?.deviceId, hideIp) {
        if (profile == null) return@LaunchedEffect
        conn.setHideIp(hideIp)
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
        showIllustratedWallpaper = true,
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserTunnelSimpleScreen(
    ui: com.nonamevpn.app.core.ConnUiState,
    catalogItems: List<StoredProfile>,
    activeProfileId: String?,
    bypassActive: Boolean,
    showIllustratedWallpaper: Boolean,
    themeMode: String,
    onSwitchThemeMode: () -> Unit,
    onToggleTunnel: () -> Unit,
    onSelectPreviousProfile: () -> Unit,
    onSelectNextProfile: () -> Unit,
) {
    val droneExitDurationMs = 980L
    val lifecycleOwner = LocalLifecycleOwner.current
    var animationRestartToken by remember { mutableStateOf(0) }
    var showingBypassScene by remember { mutableStateOf(bypassActive && showIllustratedWallpaper) }
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
    LaunchedEffect(bypassActive, showIllustratedWallpaper) {
        if (!showIllustratedWallpaper) {
            dronesBlowAway = false
            showingBypassScene = false
            return@LaunchedEffect
        }
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
        if (showingBypassScene && showIllustratedWallpaper) {
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
                .combinedClickable(onClick = onSwitchThemeMode),
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
            Spacer(modifier = Modifier.height(14.dp))
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
            .padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionLit = connected || paused || busy
    val shellColor = NvpnFloatingShell.shellColor()
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        contentDescription = if (connected) "Отключить туннель" else "Подключить туннель",
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
