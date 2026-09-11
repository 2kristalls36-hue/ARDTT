package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryTimerTest {
    @Test
    fun scheduleReplacesPreviousAndFiringRunsLatestAction() {
        var fires = 0
        val pending = mutableListOf<() -> Unit>()
        val timer = RecoveryTimer(
            arm = { _, fire -> pending.add(fire) },
            cancel = { pending.clear() },
        )
        timer.schedule(1_000L) { fires += 1 }
        timer.schedule(RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS) { fires += 10 }
        assertEquals(1, pending.size)
        assertEquals(RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS, timer.pendingDelayMs)
        pending.forEach { it() }
        assertEquals(10, fires)
        assertNull(timer.pendingDelayMs)
    }

    @Test
    fun expiredNegativeEvidenceCoalescesInsteadOfBusyLoop() {
        assertEquals(
            RecoverySettings.NETWORK_RETURN_COALESCE_MS,
            bypassReevalDelayMs(retryAfterElapsedMs = 10L, elapsedMs = 20L),
        )
        assertEquals(
            5_000L,
            bypassReevalDelayMs(retryAfterElapsedMs = 15_000L, elapsedMs = 10_000L),
        )
        assertEquals(
            RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS,
            bypassReevalDelayMs(retryAfterElapsedMs = null, elapsedMs = 0L),
        )
    }

    @Test
    fun stayReevalNeverFiresSoonerThanTheBaseGap() {
        // Expired hold: a Stay has no fresh handshake to coalesce against.
        assertEquals(
            RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS,
            stayReevalDelayMs(retryAfterElapsedMs = 10L, elapsedMs = 20L),
        )
        assertEquals(
            RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS,
            stayReevalDelayMs(retryAfterElapsedMs = null, elapsedMs = 20L),
        )
        // Escalated hold wins once it is further out than the base gap.
        assertEquals(
            RecoverySettings.directReevalDelayMs(3),
            stayReevalDelayMs(
                retryAfterElapsedMs = 1_000L + RecoverySettings.directReevalDelayMs(3),
                elapsedMs = 1_000L,
            ),
        )
    }

    @Test
    fun directReevalGapWidensAndSaturates() {
        assertEquals(
            RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS,
            RecoverySettings.directReevalDelayMs(0),
        )
        assertEquals(RecoverySettings.directReevalDelayMs(0), RecoverySettings.directReevalDelayMs(-1))
        val steps = (0..6).map { RecoverySettings.directReevalDelayMs(it) }
        assertEquals(steps.sorted(), steps)
        assertEquals(steps.last(), RecoverySettings.directReevalDelayMs(99))
        assertEquals(
            600_000L,
            RecoverySettings.directReevalDelayMs(RecoverySettings.directReevalBackoffMs.lastIndex),
        )
    }

    @Test
    fun autoCellularDirectNegativeUsesBypassReevalHold() {
        assertEquals(
            30_000L,
            RecoverySettings.directNegativeRetryAfterElapsedMs(
                elapsedMs = 0L,
                failureIndex = 0,
                holdForBypassReeval = true,
            ),
        )
        assertEquals(
            2_000L,
            RecoverySettings.directNegativeRetryAfterElapsedMs(
                elapsedMs = 0L,
                failureIndex = 0,
                holdForBypassReeval = false,
            ),
        )
    }
}
