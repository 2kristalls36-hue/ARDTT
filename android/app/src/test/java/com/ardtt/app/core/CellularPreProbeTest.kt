package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
