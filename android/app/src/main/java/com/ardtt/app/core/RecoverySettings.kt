package com.ardtt.app.core

/**
 * Central budgets for Auto / recovery. Protocol timeouts (VK, RAW handshake)
 * stay in their own modules — these values are not a global 2s cap.
 */
object RecoverySettings {
    const val FAST_PROBE_BUDGET_MS = 2_000L
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
}
