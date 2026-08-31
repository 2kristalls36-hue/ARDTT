package com.nonamevpn.app.ui

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.needsNotificationPermission
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.telemetry.TelemetryRecorder
import com.nonamevpn.app.ui.admin.LogsScreen
import com.nonamevpn.app.ui.admin.ServersScreen
import com.nonamevpn.app.ui.admin.TestingScreen
import com.nonamevpn.app.ui.components.AppBackdrop
import com.nonamevpn.app.ui.components.NavBarItem
import com.nonamevpn.app.ui.components.NvpnNavigationBar
import com.nonamevpn.app.ui.exceptions.ExceptionsScreen
import com.nonamevpn.app.ui.profiles.ProfilesScreen
import com.nonamevpn.app.ui.settings.SettingsScreen
import com.nonamevpn.app.ui.telemetry.TelemetryRecordingOverlay
import com.nonamevpn.app.ui.tunnel.TunnelScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppRoot(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    deployEngine: DeployEngine,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val conn = remember { ConnectionManager.get(context) }
    val scope = rememberCoroutineScope()
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val testingMode by settings.testingModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathModeSetting by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val wallpaper by settings.wallpaperFlow.collectAsStateWithLifecycle(initialValue = "none")
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: AppDestination.Tunnel.route
    val recorder = remember { TelemetryRecorder.get(context) }
    val isRecording by recorder.isRecording.collectAsStateWithLifecycle()

    val tabs = AppDestination.entries.filter { dest ->
        if (!dest.inBottomNav) return@filter false
        when (dest) {
            AppDestination.Testing -> admin && testingMode
            else -> !dest.adminOnly || admin
        }
    }
    val navItems = tabs.map { dest ->
        NavBarItem(route = dest.route, label = dest.navLabel, icon = dest.icon())
    }
    val selectedNavRoute = currentRoute
    var vpnConsentBackgroundVisible by remember { mutableStateOf(false) }
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnConsentBackgroundVisible = false
        if (result.resultCode == Activity.RESULT_OK) {
            AppLog.i("VpnPrep", "VPN permission granted")
            conn.connect()
        } else {
            AppLog.w("VpnPrep", "VPN permission denied/cancelled")
            conn.reportUserError("Нужно разрешить VPN в системном диалоге")
        }
    }

    fun launchVpnPrepareOrConnect() {
        scope.launch {
            val prep = runCatching { VpnService.prepare(activity ?: context) }.getOrNull()
            if (prep != null) {
                AppLog.i("VpnPrep", "Launching system VPN consent")
                // Some vendor Android builds render the system VPN consent
                // surface translucent. Paint an opaque app surface first so
                // system text never overlaps the busy tunnel screen.
                vpnConsentBackgroundVisible = true
                delay(100)
                runCatching { vpnPermission.launch(prep) }
                    .onFailure {
                        vpnConsentBackgroundVisible = false
                        conn.reportUserError("Не удалось открыть системное разрешение VPN")
                    }
            } else {
                AppLog.i("VpnPrep", "VPN already permitted — connect")
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
        navController.navigate(route) {
            popUpTo(AppDestination.Tunnel.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(Unit) {
        AppLog.i("App", "UI ready")
    }

    LaunchedEffect(admin) {
        AppLog.setDetailedEnabled(admin)
        AppLog.i("App", if (admin) "Подробные логи (админ)" else "Минимальные логи")
    }

    LaunchedEffect(silent, dial, pathModeSetting) {
        conn.setSilentRecreate(silent)
        conn.setWorkers(3)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
        conn.setPathMode(ConnPathMode.fromSetting(pathModeSetting))
    }

    LaunchedEffect(admin, testingMode, currentRoute) {
        val dest = AppDestination.entries.find { it.route == currentRoute }
        val blocked = when {
            dest == AppDestination.Testing -> !admin || !testingMode
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
        Box(modifier = Modifier.fillMaxSize()) {
            AppBackdrop(
                wallpaperId = wallpaper,
                modifier = Modifier.fillMaxSize(),
            )

            NavHost(
                navController = navController,
                startDestination = AppDestination.Tunnel.route,
                modifier = Modifier.fillMaxSize(),
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
                composable(AppDestination.Logs.route) {
                    LogsScreen()
                }
                composable(AppDestination.Settings.route) {
                    SettingsScreen(settings = settings)
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
        }
    }
}

private fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.Tunnel -> Icons.Outlined.VpnKey
    AppDestination.Servers -> Icons.Outlined.Cloud
    AppDestination.Profiles -> Icons.Outlined.Folder
    AppDestination.Exceptions -> Icons.Outlined.FilterList
    AppDestination.Logs -> Icons.Outlined.Terminal
    AppDestination.Deploy -> Icons.Outlined.CloudUpload
    AppDestination.Settings -> Icons.Outlined.Settings
    AppDestination.Testing -> Icons.Outlined.Science
}
