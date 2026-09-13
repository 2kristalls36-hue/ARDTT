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
            ruService = CheckOutcome.Success,
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
    fun unreachableRussianServiceIsABadLinkNotAWhitelist() {
        listOf(CheckOutcome.Timeout, CheckOutcome.Refused, CheckOutcome.TransportFailure)
            .forEach { down ->
                assertEquals(
                    "ruService=$down",
                    RestrictionSample.Ignore,
                    RestrictionScore.sample(
                        cellular = true,
                        yandex = CheckOutcome.Success,
                        bigtech = CheckOutcome.Timeout,
                        google = CheckOutcome.Timeout,
                        ruService = down,
                    ),
                )
            }
        assertEquals(80, RestrictionScore.apply(80, RestrictionSample.Ignore))
    }

    @Test
    fun withoutARussianServiceVerdictTheRoundIsOnlyPartialEvidence() {
        listOf(CheckOutcome.NotRun, CheckOutcome.Cancelled).forEach { pending ->
            val sample = RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = pending,
            )
            assertEquals("ruService=$pending", RestrictionSample.WeakPositive, sample)
            val score = RestrictionScore.apply(0, sample)
            assertEquals(25, score)
            assertFalse(RestrictionScore.likely(score, alreadyBypass = false))
        }
    }

    @Test
    fun oneOrdinaryTargetBlockedStaysWeakEvenWithBothControlsUp() {
        assertEquals(
            RestrictionSample.WeakPositive,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.NotRun,
                ruService = CheckOutcome.Success,
            ),
        )
    }

    @Test
    fun anOrdinarySuccessStillOpensBeforeTheRussianServiceIsConsidered() {
        assertEquals(
            RestrictionSample.Open,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Timeout,
            ),
        )
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
            ruService = CheckOutcome.Success,
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

    @Test
    fun fourWeakRoundsFromZeroNeverEnterBypass() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        var evidence: ReachabilityEvidence? = null
        repeat(4) { i ->
            evidence = foldReachabilityEvidence(
                previous = evidence,
                incoming = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.NotRun,
                    ruService = CheckOutcome.Success,
                    seriesId = "w$i",
                    measuredAtElapsedMs = (i + 1) * 1_000L,
                ),
                cellular = true,
                elapsedMs = (i + 1) * 1_000L,
            )
        }
        val folded = evidence!!
        assertEquals(50, folded.whitelistScorePercent)
        assertEquals(RestrictionHint.Suspected, folded.restriction)
        assertFalse(RestrictionScore.mayEnterBypassForWhitelist(folded.whitelistScorePercent, folded.hasFreshStrong(4_000L, key, null)))
        assertFalse(RestrictionScore.likely(folded.whitelistScorePercent, alreadyBypass = false))
    }

    @Test
    fun weakAfterFreshStrongDoesNotClipToFiftyOrRefreshStrong() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val strong = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
                seriesId = "s",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        assertEquals(80, strong.whitelistScorePercent)
        assertEquals(10L, strong.strongAtElapsedMs)
        val weak = foldReachabilityEvidence(
            previous = strong,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.NotRun,
                ruService = CheckOutcome.Success,
                seriesId = "w",
            ),
            cellular = true,
            elapsedMs = 20L,
        )
        assertEquals(100, weak.whitelistScorePercent)
        assertEquals(10L, weak.strongAtElapsedMs)
        assertEquals(strong.strongUntilElapsedMs, weak.strongUntilElapsedMs)
        assertEquals(20L, weak.usableAtElapsedMs)
    }

    @Test
    fun ignoreDoesNotRefreshUsableOrStrongTimestamps() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val strong = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
                seriesId = "s",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        val ignore = foldReachabilityEvidence(
            previous = strong,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Timeout,
                bigtech = CheckOutcome.NotRun,
                google = CheckOutcome.NotRun,
                seriesId = "i",
            ),
            cellular = true,
            elapsedMs = 20L,
        )
        assertEquals(80, ignore.whitelistScorePercent)
        assertEquals(10L, strong.usableAtElapsedMs)
        assertEquals(strong.usableAtElapsedMs, ignore.usableAtElapsedMs)
        assertEquals(strong.strongAtElapsedMs, ignore.strongAtElapsedMs)
        assertEquals(strong.ttlUntilElapsedMs, ignore.ttlUntilElapsedMs)
        assertEquals(20L, ignore.observedAtElapsedMs)
        assertEquals(1, ignore.unknownStreak)
        val ttl = RecoverySettings.PROBE_RESTRICTION_TTL_MS
        assertTrue(strong.hasFreshStrong(10L + ttl - 1, key, null))
        assertFalse(ignore.hasFreshStrong(10L + ttl, key, null))
        assertEquals(0, ignore.whitelistScoreAt(key, null, nowElapsedMs = 10L + ttl))
        assertEquals(80, ignore.historicalWhitelistScore(key, null))
    }

    @Test
    fun ttlBoundaryIsExpiredAtValidUntil() {
        assertTrue(RecoverySettings.evidenceExpired(30_000L, 30_000L))
        assertFalse(RecoverySettings.evidenceExpired(29_999L, 30_000L))
        assertTrue(RecoverySettings.evidenceExpired(1L, 0L))
    }

    @Test
    fun yandexTimeoutWithTwoOrdinarySuccessesIsOpen() {
        val sample = RestrictionScore.sample(
            cellular = true,
            yandex = CheckOutcome.Timeout,
            bigtech = CheckOutcome.Success,
            google = CheckOutcome.Success,
        )
        assertEquals(RestrictionSample.Open, sample)
        assertEquals(0, RestrictionScore.apply(18, sample))
        val folded = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell"),
                yandex = CheckOutcome.Timeout,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Success,
            ),
            cellular = true,
            elapsedMs = 5L,
        )
        assertEquals(RestrictionHint.None, folded.restriction)
        assertEquals(0, folded.whitelistScorePercent)
    }

    @Test
    fun oneOrdinarySuccessWithoutYandexIsNotOpen() {
        assertEquals(
            RestrictionSample.Ignore,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Timeout,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Timeout,
            ),
        )
        assertEquals(
            RestrictionSample.Ignore,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.NotRun,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.NotRun,
            ),
        )
    }

    @Test
    fun expiredStrongPlusWeakIsNotAFreshConfirmation() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val ttl = RecoverySettings.PROBE_RESTRICTION_TTL_MS
        val strong = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
                seriesId = "s",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        val later = 10L + ttl
        val weak = foldReachabilityEvidence(
            previous = strong,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.NotRun,
                ruService = CheckOutcome.Success,
                seriesId = "w",
            ),
            cellular = true,
            elapsedMs = later,
        )
        assertFalse(weak.hasFreshStrong(later, key, null))
        assertTrue(weak.whitelistScorePercent <= RecoverySettings.WHITELIST_WEAK_ONLY_CAP_PERCENT)
        assertFalse(
            RestrictionScore.mayEnterBypassForWhitelist(
                weak.whitelistScorePercent,
                weak.hasFreshStrong(later, key, null),
            ),
        )
    }

    @Test
    fun lostNetworkDoesNotApplyPriorOrdinarySuccess() {
        val a = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell-a", carrier = "25001")
        val b = NetworkKey(2L, UnderlayKind.Cellular, 7, "cell-b", carrier = "25001")
        val open = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = a,
                yandex = CheckOutcome.Timeout,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Success,
                seriesId = "a",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        assertEquals(RestrictionSample.Open, RestrictionScore.sample(true, CheckOutcome.Timeout, CheckOutcome.Success, CheckOutcome.Success))
        val other = foldReachabilityEvidence(
            previous = open,
            incoming = ReachabilityEvidence(
                networkKey = b,
                yandex = CheckOutcome.Timeout,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Success,
                seriesId = "b",
            ),
            cellular = true,
            elapsedMs = 20L,
        )
        assertEquals(0, other.historicalWhitelistScore(a, null))
        assertEquals(0, open.whitelistScoreAt(b, null, nowElapsedMs = 20L))
    }

    @Test
    fun unknownBackoffStartsAtTwoSeconds() {
        assertEquals(
            2_000L,
            RecoverySettings.nextDiagnosticDelayMs(
                completedSeries = 1,
                restriction = RestrictionHint.Unknown,
                seriesCount = 0,
                unknownStreak = 1,
            ),
        )
        assertEquals(5_000L, RecoverySettings.unknownDiagnosticDelayMs(2))
        assertEquals(10_000L, RecoverySettings.unknownDiagnosticDelayMs(3))
        assertEquals(30_000L, RecoverySettings.unknownDiagnosticDelayMs(4))
        assertEquals(60_000L, RecoverySettings.unknownDiagnosticDelayMs(5))
        assertEquals(60_000L, RecoverySettings.unknownDiagnosticDelayMs(9))
        assertEquals(
            RecoverySettings.DIAGNOSTIC_RESTRICTION_REFRESH_MS,
            RecoverySettings.nextDiagnosticDelayMs(
                completedSeries = 2,
                restriction = RestrictionHint.Confirmed,
                seriesCount = 2,
                unknownStreak = 0,
                strongFresh = true,
                usableFresh = true,
                lastSample = RestrictionSample.Positive,
            ),
        )
        assertEquals(
            2_000L,
            RecoverySettings.nextDiagnosticDelayMs(
                completedSeries = 2,
                restriction = RestrictionHint.Confirmed,
                seriesCount = 2,
                unknownStreak = 1,
                strongFresh = false,
                lastSample = RestrictionSample.Ignore,
            ),
        )
    }

    @Test
    fun unknownStreakResetsOnOpenAndNotOnDuplicateNetworkKey() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val ignore = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Timeout,
                seriesId = "i1",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        assertEquals(1, ignore.unknownStreak)
        val again = foldReachabilityEvidence(
            previous = ignore,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Timeout,
                seriesId = "i2",
            ),
            cellular = true,
            elapsedMs = 20L,
        )
        assertEquals(2, again.unknownStreak)
        val open = foldReachabilityEvidence(
            previous = again,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Success,
                seriesId = "o",
            ),
            cellular = true,
            elapsedMs = 30L,
        )
        assertEquals(0, open.unknownStreak)
        assertEquals(RestrictionSample.Open, RestrictionScore.sample(
            true, CheckOutcome.Success, CheckOutcome.Success, CheckOutcome.Success,
        ))
    }

    @Test
    fun bindFailureDoesNotConfirmWhitelist() {
        assertEquals(
            RestrictionSample.Ignore,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.BindFailure,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
            ),
        )
        assertEquals(
            RestrictionSample.Ignore,
            RestrictionScore.sample(
                cellular = true,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.TlsFailure,
                google = CheckOutcome.NotRun,
                ruService = CheckOutcome.Success,
            ),
        )
    }

    @Test
    fun liveBypassHoldUsesExitThresholdNotEnter() {
        val scores = listOf(55, 62, 64, 79, 80)
        for (score in scores) {
            assertTrue(
                "stale score $score should hold Bypass",
                RestrictionScore.bypassHoldsDirectReeval(
                    historicalScore = score,
                    freshStrong = false,
                    usable = false,
                    unknownStreak = 3,
                    alreadyBypass = true,
                ),
            )
            assertFalse(
                "stale score $score after 4 unknowns should not hold",
                RestrictionScore.bypassHoldsDirectReeval(
                    historicalScore = score,
                    freshStrong = false,
                    usable = false,
                    unknownStreak = 4,
                    alreadyBypass = true,
                ),
            )
        }
        assertFalse(
            RestrictionScore.bypassHoldsDirectReeval(
                historicalScore = 54,
                freshStrong = false,
                usable = true,
                unknownStreak = 0,
                alreadyBypass = true,
            ),
        )
        assertTrue(
            RestrictionScore.bypassHoldsDirectReeval(
                historicalScore = 55,
                freshStrong = true,
                usable = true,
                unknownStreak = 9,
                alreadyBypass = true,
            ),
        )
        assertFalse(
            RestrictionScore.mayEnterBypassForWhitelist(80, freshStrongConfirmation = false),
        )
        assertFalse(
            RestrictionScore.mayEnterBypassForWhitelist(100, freshStrongConfirmation = false),
        )
    }

    @Test
    fun sixWeakRoundsStayOnRestrictionRefresh() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        var ev: ReachabilityEvidence? = null
        repeat(6) { i ->
            ev = foldReachabilityEvidence(
                previous = ev,
                incoming = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Success,
                    bigtech = CheckOutcome.Timeout,
                    google = CheckOutcome.NotRun,
                    ruService = CheckOutcome.Success,
                    seriesId = "w$i",
                ),
                cellular = true,
                elapsedMs = (i + 1) * 1_000L,
            )
            val now = (i + 1) * 1_000L
            assertEquals(RestrictionSample.WeakPositive, ev!!.lastSample)
            assertTrue(ev!!.whitelistScorePercent <= RecoverySettings.WHITELIST_WEAK_ONLY_CAP_PERCENT)
            assertFalse(ev!!.hasFreshStrong(now, key, null))
            assertEquals(
                RecoverySettings.DIAGNOSTIC_RESTRICTION_REFRESH_MS,
                RecoverySettings.nextDiagnosticDelayMs(ev, now, key, null),
            )
        }
    }

    @Test
    fun ignoreBackoffThenWeakThenIgnoreRestartsAtTwoSeconds() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val expected = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L, 60_000L)
        var ev: ReachabilityEvidence? = null
        expected.forEachIndexed { i, delay ->
            ev = foldReachabilityEvidence(
                previous = ev,
                incoming = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Timeout,
                    seriesId = "i$i",
                ),
                cellular = true,
                elapsedMs = (i + 1) * 1_000L,
            )
            assertEquals(delay, RecoverySettings.nextDiagnosticDelayMs(ev, (i + 1) * 1_000L, key, null))
        }
        val weak = foldReachabilityEvidence(
            previous = ev,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.NotRun,
                ruService = CheckOutcome.Success,
                seriesId = "w",
            ),
            cellular = true,
            elapsedMs = 8_000L,
        )
        assertEquals(0, weak.unknownStreak)
        assertEquals(
            RecoverySettings.DIAGNOSTIC_RESTRICTION_REFRESH_MS,
            RecoverySettings.nextDiagnosticDelayMs(weak, 8_000L, key, null),
        )
        val ignoreAgain = foldReachabilityEvidence(
            previous = weak,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Timeout,
                seriesId = "i-next",
            ),
            cellular = true,
            elapsedMs = 9_000L,
        )
        assertEquals(1, ignoreAgain.unknownStreak)
        assertEquals(2_000L, RecoverySettings.nextDiagnosticDelayMs(ignoreAgain, 9_000L, key, null))
    }

    @Test
    fun openSampleUsesOpenScheduleAndStaleSuspectedIsNotTwentyFiveSeconds() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val open = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Success,
                seriesId = "o",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        assertEquals(0, open.unknownStreak)
        assertEquals(
            RecoverySettings.DIAGNOSTIC_OPEN_INTERVAL_MS,
            RecoverySettings.nextDiagnosticDelayMs(open, 10L, key, null),
        )
        val stale = open.copy(
            restriction = RestrictionHint.Suspected,
            lastSample = RestrictionSample.WeakPositive,
            ttlUntilElapsedMs = 10L,
            strongUntilElapsedMs = 10L,
            usableAtElapsedMs = 10L,
        )
        assertEquals(
            2_000L,
            RecoverySettings.nextDiagnosticDelayMs(stale, 10L, key, null),
        )
    }

    @Test
    fun positiveThenOpenThenTtlThenFourUnknownReleasesBypassHold() {
        val key = NetworkKey(1L, UnderlayKind.Cellular, 7, "cell", carrier = "25001")
        val strong = foldReachabilityEvidence(
            previous = null,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Timeout,
                google = CheckOutcome.Timeout,
                ruService = CheckOutcome.Success,
                seriesId = "s",
            ),
            cellular = true,
            elapsedMs = 10L,
        )
        val opened = foldReachabilityEvidence(
            previous = strong,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Success,
                bigtech = CheckOutcome.Success,
                google = CheckOutcome.Success,
                seriesId = "o",
            ),
            cellular = true,
            elapsedMs = 20L,
        )
        assertEquals(62, opened.whitelistScorePercent)
        val expiredAt = opened.usableUntilElapsedMs()
        assertTrue(RecoverySettings.evidenceExpired(expiredAt, opened.usableUntilElapsedMs()))
        var ev = opened
        repeat(3) { i ->
            ev = foldReachabilityEvidence(
                previous = ev,
                incoming = ReachabilityEvidence(
                    networkKey = key,
                    yandex = CheckOutcome.Timeout,
                    seriesId = "u$i",
                ),
                cellular = true,
                elapsedMs = expiredAt + i + 1,
            )
            assertTrue(
                RestrictionScore.bypassHoldsDirectReeval(
                    historicalScore = ev!!.historicalWhitelistScore(key, null),
                    freshStrong = ev!!.hasFreshStrong(expiredAt + i + 1, key, null),
                    usable = ev!!.usableAt(expiredAt + i + 1, key, null),
                    unknownStreak = ev!!.unknownStreak,
                    alreadyBypass = true,
                ),
            )
        }
        ev = foldReachabilityEvidence(
            previous = ev,
            incoming = ReachabilityEvidence(
                networkKey = key,
                yandex = CheckOutcome.Timeout,
                seriesId = "u4",
            ),
            cellular = true,
            elapsedMs = expiredAt + 4,
        )
        assertFalse(
            RestrictionScore.bypassHoldsDirectReeval(
                historicalScore = ev!!.historicalWhitelistScore(key, null),
                freshStrong = ev!!.hasFreshStrong(expiredAt + 4, key, null),
                usable = ev!!.usableAt(expiredAt + 4, key, null),
                unknownStreak = ev!!.unknownStreak,
                alreadyBypass = true,
            ),
        )
    }
}
