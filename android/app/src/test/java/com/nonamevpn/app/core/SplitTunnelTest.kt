package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {
    private val self = "com.nonamevpn.app"

    @Test
    fun blacklistKeepsSelectedOutUnlessWarpOverrides() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("com.a", "com.b"),
            warpApps = setOf("com.b"),
            selfPackage = self,
        )
        assertEquals(false, plan.whitelistMode)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(setOf("com.a", self), plan.disallowed)
    }

    @Test
    fun whitelistUnionsSelectedAndWarpAndSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = setOf("com.a"),
            warpApps = setOf("com.b"),
            selfPackage = self,
        )
        assertEquals(true, plan.whitelistMode)
        assertTrue(plan.disallowed.isEmpty())
        assertEquals(setOf("com.a", "com.b", self), plan.allowed)
    }

    @Test
    fun emptyBlacklistOnlyDisallowsSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = emptySet(),
            warpApps = emptySet(),
            selfPackage = self,
        )
        assertEquals(setOf(self), plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun emptyWhitelistOnlyAllowsSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = emptySet(),
            warpApps = emptySet(),
            selfPackage = self,
        )
        assertEquals(setOf(self), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun warpOnlyWhitelistTunnelsThoseApps() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = emptySet(),
            warpApps = setOf("com.maps"),
            selfPackage = self,
        )
        assertEquals(setOf("com.maps", self), plan.allowed)
    }

    @Test
    fun ignoresSelfAndBlanksInUserLists() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("  com.a  ", "", self),
            warpApps = setOf(self, "  "),
            selfPackage = "  $self  ",
        )
        assertEquals(setOf("com.a", self), plan.disallowed)
    }

    @Test
    fun warpWinsOverBlacklistForEveryMarkedApp() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("com.a", "com.b", "com.c"),
            warpApps = setOf("com.a", "com.c"),
            selfPackage = self,
        )
        assertEquals(setOf("com.b", self), plan.disallowed)
    }
}
