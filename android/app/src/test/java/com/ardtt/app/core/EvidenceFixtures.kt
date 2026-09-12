package com.ardtt.app.core

/** Fresh strong confirmation still inside TTL at [atElapsedMs]. */
internal fun ReachabilityEvidence.withFreshStrongTtl(
    atElapsedMs: Long = 1L,
    ttlMs: Long = RecoverySettings.PROBE_RESTRICTION_TTL_MS,
): ReachabilityEvidence = copy(
    measuredAtElapsedMs = atElapsedMs,
    observedAtElapsedMs = atElapsedMs,
    usableAtElapsedMs = atElapsedMs,
    strongAtElapsedMs = atElapsedMs,
    ttlUntilElapsedMs = atElapsedMs + ttlMs,
    strongUntilElapsedMs = atElapsedMs + ttlMs,
)
