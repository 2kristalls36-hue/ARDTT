package com.nonamevpn.app.core

import android.net.TrafficStats
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live VPN traffic for the shade notification.
 *
 * Prefer kernel iface counters via sysfs / `/proc/net/dev` (covers TUN traffic from
 * all apps). [TrafficStats] UID counters only see this process and stay ~0 while
 * Chrome/etc. use the tunnel. AmneziaWG / Bypass backends can also publish totals.
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
    private var baselineRx = -1L
    private var baselineTx = -1L

    /** Optional AmneziaWG handle for IPC transfer counters (Direct path). */
    private val awgHandle = AtomicInteger(-1)

    fun reset() {
        downBps = 0L
        upBps = 0L
        totalRx = 0L
        totalTx = 0L
        ifaceName = null
        lastRx = -1L
        lastTx = -1L
        lastAtMs = 0L
        baselineRx = -1L
        baselineTx = -1L
        // Keep awgHandle — DirectBackend owns lifecycle across soft-restarts.
    }

    fun setAwgHandle(handle: Int) {
        awgHandle.set(handle)
    }

    fun clearAwgHandle(handle: Int = -1) {
        if (handle < 0 || awgHandle.get() == handle) {
            awgHandle.set(-1)
        }
    }

    fun sample() {
        val now = System.currentTimeMillis()
        val (rxAbs, txAbs, iface) = readCounters()
        if (rxAbs < 0L || txAbs < 0L) return

        if (baselineRx < 0L || baselineTx < 0L) {
            baselineRx = rxAbs
            baselineTx = txAbs
        }
        // Soft-restart / counter wrap: re-baseline instead of huge negative rates.
        if (rxAbs < baselineRx || txAbs < baselineTx) {
            baselineRx = rxAbs
            baselineTx = txAbs
            lastRx = -1L
            lastTx = -1L
            lastAtMs = 0L
        }

        val rx = (rxAbs - baselineRx).coerceAtLeast(0L)
        val tx = (txAbs - baselineTx).coerceAtLeast(0L)

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
        readSysfsOrProc()?.let { return it }
        readAwgTransfer()?.let { return Triple(it.first, it.second, "awg") }
        readTransportHealth()?.let { return Triple(it.first, it.second, "bypass") }
        return readUidFallback()
    }

    private fun readSysfsOrProc(): Triple<Long, Long, String?>? {
        for (name in candidateIfaces()) {
            val sys = readSysfsBytes(name)
            if (sys != null) return Triple(sys.first, sys.second, name)
            val proc = readProcNetDev(name)
            if (proc != null) return Triple(proc.first, proc.second, name)
            val api = readTrafficStatsIface(name)
            if (api != null) return Triple(api.first, api.second, name)
        }
        return null
    }

    private fun candidateIfaces(): List<String> {
        val ordered = LinkedHashSet<String>()
        ifaceName?.let { ordered.add(it) }
        ordered.addAll(listOf("nvpn0", "tun0", "awg0", "wg0", "tun1", "wdttraw0"))
        runCatching {
            File("/sys/class/net").list()?.forEach { name ->
                if (isVpnIfaceName(name)) ordered.add(name)
            }
        }
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { nif ->
                if (nif.isUp && !nif.isLoopback && isVpnIfaceName(nif.name)) {
                    ordered.add(nif.name)
                }
            }
        }
        return ordered.toList()
    }

    internal fun isVpnIfaceName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.startsWith("tun") ||
            n.startsWith("awg") ||
            n.startsWith("wg") ||
            n.startsWith("nvpn") ||
            n.contains("raw")
    }

    internal fun readSysfsBytes(iface: String, root: File = File("/sys/class/net")): Pair<Long, Long>? {
        val dir = File(root, iface)
        if (!dir.isDirectory) return null
        val rx = File(dir, "statistics/rx_bytes").readTextOrNull()?.trim()?.toLongOrNull() ?: return null
        val tx = File(dir, "statistics/tx_bytes").readTextOrNull()?.trim()?.toLongOrNull() ?: return null
        if (rx < 0L || tx < 0L) return null
        return rx to tx
    }

    internal fun readProcNetDev(
        iface: String,
        procFile: File = File("/proc/net/dev"),
    ): Pair<Long, Long>? {
        val text = procFile.readTextOrNull() ?: return null
        return parseProcNetDev(text, iface)
    }

    /**
     * `/proc/net/dev` columns: iface | rx_bytes packets ... | tx_bytes packets ...
     */
    internal fun parseProcNetDev(text: String, iface: String): Pair<Long, Long>? {
        val want = iface.trimEnd(':')
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.contains(':')) continue
            val (namePart, rest) = line.split(':', limit = 2).let { it[0].trim() to it[1].trim() }
            if (namePart != want) continue
            val cols = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (cols.size < 9) return null
            val rx = cols[0].toLongOrNull() ?: return null
            val tx = cols[8].toLongOrNull() ?: return null
            return rx to tx
        }
        return null
    }

    private fun readTrafficStatsIface(iface: String): Pair<Long, Long>? {
        val rx = runCatching { TrafficStats.getRxBytes(iface) }.getOrDefault(-1L)
        val tx = runCatching { TrafficStats.getTxBytes(iface) }.getOrDefault(-1L)
        if (rx < 0L || tx < 0L) return null
        // Constant zeros are useless (common for unsupported VPN ifaces that return 0).
        if (rx == 0L && tx == 0L) return null
        return rx to tx
    }

    private fun readAwgTransfer(): Pair<Long, Long>? {
        val h = awgHandle.get()
        if (h < 0) return null
        val cfg = runCatching {
            org.amnezia.awg.GoBackend.awgGetConfig(h)
        }.getOrNull() ?: return null
        return parseAwgTransfer(cfg)
    }

    /** Sum peer `rx_bytes=` / `tx_bytes=` from WireGuard/AmneziaWG IPC dump. */
    internal fun parseAwgTransfer(ipc: String): Pair<Long, Long>? {
        var rx = 0L
        var tx = 0L
        var saw = false
        for (raw in ipc.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("rx_bytes=") -> {
                    rx += line.substringAfter('=').toLongOrNull() ?: continue
                    saw = true
                }
                line.startsWith("tx_bytes=") -> {
                    tx += line.substringAfter('=').toLongOrNull() ?: continue
                    saw = true
                }
            }
        }
        return if (saw) rx to tx else null
    }

    private fun readTransportHealth(): Pair<Long, Long>? {
        val rx = TransportHealth.downBytes
        val tx = TransportHealth.upBytes
        if (rx <= 0L && tx <= 0L) return null
        return rx to tx
    }

    private fun readUidFallback(): Triple<Long, Long, String?> {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return Triple(-1L, -1L, null)
        }
        return Triple(rx, tx, null)
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

    private fun File.readTextOrNull(): String? =
        runCatching { readText(Charsets.UTF_8) }.getOrNull()
}
