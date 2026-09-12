package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistDetectionTest {
    private val cellKey = NetworkKey(1L, UnderlayKind.Cellular, 11, "cell")
    private val wifiKey = NetworkKey(2L, UnderlayKind.Wifi, null, "wifi")

    @Test
    fun onlyCellularIsScored() {
        assertTrue(WhitelistDetection.appliesTo(UnderlayKind.Cellular))
        assertFalse(WhitelistDetection.appliesTo(UnderlayKind.Wifi))
        assertFalse(WhitelistDetection.appliesTo(UnderlayKind.Ethernet))
        assertFalse(WhitelistDetection.appliesTo(UnderlayKind.Other))
        assertTrue(WhitelistDetection.appliesTo(cellKey))
        assertFalse(WhitelistDetection.appliesTo(wifiKey))
        assertFalse(WhitelistDetection.appliesTo(null))
    }

    @Test
    fun aStubbedTransportNeverReportsAScore() {
        // Evidence measured on cellular must not leak into a Wi-Fi verdict.
        val cellular = ReachabilityEvidence(
            networkKey = cellKey,
            profileId = "p",
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.Timeout,
            restriction = RestrictionHint.Confirmed,
            whitelistScorePercent = 80,
        )
        assertEquals(80, cellular.whitelistScoreAt(cellKey, "p"))
        assertEquals(
            WhitelistDetection.STUB_SCORE_PERCENT,
            cellular.whitelistScoreAt(wifiKey, "p"),
        )
    }

    @Test
    fun foldingAWifiRoundStubsTheVerdict() {
        val folded = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = wifiKey,
                profileId = "p",
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
            ),
            cellular = false,
            elapsedMs = 0L,
        )
        assertEquals(WhitelistDetection.STUB_SCORE_PERCENT, folded.whitelistScorePercent)
        assertEquals(WhitelistDetection.stubRestriction, folded.restriction)
        assertFalse(RestrictionScore.likely(folded.whitelistScorePercent, alreadyBypass = true))
    }

    @Test
    fun preProbeFollowsTheSameSwitch() {
        assertTrue(shouldPreProbeCellular(ConnPathMode.Auto, UnderlayKind.Wifi))
        assertTrue(shouldPreProbeCellular(ConnPathMode.Auto, UnderlayKind.Ethernet))
        assertFalse(shouldPreProbeCellular(ConnPathMode.Auto, UnderlayKind.Cellular))
        assertFalse(shouldPreProbeCellular(ConnPathMode.Direct, UnderlayKind.Wifi))
    }
}
