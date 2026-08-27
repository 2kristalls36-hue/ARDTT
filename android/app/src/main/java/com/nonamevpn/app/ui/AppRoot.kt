package com.nonamevpn.app.ui

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.admin.DeployScreen
import com.nonamevpn.app.ui.admin.LogsScreen
import com.nonamevpn.app.ui.admin.ServersScreen
import com.nonamevpn.app.ui.components.AppBackdrop
import com.nonamevpn.app.ui.components.NavBarItem
import com.nonamevpn.app.ui.components.NvpnNavigationBar
import com.nonamevpn.app.ui.exceptions.ExceptionsScreen
import com.nonamevpn.app.ui.profiles.ProfilesScreen
import com.nonamevpn.app.ui.settings.SettingsScreen
import com.nonamevpn.app.ui.tunnel.TunnelScreen
import kotlin.math.abs
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
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val economy by settings.economyWorkersEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val pathModeSetting by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: AppDestination.Tunnel.route
    var deployInitial by remember { mutableStateOf<DeployTarget?>(null) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val tabs = AppDestination.entries.filter {
        it.inBottomNav && (!it.adminOnly || admin)
    }
    val navItems = tabs.map { dest ->
        NavBarItem(route = dest.route, label = dest.label, icon = dest.icon())
    }
    val selectedNavRoute = when (currentRoute) {
        AppDestination.Settings.route -> AppDestination.Tunnel.route
        AppDestination.Deploy.route -> AppDestination.Servers.route
        else -> currentRoute
    }
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
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
                vpnPermission.launch(prep)
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

    LaunchedEffect(silent, economy, dial, pathModeSetting) {
        conn.setSilentRecreate(silent)
        conn.setWorkers(if (economy) 1 else 3)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
        conn.setPathMode(ConnPathMode.fromSetting(pathModeSetting))
    }

    LaunchedEffect(admin, currentRoute) {
        val dest = AppDestination.entries.find { it.route == currentRoute }
        if (!admin && dest?.adminOnly == true) {
            navController.navigate(AppDestination.Tunnel.route) {
                popUpTo(AppDestination.Tunnel.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedNavRoute, tabs) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDrag = 0f
                            dragTargetIndex = -1
                            dragProgress = 0f
                        },
                        onDragCancel = {
                            dragTargetIndex = -1
                            dragProgress = 0f
                        },
                        onDragEnd = {
                            if (dragTargetIndex in tabs.indices && dragProgress >= 0.5f) {
                                navigateTab(tabs[dragTargetIndex].route)
                            }
                            dragTargetIndex = -1
                            dragProgress = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        if (abs(totalDrag) < 12f) {
                            dragTargetIndex = -1
                            dragProgress = 0f
                            return@detectHorizontalDragGestures
                        }
                        val currentIndex = tabs.indexOfFirst { it.route == selectedNavRoute }
                        val candidate = if (totalDrag < 0f) currentIndex + 1 else currentIndex - 1
                        if (candidate !in tabs.indices) {
                            dragTargetIndex = -1
                            dragProgress = 0f
                            return@detectHorizontalDragGestures
                        }
                        dragTargetIndex = candidate
                        dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                    }
                },
        ) {
            NavHost(
                navController = navController,
                startDestination = AppDestination.Tunnel.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 88.dp),
            ) {
                composable(AppDestination.Tunnel.route) {
                    TunnelScreen(
                        settings = settings,
                        profiles = profiles,
                        onRequestConnect = { requestVpnThenConnect() },
                        onOpenSettings = {
                            navController.navigate(AppDestination.Settings.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(AppDestination.Servers.route) {
                    ServersScreen(
                        serversRepo = serversRepo,
                        onDeploy = { target ->
                            deployInitial = target
                            navController.navigate(AppDestination.Deploy.route) {
                                launchSingleTop = true
                            }
                        },
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
                    SettingsScreen(
                        settings = settings,
                        onBack = {
                            navController.popBackStack(
                                AppDestination.Tunnel.route,
                                inclusive = false,
                            )
                        },
                    )
                }
                composable(AppDestination.Deploy.route) {
                    DeployScreen(
                        serversRepo = serversRepo,
                        engine = deployEngine,
                        initial = deployInitial,
                    )
                }
            }

            NvpnNavigationBar(
                items = navItems,
                selectedRoute = selectedNavRoute,
                onSelect = { route -> navigateTab(route) },
                dragTargetIndex = dragTargetIndex,
                dragProgress = dragProgress,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
}
