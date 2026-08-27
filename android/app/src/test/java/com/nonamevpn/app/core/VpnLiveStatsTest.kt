package com.nonamevpn.app.core

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
        assertTrue(VpnLiveStats.isVpnIfaceName("nvpn0"))
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
        assertNull(VpnLiveStats.parseProcNetDev(text, "nvpn0"))
    }

    @Test
    fun readsSysfsBytesFromTempTree() {
        val root = createTempDir(prefix = "vpn-sysfs-")
        try {
            val stats = File(root, "nvpn0/statistics")
            assertTrue(stats.mkdirs())
            File(stats, "rx_bytes").writeText("4096\n")
            File(stats, "tx_bytes").writeText("2048\n")
            assertEquals(4096L to 2048L, VpnLiveStats.readSysfsBytes("nvpn0", root))
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
        // 125_000 B/s → 1_000_000 bit/s → 1.00 Мбит/с
        assertEquals("1.00 Мбит/с", VpnLiveStats.formatRate(125_000))
        assertTrue(VpnLiveStats.formatRateFixed(1, down = true).contains("б/с"))
        assertFalse(VpnLiveStats.formatRateFixed(1, down = true).contains("Б/с"))
        assertFalse(VpnLiveStats.formatRateFixed(125_000, down = true).contains("WARP"))
    }

    @Test
    fun formatFixedKeepsStableWidth() {
        val a = VpnLiveStats.formatRateFixed(0, down = true)
        val b = VpnLiveStats.formatRateFixed(12_300, down = true)
        assertTrue(a.startsWith("↓"))
        assertTrue(b.startsWith("↓"))
        assertEquals(
            VpnLiveStats.formatBytesFixed(100, down = false).length,
            VpnLiveStats.formatBytesFixed(999, down = false).length,
        )
    }
}
