package com.ardtt.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        assertEquals(RestrictionHint.Suspected, r.restriction)
        assertTrue(r.whitelistRestricted)
        assertEquals(25, r.whitelistScorePercent)
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
        assertEquals(RestrictionHint.Suspected, noHealth.restriction)
        assertTrue(noHealth.whitelistRestricted)
        assertEquals(25, noHealth.whitelistScorePercent)
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
        assertEquals(RestrictionHint.Suspected, healthButNoTls.restriction)
        assertEquals(25, healthButNoTls.whitelistScorePercent)
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
        assertEquals(RestrictionHint.Suspected, r.restriction)
        assertEquals(25, r.whitelistScorePercent)
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
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DataUnconfirmed, r.networkClass)
        assertEquals("Передача данных не подтверждена", r.message)
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
        assertEquals(RestrictionHint.Suspected, r.restriction)
        assertEquals(25, r.whitelistScorePercent)
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
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertEquals(NetworkClass.DataUnconfirmed, r.networkClass)
        assertEquals("Передача данных не подтверждена", r.message)
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
    fun ruControlSuccessIsInternetEvidenceAndGoesDirect() {
        assertEquals(
            ProbePathHint.Direct,
            NetworkProbe.decideProbePath(
                provisionOk = false,
                yandexOk = false,
                cloudflareOk = false,
                captive = null,
                googleOk = false,
                ruServiceOk = true,
            ),
        )
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
            underlayKind = UnderlayKind.Cellular,
            ruServiceOutcome = CheckOutcome.Success,
        )
        assertTrue(r.ruServiceOk)
        assertEquals(CheckOutcome.Success, r.ruServiceOutcome)
        assertTrue(r.networkClass != NetworkClass.DataUnconfirmed)
    }

    @Test
    fun ruControlFoldsTheRaceOverEveryVkAddress() {
        assertEquals(CheckOutcome.NotRun, NetworkProbePolicy.foldControlOutcomes(emptyList()))
        assertEquals(
            CheckOutcome.Success,
            NetworkProbePolicy.foldControlOutcomes(
                listOf(CheckOutcome.Timeout, CheckOutcome.Success),
            ),
        )
        assertEquals(
            CheckOutcome.Timeout,
            NetworkProbePolicy.foldControlOutcomes(
                listOf(CheckOutcome.Refused, CheckOutcome.Timeout),
            ),
        )
        assertEquals(
            CheckOutcome.Refused,
            NetworkProbePolicy.foldControlOutcomes(
                listOf(CheckOutcome.Cancelled, CheckOutcome.Refused, CheckOutcome.NotRun),
            ),
        )
        assertEquals(
            CheckOutcome.Cancelled,
            NetworkProbePolicy.foldControlOutcomes(
                listOf(CheckOutcome.NotRun, CheckOutcome.Cancelled),
            ),
        )
        assertTrue(NetworkProbe.RU_CONTROL_HOSTS.isNotEmpty())
        NetworkProbe.RU_CONTROL_HOSTS.forEach { host ->
            assertTrue(host.ips.isNotEmpty())
            host.ips.forEach { assertTrue(NetworkProbe.numericIpv4(it) != null) }
        }
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
    fun firstCompleteCellularSampleConfirmsWhitelistAndPrefersBypass() {
        val first = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            seriesCount = 1,
            googleOutcome = CheckOutcome.Timeout,
            ruServiceOutcome = CheckOutcome.Success,
        )
        assertEquals(RestrictionHint.Confirmed, first.restriction)
        assertEquals(VpnPath.Bypass, first.preselectedPath)
        assertEquals(80, first.whitelistScorePercent)
        assertEquals("whitelist", first.routeReason)
        val second = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            seriesCount = 2,
            googleOutcome = CheckOutcome.Timeout,
            ruServiceOutcome = CheckOutcome.Success,
            previousWhitelistScore = first.whitelistScorePercent,
        )
        assertEquals(RestrictionHint.Confirmed, second.restriction)
        assertEquals(VpnPath.Bypass, second.preselectedPath)
        assertEquals(100, second.whitelistScorePercent)
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
        assertEquals(
            0,
            nextProbeSeriesCount(
                evidence,
                evidence.copy(
                    yandex = CheckOutcome.NetworkLost,
                    measuredAtElapsedMs = 90L,
                ),
            ),
        )
        assertTrue(
            !isRestrictionSeriesSample(
                CheckOutcome.Success,
                CheckOutcome.Timeout,
                CheckOutcome.NetworkLost,
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
            ruServiceOutcome = CheckOutcome.Success,
        )
        assertEquals(RestrictionHint.Confirmed, r.restriction)
        assertEquals(VpnPath.Bypass, r.preselectedPath)
        assertEquals("whitelist", r.routeReason)
        assertEquals(80, r.whitelistScorePercent)
    }

    @Test
    fun congestedLinkWithoutVkNeverLocksBypassInOneRound() {
        val congested = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            googleOutcome = CheckOutcome.Timeout,
            ruServiceOutcome = CheckOutcome.Timeout,
        )
        assertEquals(RestrictionHint.Unknown, congested.restriction)
        assertEquals(VpnPath.Direct, congested.preselectedPath)
        assertEquals(0, congested.whitelistScorePercent)
        val partial = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = false,
            captive = false,
            provisionOk = true,
            underlayKind = UnderlayKind.Cellular,
            googleOutcome = CheckOutcome.Timeout,
            ruServiceOutcome = CheckOutcome.Cancelled,
        )
        assertEquals(RestrictionHint.Suspected, partial.restriction)
        assertEquals(VpnPath.Direct, partial.preselectedPath)
        assertEquals(25, partial.whitelistScorePercent)
    }

    @Test
    fun aRoundWithVkDownIsNotARestrictionSeriesSample() {
        assertTrue(
            !isRestrictionSeriesSample(
                CheckOutcome.Success,
                CheckOutcome.Timeout,
                CheckOutcome.Timeout,
                CheckOutcome.Timeout,
            ),
        )
        assertTrue(
            isRestrictionSeriesSample(
                CheckOutcome.Success,
                CheckOutcome.Timeout,
                CheckOutcome.Timeout,
                CheckOutcome.Success,
            ),
        )
        assertEquals(
            RestrictionHint.Unknown,
            NetworkProbePolicy.restrictionHint(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Refused,
            ),
        )
        assertEquals(
            RestrictionHint.Confirmed,
            NetworkProbePolicy.restrictionHint(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
            ),
        )
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
    fun dnsReplyMustMatchIdQuestionAndAnswers() {
        val query = NetworkProbe.buildDnsQuery()
        val headerOnly = query.copyOf(12)
        headerOnly[2] = (headerOnly[2].toInt() or 0x80).toByte()
        headerOnly[7] = 1
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, headerOnly, 12))
        val ok = dnsReplyWithCopiedQuestion(query)
        assertTrue(NetworkProbe.dnsReplyLooksValid(query, ok, ok.size))
        val wrongId = ok.copyOf()
        wrongId[1] = (wrongId[1].toInt() xor 0x01).toByte()
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, wrongId, ok.size))
        val notResponse = query.copyOf(ok.size)
        query.copyInto(notResponse)
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, notResponse, query.size))
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, ByteArray(8), 8))
        val noAnswers = dnsReplyWithCopiedQuestion(query, answers = 0)
        assertTrue(!NetworkProbe.dnsReplyLooksValid(query, noAnswers, noAnswers.size))
        assertEquals(CheckOutcome.Timeout, NetworkProbe.classifyCheckFailure(java.net.SocketTimeoutException("t")))
        assertEquals(CheckOutcome.TlsFailure, NetworkProbe.classifyCheckFailure(javax.net.ssl.SSLHandshakeException("c")))
        assertEquals(CheckOutcome.BindFailure, NetworkProbe.classifyCheckFailure(java.net.SocketException("Permission denied")))
        assertEquals(CheckOutcome.Refused, NetworkProbe.classifyCheckFailure(java.net.ConnectException("Connection refused")))
        assertEquals(0, remainingTimeoutMs(1_000L, 1_000L, 700))
        assertEquals(100, remainingTimeoutMs(1_100L, 1_000L, 700))
        assertEquals(0, remainingTimeoutMs(900L, 1_000L, 700))
    }

    @Test
    fun offlineWithoutPhysicalNetIsNoNetwork() {
        val r = NetworkProbe.classify(
            systemOnline = false,
            yandexOk = false,
            bigtechOk = false,
            captive = false,
            provisionOk = false,
            underlayKind = UnderlayKind.Other,
        )
        assertNull(r.preselectedPath)
        assertEquals(NetworkClass.NoNetwork, r.networkClass)
        assertEquals("Нет сети", r.message)
    }

    @Test
    fun provisionFailKeepsDirectPathAndDoesNotPromiseBypass() {
        val r = NetworkProbe.classify(
            systemOnline = true,
            yandexOk = true,
            bigtechOk = true,
            captive = false,
            provisionOk = false,
            underlayKind = UnderlayKind.Cellular,
        )
        assertEquals(NetworkClass.OpenNeedBypass, r.networkClass)
        assertEquals(VpnPath.Direct, r.preselectedPath)
        assertTrue(!r.message.contains("обход"))
    }

    @Test
    fun cancelAbortsHungTlsHandshakeBeforeSocketTimeout() {
        val server = java.net.ServerSocket(0)
        val acceptor = Thread {
            runCatching {
                val client = server.accept()
                Thread.sleep(30_000)
                client.close()
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    NetworkProbe.tlsReachableOutcome("127.0.0.1", server.localPort, 8_000, null)
                }
                delay(250)
                val started = System.currentTimeMillis()
                job.cancel()
                job.join()
                val elapsed = System.currentTimeMillis() - started
                assertTrue("cancel waited ${elapsed}ms", elapsed < 2_000L)
            }
        } finally {
            runCatching { server.close() }
            acceptor.interrupt()
        }
    }

    private fun dnsReplyWithCopiedQuestion(query: ByteArray, answers: Int = 1): ByteArray {
        val reply = ByteArray(query.size + 16)
        query.copyInto(reply)
        reply[2] = (reply[2].toInt() or 0x80).toByte()
        reply[6] = 0
        reply[7] = answers.toByte()
        var i = query.size
        reply[i++] = 0xc0.toByte()
        reply[i++] = 0x0c
        reply[i++] = 0
        reply[i++] = 1
        reply[i++] = 0
        reply[i++] = 1
        reply[i++] = 0
        reply[i++] = 0
        reply[i++] = 0
        reply[i++] = 60
        reply[i++] = 0
        reply[i++] = 4
        reply[i++] = 1
        reply[i++] = 2
        reply[i++] = 3
        reply[i++] = 4
        return reply
    }
}
