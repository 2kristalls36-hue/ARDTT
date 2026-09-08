package com.ardtt.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom bar: at most five items. Remaining destinations live under «Ещё».
 */
object ArdttNavPlan {
    const val MORE_ROUTE = "more"
    const val MORE_LABEL = "Ещё"
    const val MAX_PRIMARY = 4

    val moreIcon: ImageVector = Icons.Outlined.MoreHoriz

    fun visibleDestinations(
        admin: Boolean,
        testingVisible: Boolean,
    ): List<AppDestination> = AppDestination.entries.filter { dest ->
        if (!dest.inBottomNav) return@filter false
        when (dest) {
            AppDestination.Testing -> testingVisible
            else -> !dest.adminOnly || admin
        }
    }

    fun primary(
        admin: Boolean,
        testingVisible: Boolean,
    ): List<AppDestination> {
        val all = visibleDestinations(admin, testingVisible)
        val preferred = if (admin) {
            listOf(
                AppDestination.Tunnel,
                AppDestination.Network,
                AppDestination.Servers,
                AppDestination.Profiles,
            )
        } else {
            listOf(
                AppDestination.Tunnel,
                AppDestination.Profiles,
                AppDestination.Exceptions,
                AppDestination.Logs,
            )
        }
        return preferred.filter { it in all }.take(MAX_PRIMARY)
    }

    fun overflow(
        admin: Boolean,
        testingVisible: Boolean,
    ): List<AppDestination> {
        val all = visibleDestinations(admin, testingVisible)
        val shown = primary(admin, testingVisible).toSet()
        return all.filter { it !in shown }
    }

    fun barSelectedRoute(
        currentRoute: String,
        primary: List<AppDestination>,
        overflow: List<AppDestination>,
    ): String {
        if (primary.any { it.route == currentRoute }) return currentRoute
        if (overflow.any { it.route == currentRoute }) return MORE_ROUTE
        return currentRoute
    }

    fun moreIsSelected(currentRoute: String, overflow: List<AppDestination>): Boolean =
        overflow.any { it.route == currentRoute }
}
