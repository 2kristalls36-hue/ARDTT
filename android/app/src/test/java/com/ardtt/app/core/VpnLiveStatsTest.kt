package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnLiveStatsTest {
    @Test
    fun detectsVpnIfaceNames() {
        assertTrue(VpnLiveStats.isVpnIfaceName("tun0"))
        assertTrue(VpnLiveStats.isVpnIfaceName("ardtt0"))
        assertTrue(VpnLiveStats.isVpnIfaceName("awg0"))
        assertTrue(VpnLiveStats.isVpnIfaceName("wdttraw0"))
        assertFalse(VpnLiveStats.isVpnIfaceName("wlan0"))
        assertFalse(VpnLiveStats.isVpnIfaceName("rmnet0"))
    }

    @Test
    fun parsesProcNetDev() {
        val text = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
              lo: 1000 1 0 0 0 0 0 0 2000 2 0 0 0 0 0 0
            tun0: 1111 3 0 0 0 0 0 0 2222 4 0 0 0 0 0 0
        """.trimIndent()
        assertEquals(1111L to 2222L, VpnLiveStats.parseProcNetDev(text, "tun0"))
        assertNull(VpnLiveStats.parseProcNetDev(text, "ardtt0"))
    }

    @Test
    fun readsSysfsBytesFromTempTree() {
        val root = createTempDir(prefix = "vpn-sysfs-")
        try {
            val stats = File(root, "ardtt0/statistics")
            assertTrue(stats.mkdirs())
            File(stats, "rx_bytes").writeText("4096\n")
            File(stats, "tx_bytes").writeText("2048\n")
            assertEquals(4096L to 2048L, VpnLiveStats.readSysfsBytes("ardtt0", root))
            assertNull(VpnLiveStats.readSysfsBytes("missing", root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parsesAwgIpcTransfer() {
        val ipc = """
            private_key=abc
            listen_port=51820
            public_key=peer
            endpoint=1.2.3.4:51820
            tx_bytes=1000
            rx_bytes=5000
            last_handshake_time_sec=1
        """.trimIndent()
        assertEquals(5000L to 1000L, VpnLiveStats.parseAwgTransfer(ipc))
        assertEquals(1L, VpnLiveStats.parseAwgHandshakeSec(ipc))
    }

    @Test
    fun parsesAwgIpcWithMultiplePeers() {
        val ipc = """
            public_key=a
            rx_bytes=10
            tx_bytes=20
            public_key=b
            rx_bytes=5
            tx_bytes=7
        """.trimIndent()
        assertEquals(15L to 27L, VpnLiveStats.parseAwgTransfer(ipc))
    }

    @Test
    fun formatRateAndBytes() {
        // 512 B/s → 4096 bit/s → 4.1 Кбит/с
        assertEquals("4.1 Кбит/с", VpnLiveStats.formatRate(512))
        // 1536 B/s → 12288 bit/s → 12.3 Кбит/с
        assertEquals("12.3 Кбит/с", VpnLiveStats.formatRate(1536))
        assertEquals("1.5 КБ", VpnLiveStats.formatBytes(1536))
        assertEquals("00:01:05", VpnLiveStats.formatDuration(1_000L, 66_000L))
    }

    @Test
    fun formatRateUsesBitsNotBytes() {
        assertEquals("0 бит/с", VpnLiveStats.formatRate(0))
        assertEquals("8 бит/с", VpnLiveStats.formatRate(1))
        assertEquals("1.00 Мбит/с", VpnLiveStats.formatRate(125_000))
        val p = VpnLiveStats.formatRateParts(1)
        assertTrue(p.unit.trim().endsWith("б/с") || p.unit.contains("б/с"))
        assertFalse(p.unit.contains("Б/с"))
    }

    @Test
    fun formatPartsAreCompactForShade() {
        val a = VpnLiveStats.formatRateParts(0)
        val b = VpnLiveStats.formatRateParts(12_300)
        assertTrue(a.value.isNotBlank())
        assertTrue(b.value.isNotBlank())
        assertFalse(a.value.startsWith(" "))
        assertFalse(b.value.startsWith(" "))
        val line = VpnLiveStats.formatRateLine(0, 12_300)
        assertTrue(line.contains("↓"))
        assertTrue(line.contains("↑"))
        assertTrue(line.contains("Кб/с") || line.contains("б/с"))
        val compact = VpnLiveStats.formatCompactRateLine(12_300, 1)
        assertTrue(compact.contains("↓98.4 Кб/с"))
        assertTrue(compact.contains("↑8.0 б/с"))
        val totals = VpnLiveStats.formatBytesLine(100, 999)
        assertTrue(totals.contains("КБ") || totals.contains("Б"))
    }

    /** 1000 B used to count as fresh; the threshold now excludes handshake-sized rx. */
    @Test
    fun freshRxTracksGrowthAfterNetworkEvent() {
        VpnLiveStats.reset()
        assertFalse(VpnLiveStats.hasFreshRxSince(0L))
        VpnLiveStats.recordRxGrowthForTest(40_000L, nowMs = 50_000L)
        assertTrue(VpnLiveStats.hasFreshRxSince(40_000L, nowMs = 51_000L))
        assertFalse(VpnLiveStats.hasFreshRxSince(60_000L, nowMs = 61_000L))
        VpnLiveStats.reset()
        assertFalse(VpnLiveStats.hasFreshRxSince(0L))
    }

    @Test
    fun handshakeBytesAfterRestartAreNotFreshRx() {
        VpnLiveStats.reset()
        // Restarted Direct on the blackholed cell: 412 B handshake response, then flat.
        VpnLiveStats.recordRxGrowthForTest(412L, nowMs = 5_000L)
        assertEquals(412L, VpnLiveStats.rxGrowthSince(0L))
        assertFalse(VpnLiveStats.hasFreshRxSince(0L, nowMs = 6_000L))
        VpnLiveStats.recordRxGrowthForTest(9_000L, nowMs = 8_000L)
        assertTrue(VpnLiveStats.hasFreshRxSince(0L, nowMs = 9_000L))
        // Growth is measured from the anchor, not from the session start.
        VpnLiveStats.recordRxGrowthForTest(9_600L, nowMs = 12_000L)
        assertEquals(600L, VpnLiveStats.rxGrowthSince(8_000L))
        assertFalse(VpnLiveStats.hasFreshRxSince(8_000L, nowMs = 13_000L))
        VpnLiveStats.reset()
    }

    @Test
    fun rxDataGrowthMarkIgnoresHandshakeSizedArrivals() {
        VpnLiveStats.reset()
        assertEquals(0L, VpnLiveStats.lastRxDataGrowthAtMs)
        VpnLiveStats.recordRxGrowthForTest(412L, nowMs = 1_000L)
        assertEquals(0L, VpnLiveStats.lastRxDataGrowthAtMs)
        // Keepalives keep the counter moving without ever reaching a payload's worth.
        VpnLiveStats.recordRxGrowthForTest(444L, nowMs = 4_000L)
        assertEquals(0L, VpnLiveStats.lastRxDataGrowthAtMs)
        VpnLiveStats.recordRxGrowthForTest(9_000L, nowMs = 7_000L)
        assertEquals(7_000L, VpnLiveStats.lastRxDataGrowthAtMs)
        // Slow but live: growth accumulates from the last mark.
        VpnLiveStats.recordRxGrowthForTest(9_600L, nowMs = 10_000L)
        assertEquals(7_000L, VpnLiveStats.lastRxDataGrowthAtMs)
        VpnLiveStats.recordRxGrowthForTest(10_200L, nowMs = 13_000L)
        assertEquals(13_000L, VpnLiveStats.lastRxDataGrowthAtMs)
        VpnLiveStats.reset()
        assertEquals(0L, VpnLiveStats.lastRxDataGrowthAtMs)
    }

    @Test
    fun txGrowthIsTrackedIndependentlyOfRx() {
        VpnLiveStats.reset()
        assertEquals(0L, VpnLiveStats.lastTxGrowthAtMs)
        VpnLiveStats.recordTxGrowthForTest(9_000L, nowMs = 50_000L)
        assertEquals(50_000L, VpnLiveStats.lastTxGrowthAtMs)
        assertEquals(0L, VpnLiveStats.lastRxDataGrowthAtMs)
        assertFalse(VpnLiveStats.hasFreshRxSince(40_000L, nowMs = 51_000L))
        VpnLiveStats.reset()
        assertEquals(0L, VpnLiveStats.lastTxGrowthAtMs)
    }
}
