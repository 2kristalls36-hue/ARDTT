package com.ardtt.app.ui.tunnel

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.ConnectionUiAction
import com.ardtt.app.core.VpnPath
import com.ardtt.app.ui.performConnectionUiAction
import com.ardtt.app.ui.tunnelChromeActions
import com.ardtt.app.profile.ProfileCatalog
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.StoredProfile
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.RisingEdgeSuccessHaptic
import com.ardtt.app.ui.components.control.rememberArdttHaptics
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.nextThemeMode
import com.ardtt.app.ui.persistThemeMode
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttMotion
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttSurface
import com.ardtt.app.ui.theme.ArdttWallpaperTextShadow
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
        onConnectionAction = { action ->
            haptics.tick()
            performConnectionUiAction(context, conn, action)
        },
        showDonateBanner = DonateSupport.bannerVisible(donateBannerDismissed, ui.state),
        onDismissDonate = { scope.launch { settings.setDonateBannerDismissed(true) } },
        onAddCallHash = {
            haptics.tick()
            PendingUiAction.requestCallHashSettings()
        },
    )
}

/**
 * Geometry of the illustrated tunnel screen. The ring is the reference size;
 * every other measure scales with the ring so the control shrinks as one
 * piece on short viewports (landscape, small phones) instead of overflowing.
 */
internal object UserTunnelDefaults {
    val RingSize: Dp = 198.dp
    val MinRingSize: Dp = 132.dp
    val PowerButtonSize: Dp = 180.dp
    val PowerIconSize: Dp = 72.dp
    val PauseBarWidth: Dp = 16.dp
    val PauseBarHeight: Dp = 62.dp
    val PauseBarRadius: Dp = 10.dp
    val RingStroke: Dp = 4.dp
    val StatusMaxWidth: Dp = 320.dp

    /** Share of the viewport height the ring may take in portrait. */
    const val RingHeightFraction = 0.34f

    /** Share of the viewport height the drone sky occupies. */
    const val SkyHeightFraction = 0.37f
    val SkyTopOffset: Dp = ArdttSpacing.LargePlus

    /** Below this height the status block and chips move beside the ring. */
    val LandscapeMaxHeight: Dp = 480.dp

    const val PulseScaleLit = 1.08f
    const val PulseAlphaLit = 0.28f
    const val PulseAlphaIdle = 0.12f
    const val DroneExitDurationMs = 980L

    fun ringSize(viewportHeight: Dp): Dp =
        (viewportHeight * RingHeightFraction).coerceIn(MinRingSize, RingSize)

    fun sideBySide(viewportWidth: Dp, viewportHeight: Dp): Boolean =
        viewportWidth > viewportHeight && viewportHeight < LandscapeMaxHeight
}

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
    onConnectionAction: (ConnectionUiAction) -> Unit,
    showDonateBanner: Boolean,
    onDismissDonate: () -> Unit,
    onAddCallHash: () -> Unit,
) {
    val droneExitDurationMs = UserTunnelDefaults.DroneExitDurationMs
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val ringSize = UserTunnelDefaults.ringSize(maxHeight)
        val sideBySide = UserTunnelDefaults.sideBySide(maxWidth, maxHeight)
        if (showingBypassScene) {
            WhitelistSkyAnimation(
                restartToken = animationRestartToken,
                blowAway = dronesBlowAway,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(UserTunnelDefaults.SkyHeightFraction)
                    .offset(y = UserTunnelDefaults.SkyTopOffset)
                    .align(Alignment.TopCenter),
            )
        }
        ThemeModeBadge(
            themeMode = themeMode,
            onClick = onSwitchThemeMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = ArdttSpacing.Small, end = ArdttSpacing.Medium),
        )

        val powerToggle: @Composable (Modifier) -> Unit = { toggleModifier ->
            TunnelPowerToggle(
                connected = connected,
                paused = ui.state == ConnState.PausedTrustedWifi,
                busy = tunnelPowerBusy(ui.state),
                sessionLit = tunnelPowerSessionLit(ui.state),
                enabled = tunnelPowerToggleEnabled(ui.state, ui.connectEnabled),
                contentDescription = tunnelPowerContentDescription(ui.state, connected),
                onClick = onToggleTunnel,
                ringSize = ringSize,
                modifier = toggleModifier,
            )
        }
        val statusBlock: @Composable (Modifier) -> Unit = { blockModifier ->
            UserConnectStatusBlock(
                state = ui.state,
                statusMessage = ui.uiModel.message.ifBlank {
                    userModeStatusPrimary(ui.state, ui.activePath)
                },
                details = ui.uiModel.details ?: ui.softInfo,
                lastError = ui.lastError,
                hasCallHash = ui.hasCallHash,
                activePath = ui.activePath,
                hideIp = ui.hideIp,
                modifier = blockModifier,
                onAddCallHash = onAddCallHash,
            )
        }
        val actionChips: @Composable () -> Unit = {
            ConnectionActionChips(
                actions = tunnelChromeActions(ui.uiModel.actions),
                onAction = onConnectionAction,
            )
        }
        val profileBar: @Composable () -> Unit = {
            ProfileSwitcherBar(
                activeItem = activeItem,
                canSwitch = catalogItems.size > 1,
                switchEnabled = catalogItems.size > 1 && !vpnSessionBlocksProfileSwitch(ui.state),
                onPrev = onSelectPreviousProfile,
                onNext = onSelectNextProfile,
                onOpenProfiles = onOpenProfiles,
            )
        }

        if (sideBySide) {
            // Short landscape viewport: the ring keeps the left half, everything
            // else stacks on the right with the profile bar still at the bottom.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = ArdttSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Large),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(bottom = ArdttBottomChrome.navigationReserve()),
                    contentAlignment = Alignment.Center,
                ) {
                    powerToggle(Modifier)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    statusBlock(Modifier.align(Alignment.CenterHorizontally))
                    actionChips()
                    profileBar()
                }
            }
        } else {
            // Portrait: the main control and the profile switcher stay anchored to
            // the bottom edge on every height, the sky and badge own the top.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ArdttSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                powerToggle(Modifier.align(Alignment.CenterHorizontally))
                statusBlock(Modifier.align(Alignment.CenterHorizontally))
                actionChips()
                if (showDonateBanner) {
                    DonateSupportBanner(onDismiss = onDismissDonate)
                }
                Spacer(modifier = Modifier.height(ArdttSpacing.MediumPlus))
                profileBar()
            }
        }
    }
}

/** Floating theme switch in the top-right corner of the illustrated screen. */
@Composable
private fun ThemeModeBadge(
    themeMode: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shellColor = ArdttFloatingShell.shellColor()
    // The shell is light in the day scene and dark at night; the glyph must
    // follow the shell, not assume a dark background.
    val glyphColor = ArdttSurface.contentColorOn(shellColor)
    val modeBadge = themeModeVisualKey(themeMode)
    val label = when (modeBadge) {
        "light" -> "Тема: светлая. Переключить"
        "dark" -> "Тема: тёмная. Переключить"
        else -> "Тема: как в системе. Переключить"
    }
    Surface(
        modifier = modifier
            .size(ArdttSize.TouchTarget)
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = label)
            .semantics { contentDescription = label },
        shape = CircleShape,
        color = shellColor,
        border = ArdttFloatingShell.shellBorder(),
        shadowElevation = ArdttFloatingShell.shadowElevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (modeBadge) {
                "light" -> Icon(
                    imageVector = Icons.Outlined.WbSunny,
                    contentDescription = null,
                    tint = glyphColor,
                    modifier = Modifier.size(ArdttSize.IconCompact),
                )
                "dark" -> Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = null,
                    tint = glyphColor,
                    modifier = Modifier.size(ArdttSize.IconCompact),
                )
                else -> Text(
                    "A",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = glyphColor,
                )
            }
        }
    }
}

@Composable
private fun UserConnectStatusBlock(
    state: ConnState,
    statusMessage: String,
    details: String?,
    lastError: String?,
    hasCallHash: Boolean,
    activePath: VpnPath?,
    hideIp: Boolean,
    modifier: Modifier = Modifier,
    onAddCallHash: (() -> Unit)? = null,
) {
    val primaryLine = statusMessage.ifBlank { userModeStatusPrimary(state, activePath) }
    val needsCallHash = userModeNeedsCallHashHint(state, hasCallHash, activePath, details)
    val resolvedDetails = if (needsCallHash) {
        UserTunnelCopy.CALL_HASH_HINT
    } else {
        details ?: userModeStatusDetails(state, details, lastError, activePath, hideIp)
    }
    // Always painted straight on the wallpaper: light text with the shared shadow.
    val statusTextStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        shadow = ArdttWallpaperTextShadow,
    )
    val detailsTextStyle = MaterialTheme.typography.bodyMedium.copy(
        shadow = ArdttWallpaperTextShadow,
    )
    Column(
        modifier = modifier
            .widthIn(max = UserTunnelDefaults.StatusMaxWidth)
            .padding(horizontal = ArdttSpacing.SmallPlus)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.TinyPlus),
    ) {
        Text(
            text = primaryLine,
            style = statusTextStyle,
            color = ArdttSurface.LightContent,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = resolvedDetails.orEmpty(),
            style = detailsTextStyle,
            color = ArdttSurface.SoftLightContent.copy(alpha = if (resolvedDetails != null) 1f else 0f),
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (needsCallHash && onAddCallHash != null) {
            // The hint alone left the user without a way forward; this opens the
            // «Код звонка» card in Settings directly.
            ArdttButton(
                text = UserTunnelCopy.ADD_CALL_HASH,
                onClick = onAddCallHash,
                variant = ArdttButtonVariant.Tonal,
                size = ArdttButtonSize.Compact,
                fillMaxWidth = false,
            )
        }
    }
}

internal object UserTunnelCopy {
    const val CALL_HASH_HINT = "Для режима «Обход» добавьте код звонка в настройках."
    const val ADD_CALL_HASH = "Добавить код звонка"
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
    ringSize: Dp = UserTunnelDefaults.RingSize,
) {
    val shellColor = ArdttFloatingShell.shellColor()
    // Idle glyph follows the shell (light day shell → dark glyph); the lit
    // session ring keeps its dedicated green.
    val accentColor = if (sessionLit) {
        ArdttColors.SessionLit
    } else {
        ArdttSurface.contentColorOn(shellColor).copy(alpha = ArdttAlpha.Subtle)
    }
    val scale = ringSize / UserTunnelDefaults.RingSize
    val pulseScale by animateFloatAsState(
        targetValue = if (sessionLit) UserTunnelDefaults.PulseScaleLit else 1f,
        animationSpec = tween(durationMillis = ArdttMotion.Slow, easing = FastOutSlowInEasing),
        label = "awg_pulse_scale",
    )
    val pulseAlpha by animateFloatAsState(
        targetValue = if (sessionLit) UserTunnelDefaults.PulseAlphaLit else UserTunnelDefaults.PulseAlphaIdle,
        animationSpec = tween(durationMillis = ArdttMotion.Slow, easing = FastOutSlowInEasing),
        label = "awg_pulse_alpha",
    )
    val stateLabel = when {
        busy -> "Загрузка"
        !enabled -> "Недоступно"
        else -> null
    }
    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(ringSize),
                color = accentColor,
                strokeWidth = UserTunnelDefaults.RingStroke,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(ringSize * pulseScale)
                    .background(
                        color = accentColor.copy(alpha = pulseAlpha),
                        shape = CircleShape,
                    ),
            )
        }
        Surface(
            modifier = Modifier
                .size(UserTunnelDefaults.PowerButtonSize * scale)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                    stateLabel?.let { stateDescription = it }
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
                    val barShape = RoundedCornerShape(UserTunnelDefaults.PauseBarRadius * scale)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium * scale),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(2) {
                            Box(
                                modifier = Modifier
                                    .width(UserTunnelDefaults.PauseBarWidth * scale)
                                    .height(UserTunnelDefaults.PauseBarHeight * scale)
                                    .background(accentColor, shape = barShape),
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(UserTunnelDefaults.PowerIconSize * scale),
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
