package com.nonamevpn.app.ui

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
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.needsNotificationPermission
import com.nonamevpn.app.core.vpnPermissionDeniedHint
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.telemetry.TelemetryRecorder
import com.nonamevpn.app.ui.admin.LogsScreen
import com.nonamevpn.app.ui.admin.NetworkScreen
import com.nonamevpn.app.ui.admin.ServersScreen
import com.nonamevpn.app.ui.admin.TestingScreen
import com.nonamevpn.app.ui.components.AppBackdrop
import com.nonamevpn.app.ui.components.NavBarItem
import com.nonamevpn.app.ui.components.LocalOpaqueSectionCards
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import com.nonamevpn.app.ui.components.NvpnNavigationBar
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.ui.components.rememberSmartHaptics
import com.nonamevpn.app.ui.exceptions.ExceptionsScreen
import com.nonamevpn.app.ui.profiles.ProfilesScreen
import com.nonamevpn.app.ui.settings.SettingsScreen
import com.nonamevpn.app.ui.telemetry.TelemetryRecordingOverlay
import com.nonamevpn.app.ui.tunnel.TunnelScreen
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperBackdrop
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperCache
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperSession
import com.nonamevpn.app.ui.tunnel.resolveTunnelWallpaper
import com.nonamevpn.app.ui.tunnel.tunnelWallpaperVisible
import com.nonamevpn.app.ui.tunnel.wallpaperBypassActive
import com.nonamevpn.app.ui.unlock.AlphaUnlockScreen
import com.nonamevpn.app.ui.theme.wallpaperAdaptedColorScheme
import com.nonamevpn.app.update.AppUpdateController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun AppRoot(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    deployEngine: DeployEngine,
) {
    val context = LocalContext.current
    var alphaUnlocked by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(settings) {
        settings.alphaUnlockedFlow.collect { alphaUnlocked = it }
    }
    when (alphaUnlocked) {
        null -> {
            Box(modifier = Modifier.fillMaxSize()) {
                AppBackdrop(modifier = Modifier.fillMaxSize())
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return
        }
        false -> {
            AlphaUnlockScreen(settings = settings)
            return
        }
        true -> Unit
    }
    val activity = context as? Activity
    val conn = remember { ConnectionManager.get(context) }
    val scope = rememberCoroutineScope()
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val classicAppearance by settings.classicAppearanceEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dynamicColors by settings.dynamicColorsFlow.collectAsStateWithLifecycle(initialValue = true)
    val uiHapticsEnabled by settings.uiHapticsEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val haptics = rememberSmartHaptics(uiHapticsEnabled)
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
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
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

    val tabs = AppDestination.entries.filter { dest ->
        if (!dest.inBottomNav) return@filter false
        when (dest) {
            AppDestination.Testing ->
                TestingSessionGuard.testingTabVisible(admin, testingMode, isRecording)
            else -> !dest.adminOnly || admin
        }
    }
    val navItems = tabs.map { dest ->
        NavBarItem(route = dest.route, label = dest.navLabel, icon = dest.navIcon())
    }
    val tabReselectSignal = remember { mutableStateMapOf<String, Int>() }
    tabs.forEach { tab ->
        if (tab.route !in tabReselectSignal) {
            tabReselectSignal[tab.route] = 0
        }
    }
    val selectedNavRoute = currentRoute
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
                !TestingSessionGuard.testingTabVisible(admin, testingMode, isRecording)
            dest?.adminOnly == true -> !admin
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
            CompositionLocalProvider(LocalOpaqueSectionCards provides showUserWallpaper) {
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
                        AppBackdrop(modifier = Modifier.fillMaxSize())
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
                                onRequestConnect = { requestVpnThenConnect() },
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
                                onApplied = { navigateTab(AppDestination.Tunnel.route) },
                            )
                        }
                        composable(AppDestination.Exceptions.route) {
                            ExceptionsScreen(settings = settings)
                        }
                        composable(AppDestination.Network.route) {
                            NetworkScreen(settings = settings, profiles = profiles)
                        }
                        composable(AppDestination.Logs.route) {
                            LogsScreen()
                        }
                        composable(AppDestination.Settings.route) {
                            SettingsScreen(
                                settings = settings,
                                isRecording = isRecording,
                            )
                        }
                        composable(AppDestination.Testing.route) {
                            TestingScreen(profiles = profiles)
                        }
                    }

                    NvpnNavigationBar(
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
                        NvpnDialog(
                            title = "Доступно обновление",
                            onDismissRequest = {
                                dismissedUpdateVersion = availableUpdateVersion
                            },
                            confirmAction = NvpnDialogAction(
                                text = "Загрузить",
                                onClick = {
                                    dismissedUpdateVersion = availableUpdateVersion
                                    PendingUiAction.requestOpenUpdateDownload()
                                    navigateTab(AppDestination.Settings.route)
                                },
                            ),
                            dismissAction = NvpnDialogAction(
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
