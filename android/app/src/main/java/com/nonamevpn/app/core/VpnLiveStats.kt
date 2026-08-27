package com.nonamevpn.app.core

import android.net.TrafficStats
import android.util.Log
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live VPN traffic for the shade notification.
 *
 * Prefer AmneziaWG IPC / Bypass health when available — kernel TUN sysfs often
 * stays at 0 for userspace tunnels on Android, which previously blocked fallbacks.
 */
object VpnLiveStats {
    private const val TAG = "VpnLiveStats"

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
    @Volatile var source: String? = null
        private set

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAtMs = 0L
    private var baselineRx = -1L
    private var baselineTx = -1L
    private var lastLogAtMs = 0L

    /** Optional AmneziaWG handle for IPC transfer counters (Direct path). */
    private val awgHandle = AtomicInteger(-1)

    fun reset() {
        downBps = 0L
        upBps = 0L
        totalRx = 0L
        totalTx = 0L
        ifaceName = null
        source = null
        lastRx = -1L
        lastTx = -1L
        lastAtMs = 0L
        baselineRx = -1L
        baselineTx = -1L
        lastLogAtMs = 0L
        // Keep awgHandle — DirectBackend owns lifecycle across soft-restarts.
    }

    fun setAwgHandle(handle: Int) {
        awgHandle.set(handle)
        Log.i(TAG, "awg handle=$handle")
    }

    fun clearAwgHandle(handle: Int = -1) {
        if (handle < 0 || awgHandle.get() == handle) {
            awgHandle.set(-1)
        }
    }

    fun sample() {
        val now = System.currentTimeMillis()
        val (rxAbs, txAbs, iface, src) = readCounters()
        if (rxAbs < 0L || txAbs < 0L) {
            maybeLog(now, "no counters handle=${awgHandle.get()}")
            return
        }

        if (baselineRx < 0L || baselineTx < 0L) {
            baselineRx = rxAbs
            baselineTx = txAbs
        }
        // Soft-restart / counter wrap / source switch: re-baseline.
        if (rxAbs < baselineRx || txAbs < baselineTx || (source != null && source != src)) {
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
        source = src
        if (rx > 0L || tx > 0L || downBps > 0L || upBps > 0L) {
            maybeLog(now, "src=$src iface=$iface ↓$downBps ↑$upBps rx=$rx tx=$tx")
        }
    }

    private fun maybeLog(now: Long, msg: String) {
        if (now - lastLogAtMs < 5_000L) return
        lastLogAtMs = now
        Log.i(TAG, msg)
    }

    private data class Counters(val rx: Long, val tx: Long, val iface: String?, val source: String)

    private fun readCounters(): Counters {
        // Direct: AWG peer transfer is authoritative (sysfs TUN often stuck at 0).
        readAwgTransfer()?.let { return Counters(it.first, it.second, "awg", "awg") }
        readTransportHealth()?.let { return Counters(it.first, it.second, "bypass", "bypass") }
        readSysfsOrProc()?.let { return Counters(it.first, it.second, it.third, "sysfs") }
        val uid = readUidFallback()
        return Counters(uid.first, uid.second, uid.third, "uid")
    }

    private fun readSysfsOrProc(): Triple<Long, Long, String?>? {
        for (name in candidateIfaces()) {
            val sys = readSysfsBytes(name)
            if (sys != null && (sys.first > 0L || sys.second > 0L)) {
                return Triple(sys.first, sys.second, name)
            }
            val proc = readProcNetDev(name)
            if (proc != null && (proc.first > 0L || proc.second > 0L)) {
                return Triple(proc.first, proc.second, name)
            }
            val api = readTrafficStatsIface(name)
            if (api != null) return Triple(api.first, api.second, name)
        }
        // Accept all-zero sysfs only when nothing else exists (fresh tunnel).
        for (name in candidateIfaces()) {
            val sys = readSysfsBytes(name) ?: continue
            return Triple(sys.first, sys.second, name)
        }
        return null
    }

    private fun candidateIfaces(): List<String> {
        val ordered = LinkedHashSet<String>()
        ifaceName?.let { if (it != "awg" && it != "bypass") ordered.add(it) }
        ordered.addAll(listOf("tun0", "tun1", "nvpn0", "awg0", "wg0", "wdttraw0"))
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

    /** `/proc/net/dev` columns: iface | rx_bytes … | tx_bytes … */
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
        if (rx == 0L && tx == 0L) return null
        return rx to tx
    }

    private fun readAwgTransfer(): Pair<Long, Long>? {
        val h = awgHandle.get()
        if (h < 0) return null
        val cfg = runCatching {
            org.amnezia.awg.GoBackend.awgGetConfig(h)
        }.getOrNull()
        if (cfg.isNullOrBlank()) {
            maybeLog(System.currentTimeMillis(), "awgGetConfig empty handle=$h")
            return null
        }
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
                    val v = line.substringAfter('=').toLongOrNull() ?: continue
                    rx += v
                    saw = true
                }
                line.startsWith("tx_bytes=") -> {
                    val v = line.substringAfter('=').toLongOrNull() ?: continue
                    tx += v
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

    /**
     * Format byte/s counter as bit/s for the shade (network-style units).
     * [bytesPerSec] is bytes/second from counters; display uses ×8 → бит/с.
     */
    fun formatRate(bytesPerSec: Long): String {
        val bits = bytesPerSec.coerceAtLeast(0L) * 8L
        if (bits < 1000L) return "$bits бит/с"
        val kbit = bits / 1000.0
        if (kbit < 1000.0) return String.format(Locale.US, "%.1f Кбит/с", kbit)
        val mbit = kbit / 1000.0
        return String.format(Locale.US, "%.2f Мбит/с", mbit)
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

    /**
     * Compact fixed-width bit/s for shade (monospace).
     * [bytesPerSec] → бит/с shown as б/с · Кб/с · Мб/с.
     */
    fun formatRateFixed(bytesPerSec: Long, down: Boolean): String {
        val arrow = if (down) "↓" else "↑"
        val bits = bytesPerSec.coerceAtLeast(0L) * 8L
        val body = when {
            bits < 1000L -> String.format(Locale.US, "%4dб/с", bits.coerceAtMost(999L))
            bits < 1_000_000L -> String.format(Locale.US, "%5.1fКб/с", bits / 1000.0)
            else -> String.format(Locale.US, "%5.2fМб/с", bits / 1_000_000.0)
        }
        return arrow + body
    }

    /** Compact fixed-width session total (monospace), bytes. */
    fun formatBytesFixed(bytes: Long, down: Boolean): String {
        val arrow = if (down) "↓" else "↑"
        val body = when {
            bytes < 1024L -> String.format(Locale.US, "%4dБ", bytes.coerceIn(0L, 9999L))
            bytes < 1024L * 1024L -> String.format(Locale.US, "%5.1fКБ", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%5.1fМБ", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%5.2fГБ", bytes / (1024.0 * 1024.0 * 1024.0))
        }
        return arrow + body
    }

    fun formatDuration(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (startedAtMs <= 0L) return "00:00:00"
        val sec = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L)
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun File.readTextOrNull(): String? =
        runCatching { readText(Charsets.UTF_8) }.getOrNull()
}
