package com.nonamevpn.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(AppDestination.Tunnel.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon(), contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Tunnel.route,
            modifier = Modifier.padding(padding),
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
    }
}

private fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.Tunnel -> Icons.Outlined.VpnKey
    AppDestination.Settings -> Icons.Outlined.Settings
    AppDestination.Servers -> Icons.Outlined.Dns
    AppDestination.Deploy -> Icons.Outlined.CloudUpload
    AppDestination.Logs -> Icons.Outlined.ListAlt
}
