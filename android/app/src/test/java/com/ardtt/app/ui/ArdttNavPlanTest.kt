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
    fun userPrimaryKeepsLogsAndSettingsWithoutMore() {
        val primary = ArdttNavPlan.primary(admin = false, testingVisible = false)
        assertEquals(
            listOf(
                AppDestination.Tunnel,
                AppDestination.Profiles,
                AppDestination.Exceptions,
                AppDestination.Logs,
                AppDestination.Settings,
            ),
            primary,
        )
        assertTrue(ArdttNavPlan.overflow(admin = false, testingVisible = true).isEmpty())
        assertFalse(primary.contains(AppDestination.Network))
        assertFalse(primary.contains(AppDestination.Servers))
        assertFalse(primary.contains(AppDestination.Diagnostics))
        assertFalse(primary.contains(AppDestination.Testing))
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
            AppDestination.Diagnostics.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Testing.route, adminPrimary, admin = true),
        )
        assertEquals(
            AppDestination.Servers.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Deploy.route, adminPrimary, admin = true),
        )
        assertEquals(
            AppDestination.Tunnel.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Exceptions.route, adminPrimary, admin = true),
        )
        val userPrimary = ArdttNavPlan.primary(admin = false, testingVisible = true)
        assertEquals(
            AppDestination.Logs.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Testing.route, userPrimary, admin = false),
        )
        assertEquals(
            AppDestination.Exceptions.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Exceptions.route, userPrimary, admin = false),
        )
        assertEquals(
            AppDestination.Settings.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Settings.route, userPrimary, admin = false),
        )
    }

    @Test
    fun recordingBadgeLivesOnParentTab() {
        assertEquals(
            AppDestination.Diagnostics.route,
            ArdttNavPlan.navBadgeRoute(admin = true, isRecording = true),
        )
        assertEquals(
            AppDestination.Logs.route,
            ArdttNavPlan.navBadgeRoute(admin = false, isRecording = true),
        )
        assertNull(ArdttNavPlan.navBadgeRoute(admin = true, isRecording = false))
    }
}
