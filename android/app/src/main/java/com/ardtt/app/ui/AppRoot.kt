package com.ardtt.app.ui

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnPathMode
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.needsNotificationPermission
import com.ardtt.app.core.vpnPermissionDeniedHint
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.telemetry.TelemetryRecorder
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.admin.DiagnosticsScreen
import com.ardtt.app.ui.admin.LogsScreen
import com.ardtt.app.ui.admin.NetworkScreen
import com.ardtt.app.ui.admin.ServersScreen
import com.ardtt.app.ui.admin.TestingScreen
import com.ardtt.app.ui.components.control.rememberArdttHaptics
import com.ardtt.app.ui.components.layout.ArdttBackdrop
import com.ardtt.app.ui.components.layout.ArdttNavItem
import com.ardtt.app.ui.components.layout.ArdttNavigationBar
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.exceptions.ExceptionsScreen
import com.ardtt.app.ui.profiles.ProfilesScreen
import com.ardtt.app.ui.settings.SettingsScreen
import com.ardtt.app.ui.telemetry.TelemetryRecordingOverlay
import com.ardtt.app.ui.theme.LocalIllustratedBackdrop
import com.ardtt.app.ui.theme.wallpaperAdaptedColorScheme
import com.ardtt.app.ui.tunnel.TunnelScreen
import com.ardtt.app.ui.tunnel.TunnelWallpaperBackdrop
import com.ardtt.app.ui.tunnel.TunnelWallpaperCache
import com.ardtt.app.ui.tunnel.TunnelWallpaperSession
import com.ardtt.app.ui.tunnel.resolveTunnelWallpaper
import com.ardtt.app.ui.tunnel.tunnelWallpaperVisible
import com.ardtt.app.ui.tunnel.wallpaperBypassActive
import com.ardtt.app.update.AppUpdateController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class SessionChromeFlags(
    val admin: Boolean,
    val classicAppearance: Boolean,
)

@Composable
fun AppRoot(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    deployEngine: DeployEngine,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<SessionChromeFlags?>(null) }
    LaunchedEffect(settings) {
        combine(
            settings.isAdminUnlocked,
            settings.classicAppearanceEnabled,
        ) { admin, classic ->
            SessionChromeFlags(
                admin = admin,
                classicAppearance = classic,
            )
        }.collect { session = it }
    }
    val flags = session
    if (flags == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            ArdttBackdrop(modifier = Modifier.fillMaxSize())
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    val admin = flags.admin
    val classicAppearance = flags.classicAppearance
    val activity = context as? Activity
    val conn = remember { ConnectionManager.get(context) }
    val scope = rememberCoroutineScope()
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val dynamicColors by settings.dynamicColorsFlow.collectAsStateWithLifecycle(initialValue = true)
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val haptics = rememberArdttHaptics(uiHapticsEnabled)
    val testingMode by settings.testingModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathModeSetting by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val bypassWallpaper by remember(conn, pathModeSetting) {
        conn.ui
            .map { ui ->
                wallpaperBypassActive(
                    pathMode = ConnPathMode.fromSetting(pathModeSetting),
                    activePath = ui.activePath,
                    networkClass = ui.probe?.networkClass,
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = wallpaperBypassActive(
            pathMode = ConnPathMode.fromSetting(pathModeSetting),
            activePath = conn.ui.value.activePath,
            networkClass = conn.ui.value.probe?.networkClass,
        ),
    )
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: AppDestination.Tunnel.route
    val recorder = remember { TelemetryRecorder.get(context) }
    val isRecording by recorder.isRecording.collectAsStateWithLifecycle()
    val updates = remember { AppUpdateController.get(context) }
    val updateUi by updates.ui.collectAsStateWithLifecycle()
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }
    val tunnelWallpaperScene = remember { TunnelWallpaperSession.currentOrPick() }
    val darkTheme = themeModeIsDark(themeMode, isSystemInDarkTheme())
    val tunnelWallpaper = resolveTunnelWallpaper(
        scene = tunnelWallpaperScene,
        bypass = bypassWallpaper,
        darkTheme = darkTheme,
    )
    val showUserWallpaper = tunnelWallpaperVisible(
        admin = admin,
        classicAppearance = classicAppearance,
    )
    LaunchedEffect(tunnelWallpaper, bypassWallpaper, darkTheme, showUserWallpaper) {
        AppLog.i(
            "TunnelWallpaper",
            "draw scene=${tunnelWallpaper.scene} time=${tunnelWallpaper.time} " +
                "bypass=$bypassWallpaper dark=$darkTheme visible=$showUserWallpaper",
        )
    }
    val baseColorScheme = MaterialTheme.colorScheme
    val wallpaperAccent = remember(showUserWallpaper, dynamicColors, tunnelWallpaper) {
        if (!showUserWallpaper || !dynamicColors) return@remember null
        TunnelWallpaperCache.accentArgb(
            scene = tunnelWallpaper.scene,
            time = tunnelWallpaper.time,
        )?.let { Color(it) }
    }
    val adaptedColorScheme = remember(baseColorScheme, wallpaperAccent, darkTheme, showUserWallpaper, dynamicColors) {
        if (!showUserWallpaper || !dynamicColors || wallpaperAccent == null) {
            baseColorScheme
        } else {
            wallpaperAdaptedColorScheme(
                base = baseColorScheme,
                accent = wallpaperAccent,
                darkTheme = darkTheme,
            )
        }
    }

    val testingTabVisible = TestingSessionGuard.testingTabVisible(testingMode, isRecording)
    val tabs = ArdttNavPlan.primary(admin, testingTabVisible)
    val badgeRoute = ArdttNavPlan.navBadgeRoute(admin, isRecording)
    val navItems = tabs.map { dest ->
        ArdttNavItem(
            route = dest.route,
            label = dest.navLabel,
            icon = dest.navIcon(),
            badgeCount = if (dest.route == badgeRoute) 1 else 0,
        )
    }
    val tabReselectSignal = remember { mutableStateMapOf<String, Int>() }
    ArdttNavPlan.visibleDestinations(admin, testingTabVisible).forEach { dest ->
        if (dest.route !in tabReselectSignal) {
            tabReselectSignal[dest.route] = 0
        }
    }
    val selectedNavRoute = ArdttNavPlan.barSelectedRoute(
        currentRoute = currentRoute,
        primary = tabs,
        admin = admin,
    )
    var vpnConsentBackgroundVisible by remember { mutableStateOf(false) }
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnConsentBackgroundVisible = false
        if (result.resultCode == Activity.RESULT_OK) {
            AppLog.i("TunnelPrep", "Tunnel permission granted")
            conn.connect()
        } else {
            AppLog.w("TunnelPrep", "Tunnel permission denied/cancelled")
            conn.reportUserError(vpnPermissionDeniedHint(context))
        }
    }

    fun launchVpnPrepareOrConnect() {
        scope.launch {
            val prep = runCatching { VpnService.prepare(activity ?: context) }.getOrNull()
            if (prep != null) {
                AppLog.i("TunnelPrep", "Launching system tunnel consent")
                // Some vendor Android builds render the system VPN consent
                // surface translucent. Paint an opaque app surface first so
                // system text never overlaps the busy tunnel screen.
                vpnConsentBackgroundVisible = true
                delay(100)
                runCatching { vpnPermission.launch(prep) }
                    .onFailure {
                        vpnConsentBackgroundVisible = false
                        conn.reportUserError("Не удалось открыть системное разрешение туннеля")
                    }
            } else {
                AppLog.i("TunnelPrep", "Tunnel already permitted — connect")
                conn.connect()
            }
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.i("NotifPrep", "POST_NOTIFICATIONS granted=$granted")
        launchVpnPrepareOrConnect()
    }

    fun requestVpnThenConnect() {
        scope.launch {
            val wantShade = runCatching { settings.vpnNotificationVisibleSnapshot() }.getOrDefault(true)
            if (wantShade && needsNotificationPermission(context) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                AppLog.i("NotifPrep", "Requesting POST_NOTIFICATIONS before connect")
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@launch
            }
            launchVpnPrepareOrConnect()
        }
    }

    fun navigateTab(route: String) {
        haptics.tick()
        if (route == currentRoute) {
            tabReselectSignal[route] = (tabReselectSignal[route] ?: 0) + 1
            navController.popBackStack(route, inclusive = false)
            return
        }
        navController.navigate(route) {
            popUpTo(AppDestination.Tunnel.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openCallHash by PendingUiAction.openCallHashSettings.collectAsStateWithLifecycle()
    LaunchedEffect(openCallHash) {
        if (openCallHash && currentRoute != AppDestination.Settings.route) {
            navigateTab(AppDestination.Settings.route)
        }
    }
    val openUpdateDownload by PendingUiAction.openUpdateDownload.collectAsStateWithLifecycle()
    LaunchedEffect(openUpdateDownload) {
        if (openUpdateDownload && currentRoute != AppDestination.Settings.route) {
            navigateTab(AppDestination.Settings.route)
        }
    }

    val openDeploy by PendingUiAction.openDeployServerId.collectAsStateWithLifecycle()
    LaunchedEffect(openDeploy) {
        if (openDeploy != null && currentRoute != AppDestination.Servers.route) {
            navigateTab(AppDestination.Servers.route)
        }
    }
    val openProfiles by PendingUiAction.openProfiles.collectAsStateWithLifecycle()
    LaunchedEffect(openProfiles) {
        if (!PendingUiAction.consumeOpenProfiles()) return@LaunchedEffect
        if (currentRoute != AppDestination.Profiles.route) {
            navigateTab(AppDestination.Profiles.route)
        }
    }
    val openServers by PendingUiAction.openServers.collectAsStateWithLifecycle()
    LaunchedEffect(openServers) {
        if (!PendingUiAction.consumeOpenServers()) return@LaunchedEffect
        if (currentRoute != AppDestination.Servers.route) {
            navigateTab(AppDestination.Servers.route)
        }
    }
    val availableUpdateVersion = updateUi.available
        ?.takeIf { it.isNewer }
        ?.versionName
        ?.trim()
        ?.ifBlank { null }
    val showUpdatePrompt = availableUpdateVersion != null &&
        availableUpdateVersion != dismissedUpdateVersion &&
        !updateUi.downloading &&
        updateUi.downloadedFile == null &&
        currentRoute != AppDestination.Settings.route

    LaunchedEffect(Unit) {
        AppLog.i("App", "UI ready")
    }

    LaunchedEffect(admin) {
        AppLog.setDetailedEnabled(admin)
        AppLog.i("App", if (admin) "Подробные логи (админ)" else "Минимальные логи")
    }

    LaunchedEffect(silent, dial, pathModeSetting, hideIp) {
        applySessionConnectionPrefs(
            conn = conn,
            silentRecreate = silent,
            dialSetting = dial,
            pathModeSetting = pathModeSetting,
            hideIp = hideIp,
        )
    }

    LaunchedEffect(admin, testingMode, isRecording, currentRoute) {
        val dest = AppDestination.entries.find { it.route == currentRoute }
        val blocked = when {
            dest == AppDestination.Testing ->
                !TestingSessionGuard.testingTabVisible(testingMode, isRecording)
            dest == AppDestination.Diagnostics || dest?.adminOnly == true -> !admin
            else -> false
        }
        if (blocked) {
            navController.navigate(AppDestination.Tunnel.route) {
                popUpTo(AppDestination.Tunnel.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    var previousRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoute, isRecording) {
        if (isRecording && previousRoute != currentRoute) {
            recorder.logNavigation(previousRoute, currentRoute)
        }
        previousRoute = currentRoute
    }
    TelemetryRecordingOverlay(
        isRecording = isRecording,
        currentScreen = currentRoute,
    ) {
        MaterialTheme(
            colorScheme = adaptedColorScheme,
            typography = MaterialTheme.typography,
        ) {
            CompositionLocalProvider(LocalIllustratedBackdrop provides showUserWallpaper) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // One scene × time-of-day for every user-mode tab, including Tunnel.
                    // Keep the Image composed so tab switches do not flash Field.
                    if (showUserWallpaper) {
                        key(tunnelWallpaper.scene) {
                            TunnelWallpaperBackdrop(
                                wallpaper = tunnelWallpaper,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        ArdttBackdrop(modifier = Modifier.fillMaxSize())
                    }

                    NavHost(
                        navController = navController,
                        startDestination = AppDestination.Tunnel.route,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                        sizeTransform = { null },
                    ) {
                        composable(AppDestination.Tunnel.route) {
                            TunnelScreen(
                                settings = settings,
                                profiles = profiles,
                                serversRepo = serversRepo,
                                onRequestConnect = { requestVpnThenConnect() },
                                isAdmin = admin,
                                classicAppearance = classicAppearance,
                                onOpenExceptions = { navigateTab(AppDestination.Exceptions.route) },
                            )
                        }
                        composable(AppDestination.Servers.route) {
                            ServersScreen(
                                serversRepo = serversRepo,
                                engine = deployEngine,
                                profiles = profiles,
                                reselectSignal = tabReselectSignal[AppDestination.Servers.route] ?: 0,
                            )
                        }
                        composable(AppDestination.Profiles.route) {
                            ProfilesScreen(
                                settings = settings,
                                profiles = profiles,
                                serversRepo = serversRepo,
                                onApplied = { navigateTab(AppDestination.Tunnel.route) },
                            )
                        }
                        composable(AppDestination.Exceptions.route) {
                            ExceptionsScreen(
                                settings = settings,
                                onBack = if (admin) {
                                    { navigateTab(AppDestination.Tunnel.route) }
                                } else {
                                    null
                                },
                            )
                        }
                        composable(AppDestination.Diagnostics.route) {
                            DiagnosticsScreen(
                                testingVisible = testingTabVisible,
                                onOpenNetwork = { navigateTab(AppDestination.Network.route) },
                                onOpenLogs = { navigateTab(AppDestination.Logs.route) },
                                onOpenTesting = { navigateTab(AppDestination.Testing.route) },
                            )
                        }
                        composable(AppDestination.Network.route) {
                            NetworkScreen(
                                settings = settings,
                                profiles = profiles,
                                serversRepo = serversRepo,
                                onBack = { navigateTab(AppDestination.Diagnostics.route) },
                            )
                        }
                        composable(AppDestination.Logs.route) {
                            LogsScreen(
                                onBack = if (admin) {
                                    { navigateTab(AppDestination.Diagnostics.route) }
                                } else {
                                    null
                                },
                                testingVisible = testingTabVisible,
                                onOpenTesting = { navigateTab(AppDestination.Testing.route) },
                            )
                        }
                        composable(AppDestination.Settings.route) {
                            SettingsScreen(
                                settings = settings,
                                isRecording = isRecording,
                                onOpenTesting = { navigateTab(AppDestination.Testing.route) },
                            )
                        }
                        composable(AppDestination.Testing.route) {
                            TestingScreen(
                                profiles = profiles,
                                onBack = {
                                    navigateTab(
                                        if (admin) {
                                            AppDestination.Diagnostics.route
                                        } else {
                                            AppDestination.Logs.route
                                        },
                                    )
                                },
                            )
                        }
                    }

                    ArdttNavigationBar(
                        items = navItems,
                        selectedRoute = selectedNavRoute,
                        onSelect = { route -> navigateTab(route) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    if (vpnConsentBackgroundVisible) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface,
                        ) {}
                    }
                    if (showUpdatePrompt) {
                        ArdttDialog(
                            title = "Доступно обновление",
                            onDismissRequest = {
                                dismissedUpdateVersion = availableUpdateVersion
                            },
                            confirmAction = ArdttDialogAction(
                                text = "Загрузить",
                                onClick = {
                                    dismissedUpdateVersion = availableUpdateVersion
                                    PendingUiAction.requestOpenUpdateDownload()
                                    navigateTab(AppDestination.Settings.route)
                                },
                            ),
                            dismissAction = ArdttDialogAction(
                                text = "Отмена",
                                onClick = {
                                    dismissedUpdateVersion = availableUpdateVersion
                                },
                            ),
                        ) {
                            Text(
                                "Найдена версия $availableUpdateVersion. Перейти в «Настройки» и начать загрузку?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
