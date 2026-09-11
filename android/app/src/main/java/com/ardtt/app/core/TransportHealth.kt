package com.ardtt.app.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable RAW health published atomically. Individual volatile fields are not a
 * consistent observation for PathConfirm baselines.
 */
data class TransportHealthSnapshot(
    val processId: Long = 0L,
    val operationId: Long = 0L,
    val callEpoch: Long = 0L,
    /** -1 = generation not yet known for this operation. */
    val tunGen: Long = -1L,
    val activeWorkers: Int = 0,
    val lastStatsAtMs: Long = 0L,
    val backendAlive: Boolean = false,
    val trafficKb: Long = 0L,
    /** Rounded МБ log counters — display only, never PathConfirm proof. */
    val downBytes: Long = 0L,
    val upBytes: Long = 0L,
    val lastTrafficGrowthAtMs: Long = 0L,
    val lastInboundGrowthAtMs: Long = 0L,
    val lastUplinkGrowthAtMs: Long = 0L,
    val tunWriteOk: Long = 0L,
    val tunWriteErr: Long = 0L,
    val lastTunWriteOkMs: Long = 0L,
    val exactDownBytes: Long = 0L,
    val exactUpBytes: Long = 0L,
)

/**
 * Live transport health from Path B go_client `[СТАТИСТИКА] Активных: N` lines
 * (same format as WDTT-Plus).
 */
object TransportHealth {
    private val statsRe = Regex("""Активных:\s*(\d+)""")
    private val downRe = Regex("""↓\s*([\d.]+)\s*МБ""")
    private val upRe = Regex("""↑\s*([\d.]+)\s*МБ""")
    private val totalRe = Regex("""Трафик:\s*([\d.]+)\s*МБ""")

    private val processSeq = AtomicLong(0L)
    private val operationSeq = AtomicLong(0L)

    @Volatile
    private var published: TransportHealthSnapshot = TransportHealthSnapshot()

    fun snapshot(): TransportHealthSnapshot = published

    val activeWorkers: Int get() = published.activeWorkers
    val lastStatsAtMs: Long get() = published.lastStatsAtMs
    val backendAlive: Boolean get() = published.backendAlive
    val trafficKb: Long get() = published.trafficKb
    val downBytes: Long get() = published.downBytes
    val upBytes: Long get() = published.upBytes
    val lastTrafficGrowthAtMs: Long get() = published.lastTrafficGrowthAtMs
    val lastInboundGrowthAtMs: Long get() = published.lastInboundGrowthAtMs
    val lastUplinkGrowthAtMs: Long get() = published.lastUplinkGrowthAtMs
    val tunGen: Long get() = published.tunGen
    val tunWriteOk: Long get() = published.tunWriteOk
    val tunWriteErr: Long get() = published.tunWriteErr
    val lastTunWriteOkMs: Long get() = published.lastTunWriteOkMs
    val exactDownBytes: Long get() = published.exactDownBytes
    val exactUpBytes: Long get() = published.exactUpBytes

    fun reset() {
        published = TransportHealthSnapshot()
    }

    /** New backend process: counters cleared, TUN generation unknown until telemetry. */
    fun noteBackendStarted(callEpoch: Long = 0L): TransportHealthSnapshot {
        val snap = TransportHealthSnapshot(
            processId = processSeq.incrementAndGet(),
            operationId = operationSeq.incrementAndGet(),
            callEpoch = callEpoch,
            tunGen = -1L,
            backendAlive = true,
        )
        published = snap
        return snap
    }

    /**
     * Reattach / resume of the same process: new operation id, unknown tunGen until
     * the first telemetry for this attach. Process id is preserved.
     */
    fun noteAttachStarted(callEpoch: Long = published.callEpoch): TransportHealthSnapshot {
        val prev = published
        val snap = TransportHealthSnapshot(
            processId = if (prev.processId > 0L) prev.processId else processSeq.incrementAndGet(),
            operationId = operationSeq.incrementAndGet(),
            callEpoch = callEpoch,
            tunGen = -1L,
            backendAlive = true,
        )
        published = snap
        return snap
    }

    fun noteBackendStopped() {
        val prev = published
        published = prev.copy(backendAlive = false, activeWorkers = 0)
    }

    fun markSoftRestart() {
        val prev = published
        published = prev.copy(backendAlive = true)
    }

    fun applyStructuredTelemetry(payload: String) {
        val keys = parseKeyValues(payload)
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        val prev = published
        var next = prev.copy(
            lastStatsAtMs = now,
            backendAlive = true,
        )
        keys["channels"]?.toIntOrNull()?.let { next = next.copy(activeWorkers = it) }
        keys["tunGen"]?.toLongOrNull()?.let { gen ->
            // Adopt first known gen for this operation; later gen changes mean reattach
            // of the same operation only when process/operation ids still match.
            next = next.copy(tunGen = gen)
        }
        keys["tunWriteOk"]?.toLongOrNull()?.let { next = next.copy(tunWriteOk = it) }
        keys["tunWriteErr"]?.toLongOrNull()?.let { next = next.copy(tunWriteErr = it) }
        keys["lastTunWriteOk"]?.toLongOrNull()?.let { next = next.copy(lastTunWriteOkMs = it) }
        keys["down"]?.toLongOrNull()?.let { down ->
            val inboundAt = if (down > prev.exactDownBytes) now else prev.lastInboundGrowthAtMs
            next = next.copy(
                exactDownBytes = down,
                downBytes = down,
                lastInboundGrowthAtMs = inboundAt,
            )
        }
        keys["up"]?.toLongOrNull()?.let { up ->
            val uplinkAt = if (up > prev.exactUpBytes) now else prev.lastUplinkGrowthAtMs
            next = next.copy(exactUpBytes = up, upBytes = up, lastUplinkGrowthAtMs = uplinkAt)
        }
        val total = next.exactDownBytes + next.exactUpBytes
        if (total > next.trafficKb * 1024L) {
            next = next.copy(
                trafficKb = total / 1024L,
                lastTrafficGrowthAtMs = now,
            )
        }
        published = next
    }

    fun applyControlAck(reply: String) {
        applyStructuredTelemetry(payloadFromControlAck(reply) ?: reply)
    }

    fun onLogLine(line: String) {
        if (line.contains("tunWriteOk=") || line.contains("channels=")) {
            applyStructuredTelemetry(line)
            return
        }
        val match = statsRe.find(line) ?: return
        val workers = match.groupValues[1].toIntOrNull() ?: return
        val now = System.currentTimeMillis()
        val prev = published
        var next = prev.copy(
            activeWorkers = workers,
            lastStatsAtMs = now,
            backendAlive = true,
        )
        parseDownUpBytes(line)?.let { (down, up) ->
            val inboundAt = if (down > prev.downBytes) now else prev.lastInboundGrowthAtMs
            val uplinkAt = if (up > prev.upBytes) now else prev.lastUplinkGrowthAtMs
            next = next.copy(
                downBytes = down,
                upBytes = up,
                lastInboundGrowthAtMs = inboundAt,
                lastUplinkGrowthAtMs = uplinkAt,
            )
        }
        val kb = parseTrafficKb(line)
        if (kb != null && kb > next.trafficKb) {
            next = next.copy(trafficKb = kb, lastTrafficGrowthAtMs = now)
        } else if (kb != null && kb == 0L) {
            // Repeated 0.00 МБ ticks are not proof of a live data plane.
            next = next.copy(trafficKb = 0L)
        }
        published = next
    }

    fun hasFreshStatsSince(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastStatsAtMs >= sinceMs && activeWorkers > 0 && nowMs - lastStatsAtMs < 90_000L

    /** ↓ counter grew after [sinceMs]. TX-only / zero ticks do not count. */
    fun hasFreshInboundSince(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        activeWorkers > 0 &&
            lastInboundGrowthAtMs >= sinceMs &&
            nowMs - lastInboundGrowthAtMs < 90_000L

    /** Total (↓+↑) grew after [sinceMs] — process is moving bytes, not necessarily inbound. */
    fun hasFreshTrafficSince(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        activeWorkers > 0 &&
            lastTrafficGrowthAtMs >= sinceMs &&
            nowMs - lastTrafficGrowthAtMs < 90_000L

    internal fun parseTrafficKb(line: String): Long? {
        val pair = parseDownUpBytes(line)
        if (pair != null) {
            return (pair.first + pair.second) / 1024L
        }
        val total = totalRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return (total * 1024.0).toLong()
    }

    internal fun parseKeyValues(payload: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val body = payloadFromControlAck(payload) ?: payload
        for (part in body.split('|')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    internal fun payloadFromControlAck(reply: String): String? {
        val parts = reply.split('|')
        if (parts.size >= 7 && parts.getOrNull(3) == "ACK") {
            return parts.drop(6).joinToString("|")
        }
        return null
    }

    /** Convert go_client `↓X.XX МБ / ↑Y.YY МБ` into approximate byte totals. */
    internal fun parseDownUpBytes(line: String): Pair<Long, Long>? {
        val down = downRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val up = upRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return (down * 1024.0 * 1024.0).toLong() to (up * 1024.0 * 1024.0).toLong()
    }
}
