package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellularPreProbeCarrierTest {
    private val mts = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell", carrier = "25001")
    private val beeline = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell", carrier = "25099")
    private val unknownCarrier = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell")

    private fun scored(key: NetworkKey) = ReachabilityEvidence(
        networkKey = key,
        profileId = "p",
        yandex = CheckOutcome.Success,
        bigtech = CheckOutcome.Timeout,
        google = CheckOutcome.Timeout,
        restriction = RestrictionHint.Confirmed,
        whitelistScorePercent = 80,
        ttlUntilElapsedMs = 90_000L,
    )

    @Test
    fun roamingOntoAnotherOperatorDropsTheScore() {
        // Same handle and SIM: Android sees one network, but it is a different
        // operator, so the whitelist measurement no longer applies.
        assertTrue(mts.samePhysicalNetwork(beeline))
        assertFalse(mts.sameCarrier(beeline))
        assertEquals(80, scored(mts).whitelistScoreAt(mts, "p"))
        assertEquals(0, scored(mts).whitelistScoreAt(beeline, "p"))
        assertFalse(scored(mts).usableAt(0L, beeline, "p"))
        assertFalse(hasSameNetworkProbeEvidence(scored(mts), beeline, "p"))
    }

    @Test
    fun anUnreadableOperatorKeepsTheScoreOnTheSameRadio() {
        assertTrue(mts.sameCarrier(unknownCarrier))
        assertTrue(unknownCarrier.sameCarrier(mts))
        assertEquals(80, scored(mts).whitelistScoreAt(unknownCarrier, "p"))
        assertEquals(80, scored(unknownCarrier).whitelistScoreAt(mts, "p"))
        assertTrue(scored(unknownCarrier).withFreshStrongTtl().hasFreshStrong(1L, mts, "p"))
        val otherHandle = NetworkKey(9L, UnderlayKind.Cellular, 11, "cell-b", carrier = "25001")
        assertFalse(scored(unknownCarrier).hasFreshStrong(1L, otherHandle, "p"))
        assertFalse(scored(unknownCarrier).hasFreshStrong(1L, beeline.copy(handle = 9L), "p"))
    }

    @Test
    fun negativeDirectEvidenceAndStashDoNotCrossOperators() {
        val negative = DirectNegativeEvidence(key = mts, retryAfterElapsedMs = 60_000L)
        assertTrue(negative.stillBlocks(0L, mts, null))
        assertFalse(negative.stillBlocks(0L, beeline, null))

        assertNull(adoptCellularEvidence(scored(mts), beeline))
        val unknownAdopted = adoptCellularEvidence(scored(unknownCarrier), beeline)
        assertEquals(beeline, unknownAdopted?.networkKey)
        assertEquals(unknownCarrier, unknownAdopted?.originNetworkKey)
        assertFalse(mts.directConfirmedOn(beeline))
        assertTrue(mts.directConfirmedOn(mts))
    }

    @Test
    fun aSampleFromAnotherOperatorDoesNotFoldOntoTheOldScore() {
        val folded = foldReachabilityEvidence(
            previous = scored(mts),
            incoming = ReachabilityEvidence(
                networkKey = beeline,
                profileId = "p",
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
            ),
            cellular = true,
            elapsedMs = 1_000L,
        )
        assertEquals(RecoverySettings.WHITELIST_SCORE_RISE, folded.whitelistScorePercent)
    }

    @Test
    fun aDifferentSimOnTheSameOperatorIsStillADifferentRadio() {
        val otherSim = NetworkKey(9L, UnderlayKind.Cellular, 12, "cell-2", carrier = "25001")
        assertFalse(mts.sameCellularSim(otherSim))
        assertFalse(mts.matchesCellularUnderlay(otherSim))
    }

    @Test
    fun lteHandleFlapKeepsDirectNegativeOnTheSameSim() {
        val nextBs = NetworkKey(8L, UnderlayKind.Cellular, 11, "cell-bs", carrier = "25001")
        val negative = DirectNegativeEvidence(key = mts, retryAfterElapsedMs = 60_000L)
        assertFalse(mts.directFailureScopeChanged(nextBs))
        assertTrue(negative.stillBlocks(0L, nextBs, null))
        assertTrue(
            deadDirectBlocksLiveUnderlay(
                blockUntilUnderlayChange = true,
                deadKey = mts,
                liveKey = nextBs,
            ),
        )
        assertFalse(
            deadDirectBlocksLiveUnderlay(
                blockUntilUnderlayChange = true,
                deadKey = mts,
                liveKey = beeline,
            ),
        )
        assertTrue(mts.restrictionScopeChanged(nextBs))
    }
}

class CellularPreProbeTest {
    private val cellA = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell-a")
    private val cellBSameSim = NetworkKey(9L, UnderlayKind.Cellular, 11, "cell-b")
    private val cellAKnown = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell-a", carrier = "25001")
    private val cellBKnown = NetworkKey(9L, UnderlayKind.Cellular, 11, "cell-b", carrier = "25001")
    private val cellOtherSim = NetworkKey(3L, UnderlayKind.Cellular, 22, "cell-other")
    private val wifi = NetworkKey(2L, UnderlayKind.Wifi, null, "wifi")

    private fun positive(key: NetworkKey) = ReachabilityEvidence(
        networkKey = key,
        profileId = "p",
        yandex = CheckOutcome.Success,
        bigtech = CheckOutcome.Timeout,
        google = CheckOutcome.Timeout,
        restriction = RestrictionHint.Confirmed,
        whitelistScorePercent = 80,
        seriesId = "pre",
        measuredAtElapsedMs = 1L,
        usableAtElapsedMs = 1L,
        strongAtElapsedMs = 1L,
        ttlUntilElapsedMs = 90_000L,
        strongUntilElapsedMs = 90_000L,
    )

    @Test
    fun sameCellularSimIgnoresHandleAndRejectsOtherSim() {
        assertTrue(cellA.sameCellularSim(cellBSameSim))
        assertTrue(cellA.matchesCellularUnderlay(cellBSameSim))
        assertFalse(cellA.samePhysicalNetwork(cellBSameSim))
        assertFalse(cellA.sameCellularSim(cellOtherSim))
        assertFalse(cellA.sameCellularSim(wifi))
        assertFalse(cellA.sameCellularSim(null))
    }

    @Test
    fun staleWifiSnapshotDoesNotStubACellularProbe() {
        val liveCell = cellBSameSim
        assertEquals(
            liveCell,
            measurementNetworkKeyForWhitelist(
                capturedKey = wifi,
                liveKey = liveCell,
                liveKind = UnderlayKind.Cellular,
                autoKind = UnderlayKind.Cellular,
            ),
        )
        assertEquals(
            cellA,
            measurementNetworkKeyForWhitelist(
                capturedKey = cellA,
                liveKey = wifi,
                liveKind = UnderlayKind.Wifi,
                autoKind = UnderlayKind.Cellular,
            ),
        )
        assertTrue(
            measurementIsCellularForWhitelist(
                measurementKey = cellA,
                liveKind = UnderlayKind.Wifi,
                snapshotKind = UnderlayKind.Wifi,
                autoKind = UnderlayKind.Cellular,
            ),
        )
        assertFalse(
            measurementIsCellularForWhitelist(
                measurementKey = wifi,
                liveKind = UnderlayKind.Wifi,
                snapshotKind = UnderlayKind.Wifi,
                autoKind = UnderlayKind.Wifi,
            ),
        )
        assertTrue(probeResultAppliesToSnapshot(cellA, wifi))
        assertTrue(probeResultAppliesToSnapshot(cellA, cellBSameSim))
        assertFalse(probeResultAppliesToSnapshot(wifi, cellA))
        val cellular = measurementIsCellularForWhitelist(
            measurementKey = cellA,
            liveKind = UnderlayKind.Cellular,
            snapshotKind = UnderlayKind.Wifi,
            autoKind = UnderlayKind.Cellular,
        )
        assertEquals(
            RestrictionSample.Positive,
            RestrictionScore.sample(
                cellular = cellular,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
            ),
        )
        assertEquals(
            RestrictionSample.Ignore,
            RestrictionScore.sample(
                cellular = false,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
            ),
        )
    }

    @Test
    fun adoptRebindsHandleForSameKnownOperator() {
        val adopted = adoptCellularEvidence(positive(cellAKnown), cellBKnown)
        assertNotNull(adopted)
        assertEquals(cellBKnown, adopted?.networkKey)
        assertEquals(cellAKnown, adopted?.originNetworkKey)
        assertEquals(80, adopted?.whitelistScorePercent)
        assertEquals(1L, adopted?.measuredAtElapsedMs)
        assertEquals(1L, adopted?.strongAtElapsedMs)
        assertEquals(90_000L, adopted?.ttlUntilElapsedMs)
        assertTrue(adopted!!.hasFreshStrong(10L, cellBKnown, "p"))
    }

    @Test
    fun unknownOriginDoesNotBecomeFreshStrongOnAnotherHandle() {
        val adopted = adoptCellularEvidence(positive(cellA), cellBSameSim)
        assertNull(adopted)
        assertFalse(positive(cellA).hasFreshStrong(10L, cellBSameSim, "p"))
        val chain = whitelistEvidenceForUnderlay(
            evidence = null,
            cellularEvidence = positive(cellA),
            key = cellBSameSim.copy(carrier = "25001"),
            profileId = "p",
            nowElapsedMs = 10L,
        )
        assertNull(chain)
        val liveB = cellBSameSim.copy(carrier = "25001")
        assertFalse(
            RestrictionScore.mayEnterBypassForWhitelist(
                positive(cellA).historicalWhitelistScore(liveB, "p"),
                positive(cellA).hasFreshStrong(10L, liveB, "p"),
            ),
        )
        val measuredOnB = positive(liveB)
        assertTrue(measuredOnB.hasFreshStrong(10L, liveB, "p"))
    }

    @Test
    fun adoptRejectsOtherSimAndWifi() {
        assertNull(adoptCellularEvidence(positive(cellA), cellOtherSim))
        assertNull(adoptCellularEvidence(positive(cellA), wifi))
        assertNull(adoptCellularEvidence(positive(wifi), cellA))
    }

    @Test
    fun whitelistEvidencePrefersCellularStashOnLte() {
        val wifiEvidence = ReachabilityEvidence(
            networkKey = wifi,
            yandex = CheckOutcome.NotRun,
            whitelistScorePercent = 0,
        )
        val chosen = whitelistEvidenceForUnderlay(
            evidence = wifiEvidence,
            cellularEvidence = positive(cellAKnown),
            key = cellBKnown,
            profileId = "p",
            nowElapsedMs = 10L,
        )
        assertEquals(80, chosen?.whitelistScorePercent)
        assertEquals(cellBKnown, chosen?.networkKey)
        assertEquals(cellAKnown, chosen?.originNetworkKey)
        assertTrue(chosen!!.hasFreshStrong(10L, cellBKnown, "p"))
        val again = adoptCellularEvidence(chosen, cellBKnown)
        assertEquals(cellAKnown, again?.originNetworkKey)
    }

    @Test
    fun shouldPreProbeOnlyAutoOnWifiOrEthernet() {
        assertTrue(shouldPreProbeCellular(ConnPathMode.Auto, UnderlayKind.Wifi))
        assertTrue(shouldPreProbeCellular(ConnPathMode.Auto, UnderlayKind.Ethernet))
        assertFalse(shouldPreProbeCellular(ConnPathMode.Auto, UnderlayKind.Cellular))
        assertFalse(shouldPreProbeCellular(ConnPathMode.Direct, UnderlayKind.Wifi))
        assertFalse(shouldPreProbeCellular(ConnPathMode.Bypass, UnderlayKind.Wifi))
    }

    @Test
    fun wifiDirectProofIsNotCellularProof() {
        assertFalse(wifi.directConfirmedOn(cellA))
        assertTrue(cellA.directConfirmedOn(cellBSameSim))
        assertTrue((null as NetworkKey?).directConfirmedOn(cellA))
    }

    @Test
    fun expiredPreProbeIsNotAPathReason() {
        val expired = positive(cellA).copy(
            ttlUntilElapsedMs = 20L,
            strongUntilElapsedMs = 20L,
        )
        assertNull(
            whitelistEvidenceForUnderlay(
                evidence = null,
                cellularEvidence = expired,
                key = cellBSameSim,
                profileId = "p",
                nowElapsedMs = 20L,
            ),
        )
        assertFalse(expired.hasFreshStrong(20L, cellBSameSim, "p"))
        assertFalse(
            RestrictionScore.mayEnterBypassForWhitelist(
                expired.historicalWhitelistScore(cellBSameSim, "p"),
                expired.hasFreshStrong(20L, cellBSameSim, "p"),
            ),
        )
    }

    @Test
    fun whitelistOriginMatrix() {
        val simS = 11
        val aUnknown = NetworkKey(1L, UnderlayKind.Cellular, simS, "a")
        val bKnown = NetworkKey(9L, UnderlayKind.Cellular, simS, "b", carrier = "25001")
        val aKnownX = NetworkKey(1L, UnderlayKind.Cellular, simS, "a", carrier = "25001")
        val bKnownX = NetworkKey(9L, UnderlayKind.Cellular, simS, "b", carrier = "25001")
        val sameHandleY = NetworkKey(1L, UnderlayKind.Cellular, simS, "a", carrier = "25099")
        val otherSim = NetworkKey(3L, UnderlayKind.Cellular, 22, "other", carrier = "25001")
        val emptyLive = NetworkKey(1L, UnderlayKind.Cellular, simS, "a")
        assertFalse(positive(aUnknown).hasFreshStrong(10L, bKnown, "p"))
        assertFalse(
            ReachabilityEvidence(whitelistScorePercent = 80, strongAtElapsedMs = 1L, strongUntilElapsedMs = 90_000L)
                .hasFreshStrong(10L, bKnown, "p"),
        )
        assertTrue(positive(aKnownX).hasFreshStrong(10L, bKnownX, "p"))
        assertFalse(positive(aKnownX).hasFreshStrong(10L, sameHandleY, "p"))
        assertFalse(positive(aKnownX).hasFreshStrong(10L, otherSim, "p"))
        assertTrue(positive(aKnownX).hasFreshStrong(10L, emptyLive, "p"))
        assertTrue(positive(aUnknown).hasFreshStrong(10L, aUnknown, "p"))
        assertFalse(positive(aUnknown).hasFreshStrong(10L, bKnown, "p"))
        val expired = positive(aKnownX).copy(ttlUntilElapsedMs = 1L, strongUntilElapsedMs = 1L)
        assertFalse(expired.hasFreshStrong(1L, aKnownX, "p"))
        assertFalse(positive(aKnownX).hasFreshStrong(10L, aKnownX, "other-profile"))
    }

    @Test
    fun ignoreAfterUnknownOriginStrongDoesNotConfirmAnotherHandle() {
        val aUnknown = NetworkKey(1L, UnderlayKind.Cellular, 11, "a")
        val aKnown = NetworkKey(1L, UnderlayKind.Cellular, 11, "a", carrier = "25001")
        val bKnown = NetworkKey(9L, UnderlayKind.Cellular, 11, "b", carrier = "25001")
        val strong = foldReachabilityEvidence(
            previous = null,
            incoming = positive(aUnknown).copy(
                measuredAtElapsedMs = 1_000L,
                ruService = CheckOutcome.Success,
            ),
            cellular = true,
            elapsedMs = 1_000L,
        )
        assertEquals(80, strong.whitelistScorePercent)
        assertTrue(strong.hasFreshStrong(1_000L, aUnknown, "p"))
        assertFalse(strong.hasFreshStrong(1_000L, bKnown, "p"))
        val afterIgnore = foldReachabilityEvidence(
            previous = strong,
            incoming = ReachabilityEvidence(
                networkKey = aKnown,
                originNetworkKey = aKnown,
                profileId = "p",
                yandex = CheckOutcome.Timeout,
                seriesId = "ign",
            ),
            cellular = true,
            elapsedMs = 2_000L,
        )
        assertEquals(aUnknown, afterIgnore.originNetworkKey)
        assertEquals(80, afterIgnore.whitelistScorePercent)
        assertEquals(strong.strongAtElapsedMs, afterIgnore.strongAtElapsedMs)
        val adopted = adoptCellularEvidence(afterIgnore, bKnown)
        assertNull(adopted)
        val chain = whitelistEvidenceForUnderlay(
            evidence = afterIgnore,
            cellularEvidence = afterIgnore,
            key = bKnown,
            profileId = "p",
            nowElapsedMs = 3_000L,
        )
        assertNull(chain)
        assertFalse(afterIgnore.hasFreshStrong(3_000L, bKnown, "p"))
        assertFalse(
            RestrictionScore.mayEnterBypassForWhitelist(
                afterIgnore.historicalWhitelistScore(bKnown, "p"),
                afterIgnore.hasFreshStrong(3_000L, bKnown, "p"),
            ),
        )
        val onB = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = UnderlaySnapshot(
                    key = bKnown,
                    kind = UnderlayKind.Cellular,
                    availability = UnderlayAvailability.Usable,
                    handle = 9L,
                    simId = 11,
                    cellularConnected = true,
                    networkEpoch = 1L,
                ),
                evidence = afterIgnore,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
                elapsedMs = 3_000L,
                profileId = "p",
            ),
        )
        assertTrue(onB is AutoDecision.StartDirect)
        val measuredB = foldReachabilityEvidence(
            previous = null,
            incoming = positive(bKnown).copy(
                measuredAtElapsedMs = 4_000L,
                ruService = CheckOutcome.Success,
            ),
            cellular = true,
            elapsedMs = 4_000L,
        )
        assertTrue(measuredB.hasFreshStrong(4_000L, bKnown, "p"))
        val enterB = decideAutoPath(
            AutoPathInput(
                mode = ConnPathMode.Auto,
                underlay = UnderlaySnapshot(
                    key = bKnown,
                    kind = UnderlayKind.Cellular,
                    availability = UnderlayAvailability.Usable,
                    handle = 9L,
                    simId = 11,
                    cellularConnected = true,
                    networkEpoch = 1L,
                ),
                evidence = measuredB,
                currentPath = null,
                transport = TransportLifecycle.Stopped,
                hasCallHash = true,
                elapsedMs = 4_000L,
                profileId = "p",
            ),
        )
        assertTrue(enterB is AutoDecision.StartBypass)
    }

    @Test
    fun weakAfterUnknownOriginStrongDoesNotRewriteStrongOrigin() {
        val aUnknown = NetworkKey(1L, UnderlayKind.Cellular, 11, "a")
        val aKnown = NetworkKey(1L, UnderlayKind.Cellular, 11, "a", carrier = "25001")
        val bKnown = NetworkKey(9L, UnderlayKind.Cellular, 11, "b", carrier = "25001")
        val strong = foldReachabilityEvidence(
            previous = null,
            incoming = positive(aUnknown).copy(ruService = CheckOutcome.Success),
            cellular = true,
            elapsedMs = 1_000L,
        )
        val weak = foldReachabilityEvidence(
            previous = strong,
            incoming = ReachabilityEvidence(
                networkKey = aKnown,
                originNetworkKey = aKnown,
                profileId = "p",
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.NotRun,
                ruService = CheckOutcome.Success,
                seriesId = "w",
            ),
            cellular = true,
            elapsedMs = 2_000L,
        )
        assertEquals(aUnknown, weak.originNetworkKey)
        assertTrue(weak.hasFreshStrong(2_000L, aUnknown, "p"))
        assertFalse(weak.hasFreshStrong(2_000L, bKnown, "p"))
        repeat(3) { i ->
            val again = adoptCellularEvidence(weak, aKnown)
            assertEquals(aUnknown, again?.originNetworkKey)
            assertFalse(again!!.hasFreshStrong(3_000L + i, bKnown, "p"))
        }
        assertNull(adoptCellularEvidence(weak, bKnown))
    }
}
