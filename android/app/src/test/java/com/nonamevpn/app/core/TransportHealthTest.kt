package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransportHealthTest {
    @Before
    fun reset() {
        TransportHealth.reset()
    }

    @Test
    fun parsesActiveWorkersFromStatsLine() {
        TransportHealth.onLogLine("[СТАТИСТИКА] Активных: 3 | Трафик: 1.00 МБ | ↓0.50 МБ / ↑0.50 МБ")
        assertEquals(3, TransportHealth.activeWorkers)
        assertTrue(TransportHealth.lastStatsAtMs > 0L)
        assertTrue(TransportHealth.backendAlive)
    }

    @Test
    fun ignoresUnrelatedLines() {
        TransportHealth.onLogLine("hello")
        assertEquals(0, TransportHealth.activeWorkers)
        assertFalse(TransportHealth.hasFreshStatsSince(0L))
    }
}
