package com.ardtt.app.core

import android.net.TrafficStats
import android.util.Log
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live VPN traffic for the shade notification.
 *
 * Prefer AmneziaWG IPC / Bypass health when available — kernel TUN sysfs often
 * stays at 0 for userspace tunnels on Android, which previously blocked fallbacks.
 */
object VpnLiveStats {
    private const val TAG = "VpnLiveStats"

    /** Anchors older than this fall back to the session total (see [rxGrowthSince]). */
    private const val RX_HISTORY_MS = 180_000L

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
    /** Last time session-relative rx increased (Direct skip-if-alive / dead-egress). */
    @Volatile var lastRxGrowthAtMs: Long = 0L
        private set
    /** Last time session-relative tx increased (Direct unanswered-uplink check). */
    @Volatile var lastTxGrowthAtMs: Long = 0L
        private set

    /**
     * Last time rx grew by more than a handshake worth of bytes since the previous
     * such mark. Growth accumulates, so a slow but live path still marks within a
     * few samples while handshake responses / keepalives never do.
     */
    @Volatile var lastRxDataGrowthAtMs: Long = 0L
        private set

    private var rxAtLastDataMark = 0L

    private data class RxSample(val atMs: Long, val rx: Long)

    /** Recent session-relative rx so growth can be measured from an arbitrary anchor. */
    private val rxHistory = ArrayDeque<RxSample>()

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAtMs = 0L
    private var baselineRx = -1L
    private var baselineTx = -1L
    private var lastLogAtMs = 0L

    /** Optional AmneziaWG handle for IPC transfer counters (Direct path). */
    private val awgHandle = AtomicInteger(-1)
    private val directOpSeq = AtomicLong(0L)

    /**
     * Baseline captured at the start of a Direct backend operation — before
     * [org.amnezia.awg.GoBackend.awgTurnOn] — so early handshake/RX are not
     * absorbed into a late verifier baseline.
     */
    data class DirectOperationBaseline(
        val operationId: Long,
        val handshakeSecAtStart: Long = 0L,
        val rxAtStart: Long = 0L,
        val handleAtStart: Int = -1,
    )

    @Volatile
    private var directOpBaseline: DirectOperationBaseline? = null

    fun beginDirectOperation(): DirectOperationBaseline {
        val baseline = DirectOperationBaseline(operationId = directOpSeq.incrementAndGet())
        directOpBaseline = baseline
        awgHandle.set(-1)
        return baseline
    }

    fun currentDirectOperation(): DirectOperationBaseline? = directOpBaseline

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
        lastRxGrowthAtMs = 0L
        lastTxGrowthAtMs = 0L
        lastRxDataGrowthAtMs = 0L
        rxAtLastDataMark = 0L
        synchronized(rxHistory) { rxHistory.clear() }
        // Keep awgHandle / directOpBaseline — DirectBackend owns lifecycle across soft-restarts.
    }

    fun currentAwgHandle(): Int = awgHandle.get()

    data class DirectAwgSample(
        val handle: Int,
        val rx: Long,
        val handshakeSec: Long,
    )

    /**
     * Direct proof must come from this AWG handle. UID / parked RAW / sysfs
     * VPN ifaces are display fallbacks only.
     */
    fun readDirectAwgSample(): DirectAwgSample? {
        val h = awgHandle.get()
        if (h < 0) return null
        val cfg = runCatching {
            org.amnezia.awg.GoBackend.awgGetConfig(h)
        }.getOrNull()
        if (cfg.isNullOrBlank()) return null
        val rx = parseAwgTransfer(cfg)?.first ?: 0L
        val handshake = parseAwgHandshakeSec(cfg) ?: 0L
        return DirectAwgSample(handle = h, rx = rx, handshakeSec = handshake)
    }

    fun currentAwgHandshakeSec(): Long = readDirectAwgSample()?.handshakeSec ?: 0L

    fun setAwgHandle(handle: Int) {
        awgHandle.set(handle)
        val op = directOpBaseline
        if (op != null && op.handleAtStart < 0) {
            directOpBaseline = op.copy(handleAtStart = handle)
        }
        Log.i(TAG, "awg handle=$handle op=${directOpBaseline?.operationId ?: -1}")
    }

    fun clearAwgHandle(handle: Int = -1) {
        if (handle < 0 || awgHandle.get() == handle) {
            awgHandle.set(-1)
            val op = directOpBaseline
            if (op != null && (handle < 0 || op.handleAtStart < 0 || op.handleAtStart == handle)) {
                // Operation ends with the handle; leave id so late samples stay attributable.
            }
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
            rxAtLastDataMark = 0L
        }

        val rx = (rxAbs - baselineRx).coerceAtLeast(0L)
        val tx = (txAbs - baselineTx).coerceAtLeast(0L)

        if (lastRx >= 0L && rx > lastRx) {
            lastRxGrowthAtMs = now
        }
        if (lastTx >= 0L && tx > lastTx) {
            lastTxGrowthAtMs = now
        }
        markRxDataGrowth(now, rx)
        recordRxSample(now, rx)

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

    private fun markRxDataGrowth(atMs: Long, rx: Long) {
        if (rx < rxAtLastDataMark) rxAtLastDataMark = rx
        if (!RecoverySettings.directRxLooksLikeData(rx - rxAtLastDataMark)) return
        rxAtLastDataMark = rx
        lastRxDataGrowthAtMs = atMs
    }

    private fun recordRxSample(atMs: Long, rx: Long) {
        synchronized(rxHistory) {
            if (rxHistory.lastOrNull()?.atMs == atMs) rxHistory.removeLast()
            rxHistory.addLast(RxSample(atMs, rx))
            while (rxHistory.size > 1 && atMs - rxHistory.first().atMs > RX_HISTORY_MS) {
                rxHistory.removeFirst()
            }
        }
    }

    /**
     * Session-relative rx growth since [sinceMs]. Counters are re-baselined when
     * the transport restarts, so with no sample that old the total *is* the delta.
     */
    fun rxGrowthSince(sinceMs: Long): Long {
        val base = synchronized(rxHistory) {
            rxHistory.lastOrNull { it.atMs <= sinceMs }?.rx
        } ?: return totalRx
        return (totalRx - base).coerceAtLeast(0L)
    }

    /** Fresh **data**: an AWG handshake response answered by a blackholing cell is not. */
    fun hasFreshRxSince(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastRxGrowthAtMs >= sinceMs &&
            nowMs - lastRxGrowthAtMs < 90_000L &&
            RecoverySettings.directRxLooksLikeData(rxGrowthSince(sinceMs))

    /** Test helper: pretend session-relative rx grew at [nowMs]. */
    internal fun recordRxGrowthForTest(rx: Long, nowMs: Long) {
        if (rx > totalRx) lastRxGrowthAtMs = nowMs
        totalRx = rx
        markRxDataGrowth(nowMs, rx)
        recordRxSample(nowMs, rx)
    }

    /** Test helper: pretend session-relative tx grew at [nowMs]. */
    internal fun recordTxGrowthForTest(tx: Long, nowMs: Long) {
        if (tx > totalTx) lastTxGrowthAtMs = nowMs
        totalTx = tx
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
        ordered.addAll(listOf("tun0", "tun1", "ardtt0", "awg0", "wg0", "wdttraw0"))
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
            n.startsWith("ardtt") ||
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

    /** Latest peer `last_handshake_time_sec=` from a WireGuard/AmneziaWG IPC dump. */
    internal fun parseAwgHandshakeSec(ipc: String): Long? {
        var latest: Long? = null
        for (raw in ipc.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("last_handshake_time_sec=")) continue
            val v = line.substringAfter('=').toLongOrNull() ?: continue
            latest = maxOf(latest ?: 0L, v)
        }
        return latest
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
     * Fixed shade slots as one monospace string (no minEms — OEM RemoteViews stretch).
     * Example: `↓ 27.22 Кб/с  ↑ 27.09 Кб/с`
     */
    fun formatRateLine(downBytesPerSec: Long, upBytesPerSec: Long): String {
        val d = formatRateParts(downBytesPerSec)
        val u = formatRateParts(upBytesPerSec)
        return "↓${d.value.trim()} ${d.unit.trim()}  ↑${u.value.trim()} ${u.unit.trim()}"
    }

    /** Compact notification form: `↓27.2 Кб/с  ↑27.1 Кб/с`. */
    fun formatCompactRateLine(downBytesPerSec: Long, upBytesPerSec: Long): String {
        val d = formatRateParts(downBytesPerSec)
        val u = formatRateParts(upBytesPerSec)
        return "↓${compactNumber(d.value)} ${d.unit.trim()}  ↑${compactNumber(u.value)} ${u.unit.trim()}"
    }

    /** Example: `↓ 71.5 КБ  ↑ 52.6 КБ` */
    fun formatBytesLine(downBytes: Long, upBytes: Long): String {
        val d = formatBytesParts(downBytes)
        val u = formatBytesParts(upBytes)
        return "↓${d.value.trim()} ${d.unit.trim()}  ↑${u.value.trim()} ${u.unit.trim()}"
    }

    data class FixedParts(val value: String, val unit: String)

    private fun compactNumber(raw: String): String {
        val number = raw.trim().toDoubleOrNull() ?: return raw.trim()
        return if (number >= 100) {
            String.format(Locale.US, "%.0f", number)
        } else {
            String.format(Locale.US, "%.1f", number)
        }
    }

    fun formatRateParts(bytesPerSec: Long): FixedParts {
        val bits = bytesPerSec.coerceAtLeast(0L) * 8L
        val (num, unit) = when {
            bits < 1000L -> bits.toDouble() to "б/с"
            bits < 1_000_000L -> (bits / 1000.0) to "Кб/с"
            bits < 1_000_000_000L -> (bits / 1_000_000.0) to "Мб/с"
            else -> (bits / 1_000_000_000.0) to "Гб/с"
        }
        return FixedParts(
            value = String.format(Locale.US, "%.2f", num),
            unit = unit,
        )
    }

    fun formatBytesParts(bytes: Long): FixedParts {
        val b = bytes.coerceAtLeast(0L)
        val (num, unit) = when {
            b < 1024L -> b.toDouble() to "Б"
            b < 1024L * 1024L -> (b / 1024.0) to "КБ"
            b < 1024L * 1024L * 1024L -> (b / (1024.0 * 1024.0)) to "МБ"
            else -> (b / (1024.0 * 1024.0 * 1024.0)) to "ГБ"
        }
        return FixedParts(
            value = String.format(Locale.US, "%.1f", num),
            unit = unit,
        )
    }

    fun formatRateFixed(bytesPerSec: Long, down: Boolean): String {
        val p = formatRateParts(bytesPerSec)
        return (if (down) "↓" else "↑") + p.value + " " + p.unit
    }

    fun formatBytesFixed(bytes: Long, down: Boolean): String {
        val p = formatBytesParts(bytes)
        return (if (down) "↓" else "↑") + p.value + " " + p.unit
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
