package com.ardtt.app.core

/**
 * Central budgets for Auto / recovery. Protocol timeouts (VK, RAW handshake)
 * stay in their own modules — these values are not a global 2s cap.
 */
object RecoverySettings {
    const val FAST_PROBE_BUDGET_MS = 1_500L
    const val DIAGNOSTIC_ROUND_MS = 1_500L
    const val DIAGNOSTIC_SERIES_GAP_MS = 8_000L
    /** Adaptive idle interval after a clean open-LTE diagnostic burst. */
    const val DIAGNOSTIC_OPEN_INTERVAL_MS = 5 * 60_000L
    /** Refresh while restriction is suspected/confirmed (aligned with TTL). */
    const val DIAGNOSTIC_RESTRICTION_REFRESH_MS = 25_000L
    const val CONFIRM_PROBE_BUDGET_MS = 5_000L
    const val RESTRICTION_CONFIRM_SERIES = 2
    /** First complete БС sample (Yandex OK + both ordinary blocked) reaches enter. */
    const val WHITELIST_SCORE_RISE = 80
    /** One ordinary target blocked, the other not a success. */
    const val WHITELIST_SCORE_WEAK = 25
    /** Ordinary internet visible — decay, do not zero the score. */
    const val WHITELIST_SCORE_FALL = -18
    /** Score at or above this is treated as operator whitelist (БС). Tunable. */
    const val WHITELIST_ENTER_PERCENT = 80
    /** Stay on Bypass until the score falls below this (hysteresis). */
    const val WHITELIST_EXIT_PERCENT = 55
    /**
     * Weak-only accumulation never reaches enter. Four +25 rounds stay at 50
     * unless a fresh strong confirmation is still inside TTL.
     */
    const val WHITELIST_WEAK_ONLY_CAP_PERCENT = 50
    /** Consecutive Ignore/unknown rounds before a live Bypass may re-try Direct. */
    const val WHITELIST_UNKNOWN_DIRECT_TRY_STREAK = 4
    const val PROBE_CACHE_TTL_MS = 30_000L
    const val STABILIZE_AFTER_GAP_MS = 1_500L
    const val FIRST_WIFI_DIRECT_DELAY_MS = 0L
    const val WIFI_STABLE_CONFIRMATIONS = 2
    const val WIFI_DEGRADED_FAILS_BEFORE_HYSTERESIS = 2

    /**
     * A live Bypass is not abandoned the instant Wi‑Fi associates. Switching
     * costs a parked VK call and a TUN rebuild, so an access point at the edge
     * of range has to hold a usable underlay this long first.
     */
    const val WIFI_UPGRADE_SETTLE_MS = 6_000L
    const val WIFI_UPGRADE_SETTLE_MAX_MS = 60_000L

    /** Each failed episode on this Wi‑Fi doubles the wait. */
    fun wifiUpgradeSettleMs(wifiFailStreak: Int): Long {
        val shift = wifiFailStreak.coerceIn(0, 4)
        return (WIFI_UPGRADE_SETTLE_MS shl shift).coerceAtMost(WIFI_UPGRADE_SETTLE_MAX_MS)
    }
    const val DIRECT_LIMITED_TRY_MS = 8_000L
    const val DIRECT_LIMITED_TRY_AFTER_HANDOFF_MS = 4_000L
    const val NETWORK_RETURN_COALESCE_MS = 400L
    const val DIRECT_REEVAL_WHILE_BYPASS_MS = 30_000L
    const val PROBE_RESTRICTION_TTL_MS = 30_000L
    /** Max completed diagnostic rounds in the initial open-LTE burst. */
    const val DIAGNOSTIC_OPEN_BURST_SERIES = 1

    /** Transient VK OAuth/calls.start failures before asking the user. */
    const val CALL_RECREATE_NETWORK_ATTEMPTS = 3

    val retryBackoffMs: LongArray = longArrayOf(
        2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L,
    )

    fun retryDelayMs(failureIndex: Int, jitterPermille: Int = 0): Long {
        val idx = failureIndex.coerceAtLeast(0)
        val base = if (idx < retryBackoffMs.size) {
            retryBackoffMs[idx]
        } else {
            60_000L
        }
        if (jitterPermille == 0) return base
        val delta = base * jitterPermille / 1000L
        return (base + delta).coerceAtLeast(0L)
    }

    /**
     * Gap between Direct re-checks while Auto sits on Bypass. Each re-check
     * parks the VK call and costs [DIRECT_LIMITED_TRY_MS] of downtime, so an
     * underlay where Direct is simply blocked must not be probed every 30s
     * forever. Reset by a confirmed Direct or a new underlay.
     */
    val directReevalBackoffMs: LongArray = longArrayOf(
        DIRECT_REEVAL_WHILE_BYPASS_MS, 60_000L, 120_000L, 300_000L, 600_000L,
    )

    fun directReevalDelayMs(failedReevals: Int): Long =
        directReevalBackoffMs[failedReevals.coerceIn(0, directReevalBackoffMs.lastIndex)]

    /**
     * How long Direct stays "failed on this underlay" after an attempt.
     * Auto cellular with a call hash parks on Bypass and must not bounce
     * back to Direct on the 2s same-path retry budget.
     */
    fun directNegativeRetryAfterElapsedMs(
        elapsedMs: Long,
        failureIndex: Int,
        jitterPermille: Int = 0,
        holdForBypassReeval: Boolean,
        failedReevals: Int = 0,
    ): Long {
        val backoff = retryDelayMs(failureIndex, jitterPermille)
        val hold = if (holdForBypassReeval) directReevalDelayMs(failedReevals) else 0L
        return elapsedMs + maxOf(backoff, hold)
    }

    /** Handshake on this attempt is protocol-ready, not PathConfirmed. */
    fun directProtocolReady(handshakeSec: Long): Boolean = handshakeSec > 0L

    /**
     * WireGuard REJECT_AFTER_TIME: a peer with traffic flowing rekeys within
     * ~120s, so a handshake older than this is not evidence of a live peer.
     * Without the age bound one successful handshake made Direct look alive
     * forever while the operator blackholed the data plane.
     */
    const val DIRECT_HANDSHAKE_LIVE_MAX_SEC = 180L

    /** [handshakeSec] and [nowSec] are wall-clock seconds (AWG IPC uses epoch). */
    fun directHandshakeLive(
        handshakeSec: Long,
        nowSec: Long,
        maxAgeSec: Long = DIRECT_HANDSHAKE_LIVE_MAX_SEC,
    ): Boolean {
        if (handshakeSec <= 0L) return false
        return nowSec - handshakeSec <= maxAgeSec
    }

    /**
     * One AWG handshake response is ~92–160 B and keepalives are 32 B. They are
     * delivered even by a cell that blackholes the data plane, so anything at or
     * below this is protocol chatter, not payload.
     */
    const val DIRECT_HANDSHAKE_RX_MAX_BYTES = 1024L

    fun directRxLooksLikeData(rxDeltaSinceAnchor: Long): Boolean =
        rxDeltaSinceAnchor > DIRECT_HANDSHAKE_RX_MAX_BYTES

    /** Useful RX from the current AWG backend is PathConfirmed. */
    fun directPathLooksConfirmed(totalRx: Long, handshakeSec: Long): Boolean =
        directRxLooksLikeData(totalRx)

    /**
     * Consecutive unknown/Ignore rounds: 2s, 5s, 10s, 30s, then 60s forever.
     * Starting values for tuning, not a measured optimum.
     */
    val diagnosticUnknownBackoffMs: LongArray = longArrayOf(
        2_000L, 5_000L, 10_000L, 30_000L, 60_000L,
    )

    fun unknownDiagnosticDelayMs(unknownStreak: Int): Long {
        val idx = (unknownStreak - 1).coerceAtLeast(0)
            .coerceAtMost(diagnosticUnknownBackoffMs.lastIndex)
        return diagnosticUnknownBackoffMs[idx]
    }

    /** `now >= validUntil` is expired. A missing timestamp is never infinitely fresh. */
    fun evidenceExpired(nowElapsedMs: Long, validUntilElapsedMs: Long): Boolean {
        if (validUntilElapsedMs <= 0L) return true
        return nowElapsedMs >= validUntilElapsedMs
    }

    /**
     * Next diagnostic delay for cellular. The last accepted sample, usable
     * freshness and strong freshness are independent of the UI restriction
     * label: a leftover Suspected name without usable evidence is not a 25s
     * refresh, and a Weak round is not an Unknown backoff.
     */
    fun nextDiagnosticDelayMs(
        completedSeries: Int,
        restriction: RestrictionHint,
        @Suppress("UNUSED_PARAMETER") seriesCount: Int,
        unknownStreak: Int = 0,
        strongFresh: Boolean = false,
        usableFresh: Boolean = false,
        lastSample: RestrictionSample = RestrictionSample.Ignore,
    ): Long? {
        return when (lastSample) {
            RestrictionSample.Ignore ->
                unknownDiagnosticDelayMs(unknownStreak.coerceAtLeast(1))
            RestrictionSample.Open ->
                if (restriction != RestrictionHint.None && !usableFresh) {
                    unknownDiagnosticDelayMs(unknownStreak.coerceAtLeast(1))
                } else if (completedSeries < DIAGNOSTIC_OPEN_BURST_SERIES) {
                    DIAGNOSTIC_SERIES_GAP_MS
                } else {
                    DIAGNOSTIC_OPEN_INTERVAL_MS
                }
            RestrictionSample.WeakPositive,
            RestrictionSample.Positive,
            -> if (usableFresh || strongFresh) {
                DIAGNOSTIC_RESTRICTION_REFRESH_MS
            } else {
                unknownDiagnosticDelayMs(unknownStreak.coerceAtLeast(1))
            }
        }
    }

    fun nextDiagnosticDelayMs(
        evidence: ReachabilityEvidence?,
        nowElapsedMs: Long,
        key: NetworkKey?,
        profileId: String?,
    ): Long? {
        if (evidence == null) return unknownDiagnosticDelayMs(1)
        return nextDiagnosticDelayMs(
            completedSeries = evidence.completedSeries,
            restriction = evidence.restrictionAt(nowElapsedMs, key, profileId),
            seriesCount = evidence.seriesCount,
            unknownStreak = evidence.unknownStreak,
            strongFresh = evidence.hasFreshStrong(nowElapsedMs, key, profileId),
            usableFresh = evidence.usableAt(nowElapsedMs, key, profileId),
            lastSample = evidence.lastSample,
        )
    }
}
