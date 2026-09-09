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
        assertEquals(NetworkClass.DirectOk, r.networkClass)
        assertEquals(RestrictionHint.Unknown, r.restriction)
        assertTrue(!r.whitelistRestricted)
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
        assertTrue(!noHealth.whitelistRestricted)
        val healthButNoTls = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(VpnPath.Direct, healthButNoTls.preselectedPath)
        assertEquals(NetworkClass.DirectOk, healthButNoTls.networkClass)
        assertEquals(RestrictionHint.Unknown, healthButNoTls.restriction)
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
        assertEquals(NetworkClass.DirectOk, r.networkClass)
        assertEquals(RestrictionHint.Unknown, r.restriction)
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
        assertEquals(NetworkClass.DirectOk, r.networkClass)
        assertTrue(!r.whitelistRestricted)
        assertEquals(RestrictionHint.Unknown, r.restriction)
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
    fun decideProbePathDirectWhenVpsUpWithoutWaitingForCloudflare() {
        assertEquals(
            ProbePathHint.Direct,
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
    fun decideProbePathDirectWhenYandexKnownEvenIfProvisionUnknown() {
        assertEquals(
            ProbePathHint.Direct,
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
    fun decideProbePathDirectWhenYandexUpBeforeCloudflareFinishes() {
        assertEquals(
            ProbePathHint.Direct,
            NetworkProbe.decideProbePath(
                provisionOk = false,
                yandexOk = true,
                cloudflareOk = null,
                captive = null,
            ),
        )
    }

    @Test
    fun decideProbePathWaitsForGoogleWhenOthersAlreadyFailed() {
        assertEquals(
            ProbePathHint.Wait,
            NetworkProbe.decideProbePath(
                provisionOk = false,
                yandexOk = false,
                cloudflareOk = false,
                captive = false,
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
                googleOk = false,
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

    @Test
    fun secondCompletedCellularSeriesConfirmsRestriction() {
        val first = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            seriesCount = 1,
            googleOutcome = CheckOutcome.Timeout,
        )
        assertEquals(RestrictionHint.Suspected, first.restriction)
        assertEquals(VpnPath.Direct, first.preselectedPath)
        val second = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            seriesCount = 2,
            googleOutcome = CheckOutcome.Timeout,
        )
        assertEquals(RestrictionHint.Confirmed, second.restriction)
        assertEquals(VpnPath.Direct, second.preselectedPath)
        assertEquals(NetworkClass.NeedBypass, second.networkClass)
    }

    @Test
    fun sameSnapshotIsNotANewProbeSeries() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val evidence = ReachabilityEvidence(
            networkKey = key,
            profileId = "p",
            measuredAtElapsedMs = 40L,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.Timeout,
            seriesCount = 1,
            bindHandle = 1L,
        )
        assertEquals(1, nextProbeSeriesCount(evidence, evidence))
        assertEquals(
            2,
            nextProbeSeriesCount(
                evidence,
                evidence.copy(measuredAtElapsedMs = 80L),
            ),
        )
        assertEquals(
            1,
            nextProbeSeriesCount(
                evidence,
                evidence.copy(networkKey = NetworkKey(2L, UnderlayKind.Cellular, 8, "cell2")),
            ),
        )
        val open = evidence.copy(
            bigtech = CheckOutcome.Success,
            seriesCount = 4,
        )
        assertEquals(0, nextProbeSeriesCount(open, open.copy(measuredAtElapsedMs = 90L)))
        assertEquals(
            1,
            nextProbeSeriesCount(
                open,
                evidence.copy(measuredAtElapsedMs = 90L),
            ),
        )
        assertEquals(
            1,
            nextProbeSeriesCount(
                evidence.copy(ttlUntilElapsedMs = 50L, seriesCount = 2),
                evidence.copy(measuredAtElapsedMs = 80L),
                elapsedMs = 80L,
            ),
        )
        assertEquals(
            0,
            nextProbeSeriesCount(
                evidence,
                evidence.copy(
                    measuredAtElapsedMs = 120L,
                    yandex = CheckOutcome.Cancelled,
                    bigtech = CheckOutcome.NotRun,
                ),
            ),
        )
        assertEquals(
            1,
            nextProbeSeriesCount(
                evidence.copy(
                    yandex = CheckOutcome.Cancelled,
                    bigtech = CheckOutcome.NotRun,
                    seriesCount = 4,
                ),
                evidence.copy(measuredAtElapsedMs = 121L),
            ),
        )
    }

    @Test
    fun wifiIsNotOperatorWhitelistEvenAfterSeries() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Wifi,
            seriesCount = 4,
        )
        assertEquals(RestrictionHint.None, r.restriction)
    }

    @Test
    fun cloudflareAndGoogleFailuresAreTwoIndependentOrdinaryTargets() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            googleOutcome = CheckOutcome.Timeout,
        )
        assertEquals(RestrictionHint.Suspected, r.restriction)
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals("direct", r.routeReason)
    }

    @Test
    fun tlsFailureIsNotARestrictionSample() {
        assertTrue(
            !isRestrictionSeriesSample(
                CheckOutcome.Success,
                CheckOutcome.TlsFailure,
                CheckOutcome.Timeout,
            ),
        )
        assertTrue(
            !isRestrictionSeriesSample(
                CheckOutcome.Success,
                CheckOutcome.Timeout,
                CheckOutcome.BindFailure,
            ),
        )
        assertEquals(
            RestrictionHint.Unknown,
            NetworkProbePolicy.restrictionHint(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.TlsFailure,
                google = CheckOutcome.Timeout,
                seriesCount = 2,
            ),
        )
    }

    @Test
    fun dnsReplyMustMatchIdAndBeAResponse() {
        val query = NetworkProbe.buildDnsQuery()
        val ok = query.copyOf(64)
        ok[2] = (ok[2].toInt() or 0x80).toByte()
        assertTrue(NetworkProbe.dnsReplyLooksValid(query, ok, 12))
        val wrongId = ok.copyOf()
        wrongId[1] = (wrongId[1].toInt() xor 0x01).toByte()
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, wrongId, 12))
        val notResponse = query.copyOf(64)
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, notResponse, 12))
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, ByteArray(8), 8))
        assertEquals(CheckOutcome.Timeout, NetworkProbe.classifyCheckFailure(java.net.SocketTimeoutException("t")))
        assertEquals(CheckOutcome.TlsFailure, NetworkProbe.classifyCheckFailure(javax.net.ssl.SSLHandshakeException("c")))
        assertEquals(CheckOutcome.BindFailure, NetworkProbe.classifyCheckFailure(java.net.SocketException("Permission denied")))
        assertEquals(CheckOutcome.Refused, NetworkProbe.classifyCheckFailure(java.net.ConnectException("Connection refused")))
    }
}
