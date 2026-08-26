package com.nonamevpn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nonamevpn.app.bypass.DialPath
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.admin.DeployScreen
import com.nonamevpn.app.ui.admin.LogsScreen
import com.nonamevpn.app.ui.admin.ServersScreen
import com.nonamevpn.app.ui.components.NavBarItem
import com.nonamevpn.app.ui.components.NvpnNavigationBar
import com.nonamevpn.app.ui.settings.SettingsScreen
import com.nonamevpn.app.ui.tunnel.TunnelScreen

@Composable
fun AppRoot(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    deployEngine: DeployEngine,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val silent by settings.silentRecreateEnabled.collectAsStateWithLifecycle(initialValue = false)
    val economy by settings.economyWorkersEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dial by settings.dialPathName.collectAsStateWithLifecycle(initialValue = "auto")
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: AppDestination.Tunnel.route
    var deployInitial by remember { mutableStateOf<DeployTarget?>(null) }

    val tabs = AppDestination.entries.filter { !it.adminOnly || admin }
    val navItems = tabs.map { dest ->
        NavBarItem(route = dest.route, label = dest.label, icon = dest.icon())
    }

    LaunchedEffect(silent, economy, dial) {
        conn.setSilentRecreate(silent)
        conn.setWorkers(if (economy) 1 else 3)
        conn.setDialPath(
            when (dial) {
                "vkcalls" -> DialPath.VkCalls
                "legacy" -> DialPath.Legacy
                else -> DialPath.Auto
            },
        )
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

    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        bg,
                        androidx.compose.ui.graphics.lerp(bg, surface, 0.55f),
                        bg,
                    ),
                ),
            ),
    ) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.Tunnel.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
        ) {
            composable(AppDestination.Tunnel.route) {
                TunnelScreen(settings = settings, profiles = profiles)
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(settings = settings)
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
            composable(AppDestination.Deploy.route) {
                DeployScreen(
                    serversRepo = serversRepo,
                    engine = deployEngine,
                    initial = deployInitial,
                )
            }
            composable(AppDestination.Logs.route) {
                LogsScreen()
            }
        }

        NvpnNavigationBar(
            items = navItems,
            selectedRoute = currentRoute,
            onSelect = { route ->
                navController.navigate(route) {
                    popUpTo(AppDestination.Tunnel.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.Tunnel -> Icons.Outlined.VpnKey
    AppDestination.Settings -> Icons.Outlined.Settings
    AppDestination.Servers -> Icons.Outlined.Dns
    AppDestination.Deploy -> Icons.Outlined.CloudUpload
    AppDestination.Logs -> Icons.Outlined.ListAlt
}
