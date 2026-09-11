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
    fun anUnreadableOperatorKeepsTheScore() {
        assertTrue(mts.sameCarrier(unknownCarrier))
        assertTrue(unknownCarrier.sameCarrier(mts))
        assertEquals(80, scored(mts).whitelistScoreAt(unknownCarrier, "p"))
        assertEquals(80, scored(unknownCarrier).whitelistScoreAt(mts, "p"))
    }

    @Test
    fun negativeDirectEvidenceAndStashDoNotCrossOperators() {
        val negative = DirectNegativeEvidence(key = mts, retryAfterElapsedMs = 60_000L)
        assertTrue(negative.stillBlocks(0L, mts, null))
        assertFalse(negative.stillBlocks(0L, beeline, null))

        assertNull(adoptCellularEvidence(scored(mts), beeline))
        assertEquals(beeline, adoptCellularEvidence(scored(unknownCarrier), beeline)?.networkKey)
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
}

class CellularPreProbeTest {
    private val cellA = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell-a")
    private val cellBSameSim = NetworkKey(9L, UnderlayKind.Cellular, 11, "cell-b")
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
    fun adoptRebindsHandleForSameSim() {
        val adopted = adoptCellularEvidence(positive(cellA), cellBSameSim)
        assertNotNull(adopted)
        assertEquals(cellBSameSim, adopted?.networkKey)
        assertEquals(80, adopted?.whitelistScorePercent)
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
            cellularEvidence = positive(cellA),
            key = cellBSameSim,
            profileId = "p",
        )
        assertEquals(80, chosen?.whitelistScorePercent)
        assertEquals(cellBSameSim, chosen?.networkKey)
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
}
