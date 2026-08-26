package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeClassifyTest {
    @Test
    fun provisionHealthUnlocksDirectWhenUdpSilent() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = true,
            captive = false,
            vpsUdpOk = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DirectOk, r.networkClass)
        assertTrue(r.message.contains("прямое"))
    }

    @Test
    fun openNetworkWithoutVpsFallsBackToBypass() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = true,
            captive = false,
            vpsUdpOk = false,
            provisionOk = false,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.OpenNeedBypass, r.networkClass)
    }

    @Test
    fun udpReplyStillDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            vpsUdpOk = true,
            provisionOk = false,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
    }

    @Test
    fun captiveBlocksConnect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = true,
            captive = true,
            vpsUdpOk = true,
            provisionOk = true,
        )
        assertNull(r.preselectedPath)
        assertEquals(NetworkClass.Captive, r.networkClass)
    }
}
