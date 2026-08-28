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
        assertTrue(EgressIpProbe.isLikelyCloudflare("172.64.0.1"))
        assertFalse(EgressIpProbe.isLikelyCloudflare("8.8.8.8"))
    }
}
