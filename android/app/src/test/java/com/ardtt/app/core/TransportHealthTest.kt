package com.ardtt.app.core

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

    @Test
    fun storesDownUpBytesFromStatsLine() {
        TransportHealth.onLogLine("[СТАТИСТИКА] Активных: 2 | Трафик: 1.50 МБ | ↓1.00 МБ / ↑0.50 МБ")
        assertEquals((1.0 * 1024 * 1024).toLong(), TransportHealth.downBytes)
        assertEquals((0.5 * 1024 * 1024).toLong(), TransportHealth.upBytes)
        assertEquals(1024L + 512L, TransportHealth.trafficKb)
    }

    @Test
    fun freshInboundRequiresDownGrowthNotTxOrZeroTicks() {
        TransportHealth.onLogLine("[СТАТИСТИКА] Активных: 2 | Трафик: 0.00 МБ | ↓0.00 МБ / ↑0.00 МБ")
        assertEquals(0L, TransportHealth.lastInboundGrowthAtMs)
        assertEquals(0L, TransportHealth.lastTrafficGrowthAtMs)
        assertFalse(TransportHealth.hasFreshInboundSince(0L))

        TransportHealth.onLogLine("[СТАТИСТИКА] Активных: 2 | Трафик: 0.20 МБ | ↓0.00 МБ / ↑0.20 МБ")
        assertTrue(TransportHealth.lastTrafficGrowthAtMs > 0L)
        assertEquals(0L, TransportHealth.lastInboundGrowthAtMs)
        assertFalse(TransportHealth.hasFreshInboundSince(0L))
        assertTrue(TransportHealth.hasFreshTrafficSince(0L))

        TransportHealth.onLogLine("[СТАТИСТИКА] Активных: 2 | Трафик: 0.50 МБ | ↓0.30 МБ / ↑0.20 МБ")
        assertTrue(TransportHealth.lastInboundGrowthAtMs > 0L)
        val afterDown = TransportHealth.lastInboundGrowthAtMs
        assertTrue(TransportHealth.hasFreshInboundSince(afterDown - 10L))
        assertFalse(TransportHealth.hasFreshInboundSince(afterDown + 10L))
        TransportHealth.reset()
        assertFalse(TransportHealth.hasFreshInboundSince(0L))
    }

    @Test
    fun parsesStructuredTelemetryAndControlAck() {
        TransportHealth.applyStructuredTelemetry(
            "channels=2|tunGen=4|tunWriteOk=9|tunWriteErr=1|down=1200|up=80",
        )
        assertEquals(2, TransportHealth.activeWorkers)
        assertEquals(4L, TransportHealth.tunGen)
        assertEquals(9L, TransportHealth.tunWriteOk)
        assertEquals(1L, TransportHealth.tunWriteErr)
        assertEquals(1200L, TransportHealth.exactDownBytes)
        TransportHealth.applyControlAck(
            "V1|k1|1|ACK|GET_TELEMETRY|ok|stage=running|channels=3|tunGen=4|tunWriteOk=10|down=1500|up=90",
        )
        assertEquals(3, TransportHealth.activeWorkers)
        assertEquals(10L, TransportHealth.tunWriteOk)
        assertEquals(1500L, TransportHealth.exactDownBytes)
        assertTrue(1500L < 0.01 * 1024 * 1024)
    }
}
