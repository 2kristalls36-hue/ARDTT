package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideIpPolicyTest {
    @Test
    fun queuesHideIpUntilTunnelOnWhitelist() {
        assertEquals(
            HideIpDispatch.QueueUntilTunnel,
            decideHideIpDispatch(ConnState.Ready, provisionOnlyViaVpn = true),
        )
        assertEquals(
            HideIpDispatch.QueueUntilTunnel,
            decideHideIpDispatch(ConnState.Idle, provisionOnlyViaVpn = true),
        )
        assertEquals(
            HideIpDispatch.QueueUntilTunnel,
            decideHideIpDispatch(ConnState.Connecting, provisionOnlyViaVpn = true),
        )
    }

    @Test
    fun usesVpnOnlyOnceConnectedOnWhitelist() {
        assertEquals(
            HideIpDispatch.ViaVpn,
            decideHideIpDispatch(ConnState.Connected, provisionOnlyViaVpn = true),
        )
    }

    @Test
    fun usesUnderlayOnOpenInternetEvenWhenIdle() {
        assertEquals(
            HideIpDispatch.Underlay,
            decideHideIpDispatch(ConnState.Ready, provisionOnlyViaVpn = false),
        )
        assertEquals(
            HideIpDispatch.Underlay,
            decideHideIpDispatch(ConnState.Connected, provisionOnlyViaVpn = false),
        )
    }

    @Test
    fun bypassWarmingOnlyWhileConnectingBypass() {
        assertTrue(isBypassWarming(ConnState.Connecting, VpnPath.Bypass))
        assertFalse(isBypassWarming(ConnState.Connected, VpnPath.Bypass))
        assertFalse(isBypassWarming(ConnState.Connecting, VpnPath.Direct))
        assertFalse(isBypassWarming(ConnState.Ready, VpnPath.Bypass))
    }

    @Test
    fun retriesHideIpOffAfterFailedSync() {
        assertTrue(hideIpShouldRetryAfterTunnel(lastSent = true, want = false, pending = false))
        assertTrue(hideIpShouldRetryAfterTunnel(lastSent = null, want = false, pending = false))
        assertTrue(hideIpShouldRetryAfterTunnel(lastSent = false, want = false, pending = true))
        assertFalse(hideIpShouldRetryAfterTunnel(lastSent = false, want = false, pending = false))
        assertFalse(hideIpShouldRetryAfterTunnel(lastSent = true, want = true, pending = false))
    }

    @Test
    fun hideIpToggleDoesNotRestartTheTunnel() {
        assertFalse(hideIpShouldRestartTransport())
    }

    @Test
    fun appIconDecodeSizeCapsLongestEdge() {
        assertEquals(96 to 48, appIconDecodeSize(192, 96, maxPx = 96))
        assertEquals(64 to 64, appIconDecodeSize(64, 64, maxPx = 96))
        assertEquals(96 to 96, appIconDecodeSize(0, 0, maxPx = 96))
    }
}
