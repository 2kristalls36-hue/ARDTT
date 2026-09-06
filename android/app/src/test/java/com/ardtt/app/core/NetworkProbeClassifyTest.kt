package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeClassifyTest {
    @Test
    fun vpsIpOnWhitelistPicksBypass() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
        assertTrue(r.whitelistRestricted)
        assertTrue(r.provisionOk)
        assertTrue(r.message.contains("белый список") || r.message.contains("обход"))
    }

    @Test
    fun vpsOnWhitelistPicksBypassNotDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
        assertTrue(r.whitelistRestricted)
        assertTrue(r.message.contains("белый список") || r.message.contains("обход"))
    }

    @Test
    fun vpsWithoutOpenCloudflareIsBypassNotDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
    }

    @Test
    fun mtsStyleTcpCloudflareWithoutTlsPicksBypass() {
        // Lab on MTS: Yandex TCP/HTTP up, 1.1.1.1 TCP up but TLS/UDP dead,
        // :9100 TCP up but /health dead. After the probe change bigtechOk and
        // provisionOk are both false; even if /health later works, no TLS → Bypass.
        val noHealth = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
        )
        assertEquals(VpnPath.Bypass, noHealth.preselectedPath)
        assertTrue(noHealth.whitelistRestricted)
        val healthButNoTls = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Bypass, healthButNoTls.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, healthButNoTls.networkClass)
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
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = false,
        )
        assertNull(r.preselectedPath)
        assertEquals(NetworkClass.NoNetwork, r.networkClass)
    }

    @Test
    fun whitelistWithoutVpsPicksBypass() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
        assertTrue(r.whitelistRestricted)
        assertTrue(r.message.contains("белый список"))
    }

    @Test
    fun vpsAloneWithoutPublicDnsIsNotDirect() {
        val r = NetworkProbe.classify(
            systemOnline = false,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
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
    fun bothPublicIpsDeadIsNoNetwork() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
        )
        assertNull(r.preselectedPath)
        assertEquals(NetworkClass.NoNetwork, r.networkClass)
    }

    @Test
    fun decideProbePathWaitsForCloudflareWhenVpsUp() {
        assertEquals(
            ProbePathHint.Wait,
            NetworkProbe.decideProbePath(
                provisionOk = true,
                yandexOk = null,
                cloudflareOk = null,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathWhitelistBypassEvenIfVpsTcpUp() {
        assertEquals(
            ProbePathHint.Bypass,
            NetworkProbe.decideProbePath(
                provisionOk = true,
                yandexOk = true,
                cloudflareOk = false,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathOpenInternetDirectWhenVpsUp() {
        assertEquals(
            ProbePathHint.Direct,
            NetworkProbe.decideProbePath(
                provisionOk = true,
                yandexOk = true,
                cloudflareOk = true,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathWhitelistBypassesWithoutWaitingVps() {
        assertEquals(
            ProbePathHint.Bypass,
            NetworkProbe.decideProbePath(
                provisionOk = null,
                yandexOk = true,
                cloudflareOk = false,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathProvisionAloneIsNotDirect() {
        assertEquals(
            ProbePathHint.Bypass,
            NetworkProbe.decideProbePath(
                provisionOk = true,
                yandexOk = false,
                cloudflareOk = false,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathBypassWhenVpsDeadAndYandexLives() {
        assertEquals(
            ProbePathHint.Bypass,
            NetworkProbe.decideProbePath(
                provisionOk = false,
                yandexOk = true,
                cloudflareOk = null,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathNoNetworkWhenEverythingFailed() {
        assertEquals(
            ProbePathHint.NoNetwork,
            NetworkProbe.decideProbePath(
                provisionOk = false,
                yandexOk = false,
                cloudflareOk = false,
                captive = false,
            ),
        )
    }

    @Test
    fun decideProbePathCaptiveOnlyAfterExplicitCheck() {
        assertEquals(
            ProbePathHint.Captive,
            NetworkProbe.decideProbePath(
                provisionOk = false,
                yandexOk = false,
                cloudflareOk = false,
                captive = true,
            ),
        )
    }

    @Test
    fun provisionUrlHostAndPort() {
        assertEquals("10.1.2.3" to 9100, NetworkProbe.parseProvisionEndpoint("http://10.1.2.3:9100"))
        assertEquals("10.1.2.3" to 9100, NetworkProbe.parseProvisionEndpoint("http://10.1.2.3:9100/"))
        assertEquals("example.com" to 443, NetworkProbe.parseProvisionEndpoint("https://example.com"))
        assertEquals(null, NetworkProbe.parseProvisionEndpoint(null))
        assertEquals(null, NetworkProbe.parseProvisionEndpoint("   "))
        assertEquals("http://10.1.2.3:9100/health", NetworkProbe.provisionHealthUrl("http://10.1.2.3:9100"))
        assertEquals("http://10.1.2.3:9100/health", NetworkProbe.provisionHealthUrl("http://10.1.2.3:9100/"))
        assertEquals("http://10.1.2.3:9100/health", NetworkProbe.provisionHealthUrl("http://10.1.2.3:9100/health"))
        assertEquals(null, NetworkProbe.provisionHealthUrl(null))
        assertTrue(!NetworkProbe.provisionReachable(null, 200, bindNetwork = null))
    }
}
