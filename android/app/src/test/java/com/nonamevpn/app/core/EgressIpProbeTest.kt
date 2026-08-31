package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressIpProbeTest {
    @Test
    fun usesIpifyAsPrimaryExternalAddressService() {
        assertEquals("https://api.ipify.org/", EgressIpProbe.PRIMARY_ENDPOINT)
        assertEquals(EgressIpProbe.PRIMARY_ENDPOINT, EgressIpProbe.endpoints.first())
    }

    @Test
    fun acceptsIpv4AndIpv6() {
        assertTrue(EgressIpProbe.looksLikeIp("8.8.8.8"))
        assertTrue(EgressIpProbe.looksLikeIp("2001:4860:4860::8888"))
        assertFalse(EgressIpProbe.looksLikeIp(""))
        assertFalse(EgressIpProbe.looksLikeIp("<html>nope</html>"))
        assertFalse(EgressIpProbe.looksLikeIp("not an ip"))
    }

    @Test
    fun detectsLikelyCloudflareIpv4() {
        assertTrue(EgressIpProbe.isLikelyCloudflare("104.16.132.229"))
        assertTrue(EgressIpProbe.isLikelyCloudflare("104.28.228.110"))
        assertTrue(EgressIpProbe.isLikelyCloudflare("172.64.0.1"))
        assertFalse(EgressIpProbe.isLikelyCloudflare("8.8.8.8"))
    }

    @Test
    fun egressLabelWaitsForPublicIp() {
        assertEquals("—", vpnEgressIpLabel(null, vpnSessionActive = false))
        assertEquals(
            VPN_EGRESS_CONNECTING_LABEL,
            vpnEgressIpLabel(null, vpnSessionActive = true),
        )
        assertEquals(
            VPN_EGRESS_CONNECTING_LABEL,
            vpnEgressIpLabel("  ", vpnSessionActive = true),
        )
        assertEquals("8.8.8.8", vpnEgressIpLabel("8.8.8.8", vpnSessionActive = true))
        assertEquals("—", vpnEgressIpLabel(null, vpnSessionActive = true, pausedOnTrustedWifi = true))
    }

    @Test
    fun connectedWithoutPublicIpIsStillConnected() {
        assertEquals(
            "Подключено: прямое",
            vpnSessionStatusText(ConnState.Connected, "Подключено: прямое", publicIp = null),
        )
        assertEquals(
            "Подключение (прямое)…",
            vpnSessionStatusText(ConnState.Connecting, "Подключение (прямое)…", publicIp = null),
        )
        assertEquals(
            "Подключено: обход",
            vpnSessionStatusText(ConnState.Connected, "Подключено: обход", publicIp = "1.1.1.1"),
        )
    }
}
