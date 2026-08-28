package com.nonamevpn.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeAwgTest {
    @Test
    fun tcpHealthAloneDoesNotUnlockDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = true,
            captive = false,
            awgUdpOk = false,
            provisionOk = true,
        )
        assertTrue(r.preselectedPath == VpnPath.Bypass)
        assertFalse(r.networkClass == NetworkClass.DirectOk)
    }

    @Test
    fun parseEndpointSplitsHostPort() {
        val parsed = NetworkProbe.parseEndpoint("159.194.225.162:51820")
        assertTrue(parsed != null)
        assertTrue(parsed!!.first == "159.194.225.162")
        assertTrue(parsed.second == 51820)
    }
}
