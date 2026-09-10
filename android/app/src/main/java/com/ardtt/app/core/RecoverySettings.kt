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
    const val PROBE_CACHE_TTL_MS = 30_000L
    const val STABILIZE_AFTER_GAP_MS = 1_500L
    const val FIRST_WIFI_DIRECT_DELAY_MS = 0L
    const val WIFI_STABLE_CONFIRMATIONS = 2
    const val WIFI_DEGRADED_FAILS_BEFORE_HYSTERESIS = 2
    const val DIRECT_LIMITED_TRY_MS = 8_000L
    const val DIRECT_LIMITED_TRY_AFTER_HANDOFF_MS = 4_000L
    const val NETWORK_RETURN_COALESCE_MS = 400L
    const val DIRECT_REEVAL_WHILE_BYPASS_MS = 30_000L
    const val PROBE_RESTRICTION_TTL_MS = 30_000L
    /** Max completed diagnostic rounds in the initial open-LTE burst. */
    const val DIAGNOSTIC_OPEN_BURST_SERIES = 1

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

    /** Handshake on this attempt is protocol-ready, not PathConfirmed. */
    fun directProtocolReady(handshakeSec: Long): Boolean = handshakeSec > 0L

    /** Useful RX from the current AWG backend is PathConfirmed. */
    fun directPathLooksConfirmed(totalRx: Long, handshakeSec: Long): Boolean =
        totalRx > 0L

    /**
     * Next diagnostic delay for cellular. Restriction confidence ([seriesCount]) is
     * separate from completed work ([completedSeries]).
     */
    fun nextDiagnosticDelayMs(
        completedSeries: Int,
        restriction: RestrictionHint,
        seriesCount: Int,
    ): Long? {
        return when (restriction) {
            RestrictionHint.Suspected, RestrictionHint.Confirmed ->
                DIAGNOSTIC_RESTRICTION_REFRESH_MS
            RestrictionHint.None ->
                if (completedSeries < DIAGNOSTIC_OPEN_BURST_SERIES) {
                    DIAGNOSTIC_SERIES_GAP_MS
                } else {
                    DIAGNOSTIC_OPEN_INTERVAL_MS
                }
            RestrictionHint.Unknown ->
                if (completedSeries < DIAGNOSTIC_OPEN_BURST_SERIES) {
                    DIAGNOSTIC_SERIES_GAP_MS
                } else if (seriesCount > 0 && seriesCount < RESTRICTION_CONFIRM_SERIES) {
                    DIAGNOSTIC_SERIES_GAP_MS
                } else {
                    DIAGNOSTIC_OPEN_INTERVAL_MS
                }
        }
    }
}
