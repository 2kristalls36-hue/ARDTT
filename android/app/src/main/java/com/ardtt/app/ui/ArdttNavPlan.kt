package com.ardtt.app.ui

/**
 * Bottom bar: exactly five destinations, no overflow «Ещё».
 * Nested routes keep their parent tab selected.
 */
object ArdttNavPlan {
    const val MAX_PRIMARY = 5

    fun visibleDestinations(
        admin: Boolean,
        testingVisible: Boolean,
    ): List<AppDestination> = AppDestination.entries.filter { dest ->
        if (!dest.inBottomNav) return@filter false
        when (dest) {
            // testingVisible must not rebuild the bar; Testing stays a nested route.
            AppDestination.Testing -> false && testingVisible
            AppDestination.Network -> false
            AppDestination.Logs -> !admin
            AppDestination.Exceptions -> !admin
            AppDestination.Diagnostics -> admin
            else -> !dest.adminOnly || admin
        }
    }

    fun primary(
        admin: Boolean,
        testingVisible: Boolean,
    ): List<AppDestination> {
        val all = visibleDestinations(admin, testingVisible).toSet()
        val preferred = if (admin) {
            listOf(
                AppDestination.Tunnel,
                AppDestination.Servers,
                AppDestination.Profiles,
                AppDestination.Diagnostics,
                AppDestination.Settings,
            )
        } else {
            listOf(
                AppDestination.Tunnel,
                AppDestination.Profiles,
                AppDestination.Exceptions,
                AppDestination.Logs,
                AppDestination.Settings,
            )
        }
        return preferred.filter { it in all }
    }

    fun overflow(
        admin: Boolean,
        testingVisible: Boolean,
    ): List<AppDestination> = emptyList()

    fun parentTab(
        currentRoute: String,
        admin: Boolean,
    ): String = when (currentRoute) {
        AppDestination.Deploy.route -> AppDestination.Servers.route
        AppDestination.Network.route,
        AppDestination.Logs.route,
        AppDestination.Testing.route,
        -> if (admin) {
            AppDestination.Diagnostics.route
        } else if (currentRoute == AppDestination.Testing.route) {
            AppDestination.Logs.route
        } else {
            currentRoute
        }
        AppDestination.Exceptions.route -> if (admin) {
            AppDestination.Settings.route
        } else {
            AppDestination.Exceptions.route
        }
        AppDestination.Diagnostics.route -> if (admin) {
            AppDestination.Diagnostics.route
        } else {
            currentRoute
        }
        else -> currentRoute
    }

    fun barSelectedRoute(
        currentRoute: String,
        primary: List<AppDestination>,
        overflow: List<AppDestination> = emptyList(),
        admin: Boolean = primary.any { it.adminOnly },
    ): String {
        val parent = parentTab(currentRoute, admin)
        if (primary.any { it.route == parent }) return parent
        if (primary.any { it.route == currentRoute }) return currentRoute
        if (overflow.any { it.route == currentRoute }) return currentRoute
        return parent
    }

    fun diagnosticsRoute(admin: Boolean): String =
        if (admin) AppDestination.Diagnostics.route else AppDestination.Logs.route

    fun navBadgeRoute(admin: Boolean, isRecording: Boolean): String? {
        if (!isRecording) return null
        return if (admin) AppDestination.Diagnostics.route else AppDestination.Logs.route
    }
}
