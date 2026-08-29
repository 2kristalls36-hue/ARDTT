package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {
    private val self = "com.nonamevpn.app"

    @Test
    fun blacklistDisallowsSelectedAndSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("com.a", "com.b"),
            selfPackage = self,
        )
        assertEquals(false, plan.whitelistMode)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(setOf("com.a", "com.b", self), plan.disallowed)
    }

    @Test
    fun whitelistAllowsSelectedAndSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = setOf("com.a"),
            selfPackage = self,
        )
        assertEquals(true, plan.whitelistMode)
        assertTrue(plan.disallowed.isEmpty())
        assertEquals(setOf("com.a", self), plan.allowed)
    }

    @Test
    fun emptyBlacklistOnlyDisallowsSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = emptySet(),
            selfPackage = self,
        )
        assertEquals(setOf(self), plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun emptyWhitelistFallsBackToFullTunnel() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = emptySet(),
            selfPackage = self,
        )
        assertEquals(false, plan.whitelistMode)
        assertEquals(setOf(self), plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun ignoresSelfAndBlanksInUserLists() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("  com.a  ", "", self),
            selfPackage = "  $self  ",
        )
        assertEquals(setOf("com.a", self), plan.disallowed)
    }
}
