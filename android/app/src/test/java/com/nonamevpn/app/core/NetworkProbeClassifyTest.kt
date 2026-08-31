package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeClassifyTest {
    @Test
    fun vpsIpUnlocksDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DirectOk, r.networkClass)
        assertTrue(r.message.contains("прямое"))
    }

    @Test
    fun vpsIpUnlocksDirectEvenWithoutYandex() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DirectOk, r.networkClass)
    }

    @Test
    fun yandexWithoutVpsMeansBypass() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = false,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
    }

    @Test
    fun neitherYandexNorVpsIsNoNetwork() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = false,
            bigtechOk = true,
            captive = false,
            awgUdpOk = false,
            provisionOk = false,
        )
        assertNull(r.preselectedPath)
        assertEquals(NetworkClass.NoNetwork, r.networkClass)
    }

    @Test
    fun captiveBlocksConnect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = true,
            captive = true,
            awgUdpOk = true,
            provisionOk = true,
        )
        assertNull(r.preselectedPath)
        assertEquals(NetworkClass.Captive, r.networkClass)
    }

    @Test
    fun vpsProbeTargetUsesProvisionIpAndPort() {
        val t = NetworkProbe.vpsProbeTarget("http://159.194.225.162:9100", "10.0.0.1:51820")
        assertEquals("159.194.225.162" to 9100, t)
    }

    @Test
    fun vpsProbeTargetDefaultsPortAndFallsBackToDirectHost() {
        assertEquals(
            "159.194.225.162" to 9100,
            NetworkProbe.vpsProbeTarget("http://159.194.225.162", null),
        )
        assertEquals(
            "203.0.113.9" to 9100,
            NetworkProbe.vpsProbeTarget(null, "203.0.113.9:51820"),
        )
        assertNull(NetworkProbe.vpsProbeTarget(null, null))
    }

    @Test
    fun numericIpv4SkipsDns() {
        val addr = NetworkProbe.numericIpv4("77.88.8.8")
        assertEquals("77.88.8.8", addr?.hostAddress)
        assertNull(NetworkProbe.numericIpv4("yandex.ru"))
        assertNull(NetworkProbe.numericIpv4("256.0.0.1"))
    }

    @Test
    fun dnsQueryIsWellFormed() {
        val q = NetworkProbe.buildDnsQuery("ya.ru")
        assertTrue(q.size > 16)
        assertEquals(0x12.toByte(), q[0])
        assertEquals(1.toByte(), q[5])
        val encoded = NetworkProbe.encodeDnsName("ya.ru")
        assertEquals(1 + 2 + 1 + 2 + 1, encoded.size)
        assertEquals(2.toByte(), encoded[0])
        assertEquals('y'.code.toByte(), encoded[1])
    }
}
