package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionScoreTest {
    @Test
    fun firstPositiveSampleReachesEnterThreshold() {
        val sample = RestrictionScore.sample(
            cellular = true,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.Timeout,
        )
        assertEquals(RestrictionSample.Positive, sample)
        val score = RestrictionScore.apply(0, sample)
        assertEquals(RecoverySettings.WHITELIST_ENTER_PERCENT, score)
        assertEquals(RestrictionHint.Confirmed, RestrictionScore.hint(score, sample))
        assertTrue(RestrictionScore.likely(score, alreadyBypass = false))
    }

    @Test
    fun weakPositiveDoesNotEnterBypass() {
        val sample = RestrictionScore.sample(
            cellular = true,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.NotRun,
        )
        assertEquals(RestrictionSample.WeakPositive, sample)
        val score = RestrictionScore.apply(0, sample)
        assertEquals(25, score)
        assertEquals(RestrictionHint.Suspected, RestrictionScore.hint(score, sample))
        assertFalse(RestrictionScore.likely(score, alreadyBypass = false))
    }

    @Test
    fun oneOpenSampleDoesNotDropLiveBypass() {
        val afterOpen = RestrictionScore.apply(80, RestrictionSample.Open)
        assertEquals(62, afterOpen)
        assertTrue(RestrictionScore.likely(afterOpen, alreadyBypass = true))
        assertFalse(RestrictionScore.likely(afterOpen, alreadyBypass = false))
        val afterTwoOpens = RestrictionScore.apply(afterOpen, RestrictionSample.Open)
        assertEquals(44, afterTwoOpens)
        assertFalse(RestrictionScore.likely(afterTwoOpens, alreadyBypass = true))
    }

    @Test
    fun ignoreLeavesScoreUnchanged() {
        assertEquals(
            RestrictionSample.Ignore,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.TlsFailure,
                google = CheckOutcome.Timeout,
            ),
        )
        assertEquals(80, RestrictionScore.apply(80, RestrictionSample.Ignore))
        assertEquals(
            RestrictionHint.Unknown,
            RestrictionScore.hint(0, RestrictionSample.Ignore),
        )
    }

    @Test
    fun foldKeepsScoreAcrossFlakyOpenThenPositive() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell")
        val positive = ReachabilityEvidence(
            networkKey = key,
            yandex = CheckOutcome.Success,
            bigtech = CheckOutcome.Timeout,
            google = CheckOutcome.Timeout,
        )
        val first = foldReachabilityEvidence(null, positive, cellular = true, elapsedMs = 10L)
        assertEquals(80, first.whitelistScorePercent)
        val open = positive.copy(
            bigtech = CheckOutcome.Success,
            seriesId = "b",
            measuredAtElapsedMs = 20L,
        )
        val afterOpen = foldReachabilityEvidence(first, open, cellular = true, elapsedMs = 20L)
        assertEquals(62, afterOpen.whitelistScorePercent)
        assertEquals(RestrictionHint.Suspected, afterOpen.restriction)
        val again = foldReachabilityEvidence(
            afterOpen,
            positive.copy(seriesId = "c", measuredAtElapsedMs = 30L),
            cellular = true,
            elapsedMs = 30L,
        )
        assertEquals(100, again.whitelistScorePercent)
        assertEquals(RestrictionHint.Confirmed, again.restriction)
    }
}
