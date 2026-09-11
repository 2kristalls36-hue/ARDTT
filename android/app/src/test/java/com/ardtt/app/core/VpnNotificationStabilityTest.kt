package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnNotificationStabilityTest {
    @Test
    fun fingerprintStableWhenRatesUnchanged() {
        val a = vpnNotificationFingerprint(
            showInShade = true,
            title = "Прямое подключение",
            ip = "1.2.3.4",
            rates = "↓1 Кб/с  ↑2 Кб/с",
            statusText = null,
            showWarpIcon = true,
            showWhitelistIcon = false,
            sessionStartedAtMs = 100L,
            trustedWifiWaiting = false,
        )
        val b = vpnNotificationFingerprint(
            showInShade = true,
            title = "Прямое подключение",
            ip = "1.2.3.4",
            rates = "↓1 Кб/с  ↑2 Кб/с",
            statusText = null,
            showWarpIcon = true,
            showWhitelistIcon = false,
            sessionStartedAtMs = 100L,
            trustedWifiWaiting = false,
        )
        assertEquals(a, b)
        assertFalse(vpnNotificationShouldRepost(a, b, force = false))
    }

    @Test
    fun fingerprintChangesWhenRatesChange() {
        val a = vpnNotificationFingerprint(
            showInShade = true,
            title = "Прямое подключение",
            ip = "1.2.3.4",
            rates = "↓1 Кб/с  ↑2 Кб/с",
        )
        val b = vpnNotificationFingerprint(
            showInShade = true,
            title = "Прямое подключение",
            ip = "1.2.3.4",
            rates = "↓9 Кб/с  ↑2 Кб/с",
        )
        assertTrue(vpnNotificationShouldRepost(a, b, force = false))
    }

    @Test
    fun minModeHasSingleFingerprint() {
        assertEquals(
            "min",
            vpnNotificationFingerprint(showInShade = false, title = "x", rates = "y"),
        )
        assertFalse(
            vpnNotificationShouldRepost(
                previousFingerprint = "min",
                nextFingerprint = vpnNotificationFingerprint(showInShade = false),
                force = false,
            ),
        )
    }

    @Test
    fun forceAlwaysReposts() {
        assertTrue(vpnNotificationShouldRepost("same", "same", force = true))
    }

    @Test
    fun channelIdsAreStable() {
        assertEquals("ardtt_vpn_shade_v6", VpnNotificationChannels.id(showInShade = true))
        assertEquals("ardtt_vpn_min_v6", VpnNotificationChannels.id(showInShade = false))
    }
}
