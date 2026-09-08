package com.ardtt.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttNavPlanTest {
    @Test
    fun adminKeepsFourPrimaryTabsAndOverflow() {
        val primary = ArdttNavPlan.primary(admin = true, testingVisible = true)
        assertEquals(
            listOf(
                AppDestination.Tunnel,
                AppDestination.Network,
                AppDestination.Servers,
                AppDestination.Profiles,
            ),
            primary,
        )
        val overflow = ArdttNavPlan.overflow(admin = true, testingVisible = true)
        assertTrue(overflow.contains(AppDestination.Exceptions))
        assertTrue(overflow.contains(AppDestination.Logs))
        assertTrue(overflow.contains(AppDestination.Settings))
        assertTrue(overflow.contains(AppDestination.Testing))
        assertFalse(overflow.contains(AppDestination.Deploy))
        assertTrue(primary.size <= ArdttNavPlan.MAX_PRIMARY)
    }

    @Test
    fun userModePrimaryDoesNotIncludeAdminTabs() {
        val primary = ArdttNavPlan.primary(admin = false, testingVisible = false)
        assertEquals(
            listOf(
                AppDestination.Tunnel,
                AppDestination.Profiles,
                AppDestination.Exceptions,
                AppDestination.Logs,
            ),
            primary,
        )
        val overflow = ArdttNavPlan.overflow(admin = false, testingVisible = false)
        assertEquals(listOf(AppDestination.Settings), overflow)
        assertFalse(primary.contains(AppDestination.Network))
        assertFalse(primary.contains(AppDestination.Servers))
    }

    @Test
    fun moreRouteStaysSelectedForOverflowDestinations() {
        val primary = ArdttNavPlan.primary(admin = true, testingVisible = true)
        val overflow = ArdttNavPlan.overflow(admin = true, testingVisible = true)
        assertEquals(
            AppDestination.Tunnel.route,
            ArdttNavPlan.barSelectedRoute(AppDestination.Tunnel.route, primary, overflow),
        )
        assertEquals(
            ArdttNavPlan.MORE_ROUTE,
            ArdttNavPlan.barSelectedRoute(AppDestination.Settings.route, primary, overflow),
        )
        assertTrue(ArdttNavPlan.moreIsSelected(AppDestination.Testing.route, overflow))
        assertFalse(ArdttNavPlan.moreIsSelected(AppDestination.Tunnel.route, overflow))
    }
}
