package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {
    private val self = "com.ardtt.app"
    private val transport = setOf(self, "com.vkontakte.android", "com.vk.calls")

    @Test
    fun blacklistDisallowsSelectedAndTransport() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("com.a", "com.b"),
            selfPackage = self,
        )
        assertEquals(false, plan.whitelistMode)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(transport + setOf("com.a", "com.b"), plan.disallowed)
    }

    @Test
    fun whitelistAllowsSelectedWithoutSelf() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = setOf("com.a"),
            selfPackage = self,
        )
        assertEquals(true, plan.whitelistMode)
        assertTrue(plan.disallowed.isEmpty())
        assertEquals(setOf("com.a"), plan.allowed)
        assertFalse(plan.allowed.contains(self))
    }

    @Test
    fun emptyBlacklistOnlyDisallowsTransport() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = emptySet(),
            selfPackage = self,
        )
        assertEquals(transport, plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun emptyWhitelistFallsBackToFullTunnelMinusTransport() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = emptySet(),
            selfPackage = self,
        )
        assertEquals(false, plan.whitelistMode)
        assertEquals(transport, plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun ignoresSelfBlanksAndVkInUserLists() {
        val plan = SplitTunnel.resolve(
            whitelistMode = false,
            selectedApps = setOf("  com.a  ", "", self, "com.vkontakte.android"),
            selfPackage = "  $self  ",
        )
        assertEquals(transport + setOf("com.a"), plan.disallowed)
    }

    @Test
    fun whitelistDropsTransportPackagesFromAllowed() {
        val plan = SplitTunnel.resolve(
            whitelistMode = true,
            selectedApps = setOf("com.a", "com.vk.calls"),
            selfPackage = self,
        )
        assertEquals(setOf("com.a"), plan.allowed)
    }

    @Test
    fun browserPackagesDetectsChrome() {
        assertEquals(
            listOf("com.android.chrome", "org.mozilla.firefox"),
            SplitTunnel.browserPackages(
                setOf("com.android.chrome", "com.a", "org.mozilla.firefox"),
            ),
        )
        assertTrue(SplitTunnel.isLikelyBrowser("com.android.chrome"))
        assertFalse(SplitTunnel.isLikelyBrowser("com.whatsapp"))
    }

    @Test
    fun logSampleIncludesBrowserHint() {
        val text = SplitTunnel.logSample(
            setOf("com.android.chrome", "com.a", "com.b"),
        )
        assertTrue(text.contains("com.android.chrome"))
        assertTrue(text.contains("browsers=com.android.chrome"))
    }

    @Test
    fun tunFilterFingerprintChangesWithWhitelistMode() {
        val self = "com.ardtt.app"
        val black = SplitTunnel.tunFilterFingerprint(
            whitelistMode = false,
            selectedApps = setOf("com.a"),
            excludedHosts = emptySet(),
            selfPackage = self,
        )
        val white = SplitTunnel.tunFilterFingerprint(
            whitelistMode = true,
            selectedApps = setOf("com.a"),
            excludedHosts = emptySet(),
            selfPackage = self,
        )
        val whiteHost = SplitTunnel.tunFilterFingerprint(
            whitelistMode = true,
            selectedApps = setOf("com.a"),
            excludedHosts = setOf("example.com"),
            selfPackage = self,
        )
        assertTrue(black != white)
        assertTrue(white != whiteHost)
        assertTrue(white.contains("wl=true"))
        assertTrue(white.contains("allow=com.a"))
    }
}
