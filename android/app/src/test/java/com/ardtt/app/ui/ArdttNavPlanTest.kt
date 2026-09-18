package com.ardtt.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttNavPlanTest {
    @Test
    fun adminPrimaryIsFiveTabsWithoutOverflow() {
        val primary = ArdttNavPlan.primary(admin = true, testingVisible = true)
        assertEquals(
            listOf(
                AppDestination.Tunnel,
                AppDestination.Servers,
                AppDestination.Profiles,
                AppDestination.Diagnostics,
                AppDestination.Settings,
            ),
            primary,
        )
        assertTrue(ArdttNavPlan.overflow(admin = true, testingVisible = true).isEmpty())
        assertTrue(primary.size <= ArdttNavPlan.MAX_PRIMARY)
        assertFalse(primary.any { it.navLabel == "Ещё" || it.label == "Ещё" })
        assertEquals("Серверы", AppDestination.Servers.navLabel)
        assertEquals("Настройки", AppDestination.Settings.navLabel)
        assertFalse(primary.contains(AppDestination.Network))
        assertFalse(primary.contains(AppDestination.Logs))
        assertFalse(primary.contains(AppDestination.Testing))
        assertFalse(primary.contains(AppDestination.Exceptions))
    }

    @Test
    fun userPrimaryShowsNetworkMapNotLogs() {
        val primary = ArdttNavPlan.primary(admin = false, testingVisible = false)
        assertEquals(
            listOf(
                AppDestination.Tunnel,
                AppDestination.Profiles,
                AppDestination.Exceptions,
                AppDestination.Network,
                AppDestination.Settings,
            ),
            primary,
        )
        assertTrue(ArdttNavPlan.overflow(admin = false, testingVisible = true).isEmpty())
        assertFalse(primary.contains(AppDestination.Logs))
        assertFalse(primary.contains(AppDestination.Servers))
        assertFalse(primary.contains(AppDestination.Diagnostics))
        assertFalse(primary.contains(AppDestination.Testing))
        assertFalse(AppDestination.Network.adminOnly)
        assertTrue(AppDestination.Network.inBottomNav)
        assertFalse(AppDestination.Logs.inBottomNav)
    }

    @Test
    fun testingVisibilityDoesNotRebuildAdminTabs() {
        val withTesting = ArdttNavPlan.primary(admin = true, testingVisible = true)
        val withoutTesting = ArdttNavPlan.primary(admin = true, testingVisible = false)
        assertEquals(withTesting, withoutTesting)
    }

    @Test
    fun nestedRoutesSelectParentTab() {
        val adminPrimary = ArdttNavPlan.primary(admin = true, testingVisible = true)
        assertEquals(
            AppDestination.Diagnostics.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Logs.route, adminPrimary, admin = true),
        )
        assertEquals(
            AppDestination.Diagnostics.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Network.route, adminPrimary, admin = true),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Testing.route, adminPrimary, admin = true),
        )
        assertEquals(
            AppDestination.Servers.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Deploy.route, adminPrimary, admin = true),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Exceptions.route, adminPrimary, admin = true),
        )
        val userPrimary = ArdttNavPlan.primary(admin = false, testingVisible = true)
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Testing.route, userPrimary, admin = false),
        )
        assertEquals(
            AppDestination.Exceptions.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Exceptions.route, userPrimary, admin = false),
        )
        assertEquals(
            AppDestination.Network.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Network.route, userPrimary, admin = false),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Settings.route, userPrimary, admin = false),
        )
    }

    @Test
    fun systemBackFromNestedRouteReturnsToParentTab() {
        assertEquals(
            AppDestination.Diagnostics.route,
            ArdttNavPlan.backTarget(AppDestination.Network.route, admin = true),
        )
        assertEquals(
            AppDestination.Diagnostics.route,
            ArdttNavPlan.backTarget(AppDestination.Logs.route, admin = true),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.backTarget(AppDestination.Testing.route, admin = true),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.backTarget(AppDestination.Exceptions.route, admin = true),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.backTarget(AppDestination.Testing.route, admin = false),
        )
        assertNull(ArdttNavPlan.backTarget(AppDestination.Network.route, admin = false))
    }

    @Test
    fun systemBackFromRootTabKeepsDefaultBackStack() {
        for (dest in ArdttNavPlan.primary(admin = true, testingVisible = true)) {
            assertNull(dest.route, ArdttNavPlan.backTarget(dest.route, admin = true))
        }
        for (dest in ArdttNavPlan.primary(admin = false, testingVisible = true)) {
            assertNull(dest.route, ArdttNavPlan.backTarget(dest.route, admin = false))
        }
    }

    @Test
    fun recordingBadgeLivesOnParentTab() {
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.navBadgeRoute(admin = true, isRecording = true),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.navBadgeRoute(admin = false, isRecording = true),
        )
        assertNull(ArdttNavPlan.navBadgeRoute(admin = true, isRecording = false))
    }

    @Test
    fun userLogsRouteOpensNetworkMap() {
        assertEquals(
            AppDestination.Network.route,
            ArdttNavPlan.replaceUnavailableUserRoute(AppDestination.Logs.route, admin = false),
        )
        assertNull(ArdttNavPlan.replaceUnavailableUserRoute(AppDestination.Logs.route, admin = true))
        assertNull(ArdttNavPlan.replaceUnavailableUserRoute(AppDestination.Network.route, admin = false))
    }
}
