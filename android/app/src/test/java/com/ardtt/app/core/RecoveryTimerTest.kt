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
}
