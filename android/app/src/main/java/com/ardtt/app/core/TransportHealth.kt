package com.ardtt.app.core

/**
 * Live transport health from Path B go_client `[СТАТИСТИКА] Активных: N` lines
 * (same format as WDTT-Plus).
 */
object TransportHealth {
    private val statsRe = Regex("""Активных:\s*(\d+)""")
    private val downRe = Regex("""↓\s*([\d.]+)\s*МБ""")
    private val upRe = Regex("""↑\s*([\d.]+)\s*МБ""")
    private val totalRe = Regex("""Трафик:\s*([\d.]+)\s*МБ""")

    @Volatile var activeWorkers: Int = 0
        private set
    @Volatile var lastStatsAtMs: Long = 0L
        private set
    @Volatile var backendAlive: Boolean = false
    /** Approximate total traffic in KB (parsed from МБ counters). */
    @Volatile var trafficKb: Long = 0L
        private set
    /** Approximate ↓ bytes from go_client МБ counters (Bypass live shade fallback). */
    @Volatile var downBytes: Long = 0L
        private set
    /** Approximate ↑ bytes from go_client МБ counters (Bypass live shade fallback). */
    @Volatile var upBytes: Long = 0L
        private set
    @Volatile var lastTrafficGrowthAtMs: Long = 0L
        private set
    /** Last time the ↓ counter increased — not TX or a repeated 0.00 МБ tick. */
    @Volatile var lastInboundGrowthAtMs: Long = 0L
        private set
    @Volatile var tunGen: Long = 0L
        private set
    @Volatile var tunWriteOk: Long = 0L
        private set
    @Volatile var tunWriteErr: Long = 0L
        private set
    @Volatile var lastTunWriteOkMs: Long = 0L
        private set
    @Volatile var exactDownBytes: Long = 0L
        private set
    @Volatile var exactUpBytes: Long = 0L
        private set

    fun reset() {
        activeWorkers = 0
        lastStatsAtMs = 0L
        backendAlive = false
        trafficKb = 0L
        downBytes = 0L
        upBytes = 0L
        lastTrafficGrowthAtMs = 0L
        lastInboundGrowthAtMs = 0L
        tunGen = 0L
        tunWriteOk = 0L
        tunWriteErr = 0L
        lastTunWriteOkMs = 0L
        exactDownBytes = 0L
        exactUpBytes = 0L
    }

    fun noteBackendStarted() {
        backendAlive = true
        activeWorkers = 0
        lastStatsAtMs = 0L
        trafficKb = 0L
        downBytes = 0L
        upBytes = 0L
        lastTrafficGrowthAtMs = 0L
        lastInboundGrowthAtMs = 0L
        tunGen = 0L
        tunWriteOk = 0L
        tunWriteErr = 0L
        lastTunWriteOkMs = 0L
        exactDownBytes = 0L
        exactUpBytes = 0L
    }

    fun noteBackendStopped() {
        backendAlive = false
        activeWorkers = 0
    }

    fun markSoftRestart() {
        // Soft restart will reset counters when backend restarts; keep alive flag.
        backendAlive = true
    }

    fun applyStructuredTelemetry(payload: String) {
        val keys = parseKeyValues(payload)
        if (keys.isEmpty()) return
        lastStatsAtMs = System.currentTimeMillis()
        backendAlive = true
        keys["channels"]?.toIntOrNull()?.let { activeWorkers = it }
        keys["tunGen"]?.toLongOrNull()?.let { tunGen = it }
        keys["tunWriteOk"]?.toLongOrNull()?.let { tunWriteOk = it }
        keys["tunWriteErr"]?.toLongOrNull()?.let { tunWriteErr = it }
        keys["lastTunWriteOk"]?.toLongOrNull()?.let { lastTunWriteOkMs = it }
        keys["down"]?.toLongOrNull()?.let { down ->
            if (down > exactDownBytes) {
                lastInboundGrowthAtMs = lastStatsAtMs
            }
            exactDownBytes = down
            downBytes = down
        }
        keys["up"]?.toLongOrNull()?.let { up ->
            exactUpBytes = up
            upBytes = up
        }
        val total = exactDownBytes + exactUpBytes
        if (total > trafficKb * 1024L) {
            trafficKb = total / 1024L
            lastTrafficGrowthAtMs = lastStatsAtMs
        }
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
        activeWorkers = match.groupValues[1].toIntOrNull() ?: return
        lastStatsAtMs = System.currentTimeMillis()
        backendAlive = true
        parseDownUpBytes(line)?.let { (down, up) ->
            if (down > downBytes) {
                lastInboundGrowthAtMs = lastStatsAtMs
            }
            downBytes = down
            upBytes = up
        }
        val kb = parseTrafficKb(line)
        if (kb != null && kb > trafficKb) {
            trafficKb = kb
            lastTrafficGrowthAtMs = lastStatsAtMs
        } else if (kb != null && kb == 0L) {
            // Repeated 0.00 МБ ticks are not proof of a live data plane.
            trafficKb = 0L
        }
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
