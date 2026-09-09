package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeClassifyTest {
    @Test
    fun cellularRestrictionHintStillTriesDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
        assertTrue(r.whitelistRestricted)
        assertTrue(r.provisionOk)
        assertTrue(r.message.contains("ограничен"))
    }

    @Test
    fun nonCellularYandexWithoutCloudflareIsNotMobileDiagnosis() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Wifi,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DirectOk, r.networkClass)
        assertEquals(RestrictionHint.None, r.restriction)
    }

    @Test
    fun vpsReachableWithoutPublicDnsStillTriesDirect() {
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
    fun cellularYandexWithoutCloudflareIsHintNotBypassLock() {
        val noHealth = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(VpnPath.Direct, noHealth.preselectedPath)
        assertTrue(noHealth.whitelistRestricted)
        val healthButNoTls = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(VpnPath.Direct, healthButNoTls.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, healthButNoTls.networkClass)
    }

    @Test
    fun yandexWithoutVpsOnCellularStillTriesDirect() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            awgUdpOk = false,
            provisionOk = false,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
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
    fun whitelistWithoutVpsOnCellularIsHint() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, r.networkClass)
        assertTrue(r.whitelistRestricted)
        assertTrue(r.message.contains("ограничен"))
    }

    @Test
    fun vpsAloneWithoutPublicDnsIsDirectCandidate() {
        val r = NetworkProbe.classify(
            systemOnline = false,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
        )
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DirectOk, r.networkClass)
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
    fun decideProbePathDirectWhenYandexUpCloudflareDown() {
        assertEquals(
            ProbePathHint.Direct,
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
    fun decideProbePathWaitsWhenCloudflareUnknown() {
        assertEquals(
            ProbePathHint.Wait,
            NetworkProbe.decideProbePath(
                provisionOk = null,
                yandexOk = true,
                cloudflareOk = false,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathProvisionAloneIsDirectCandidate() {
        assertEquals(
            ProbePathHint.Direct,
            NetworkProbe.decideProbePath(
                provisionOk = true,
                yandexOk = false,
                cloudflareOk = false,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathWaitsWhenCloudflareStillRunning() {
        assertEquals(
            ProbePathHint.Wait,
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
        assertTrue(NetworkProbe.provisionHealthAccepted(200))
        assertTrue(!NetworkProbe.provisionHealthAccepted(204))
        assertTrue(!NetworkProbe.provisionHealthAccepted(301))
        assertTrue(!NetworkProbe.provisionHealthAccepted(302))
        assertTrue(!NetworkProbe.provisionHealthAccepted(404))
        assertTrue(!NetworkProbe.provisionReachable(null, 200, bindNetwork = null))
    }
}
