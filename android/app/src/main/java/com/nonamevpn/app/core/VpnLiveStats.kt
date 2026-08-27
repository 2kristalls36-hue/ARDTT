package com.nonamevpn.app.core

import android.net.TrafficStats
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Live VPN traffic sample for the shade notification.
 * Prefers the TUN/AWG iface counters; falls back to UID totals.
 */
object VpnLiveStats {
    @Volatile var downBps: Long = 0L
        private set
    @Volatile var upBps: Long = 0L
        private set
    @Volatile var totalRx: Long = 0L
        private set
    @Volatile var totalTx: Long = 0L
        private set
    @Volatile var ifaceName: String? = null
        private set

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAtMs = 0L

    fun reset() {
        downBps = 0L
        upBps = 0L
        totalRx = 0L
        totalTx = 0L
        ifaceName = null
        lastRx = -1L
        lastTx = -1L
        lastAtMs = 0L
    }

    fun sample() {
        val now = System.currentTimeMillis()
        val (rx, tx, iface) = readCounters()
        if (rx < 0L || tx < 0L) return
        if (lastAtMs > 0L && now > lastAtMs) {
            val dtSec = (now - lastAtMs) / 1000.0
            if (dtSec >= 0.2) {
                downBps = ((rx - lastRx).coerceAtLeast(0L) / dtSec).toLong()
                upBps = ((tx - lastTx).coerceAtLeast(0L) / dtSec).toLong()
            }
        }
        lastRx = rx
        lastTx = tx
        lastAtMs = now
        totalRx = rx
        totalTx = tx
        ifaceName = iface
    }

    private fun readCounters(): Triple<Long, Long, String?> {
        val iface = detectVpnIface()
        if (iface != null) {
            val rx = runCatching { TrafficStats.getRxBytes(iface) }.getOrDefault(-1L)
            val tx = runCatching { TrafficStats.getTxBytes(iface) }.getOrDefault(-1L)
            if (rx >= 0L && tx >= 0L) return Triple(rx, tx, iface)
        }
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return Triple(-1L, -1L, iface)
        }
        return Triple(rx, tx, iface)
    }

    private fun detectVpnIface(): String? {
        ifaceName?.let { known ->
            if (runCatching { NetworkInterface.getByName(known)?.isUp == true }.getOrDefault(false)) {
                return known
            }
        }
        val preferred = listOf("awg0", "tun0", "wg0", "tun1", "wdttraw0")
        for (name in preferred) {
            val nif = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: continue
            if (nif.isUp) return name
        }
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { nif ->
                    nif.isUp && !nif.isLoopback && (
                        nif.name.startsWith("tun") ||
                            nif.name.startsWith("awg") ||
                            nif.name.startsWith("wg") ||
                            nif.name.contains("raw")
                        )
                }?.name
        }.getOrNull()
    }

    fun formatRate(bps: Long): String {
        if (bps < 1024) return "$bps Б/с"
        val kb = bps / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f КБ/с", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f МБ/с", mb)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f ГБ", gb)
    }

    fun formatDuration(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (startedAtMs <= 0L) return "—"
        val sec = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
