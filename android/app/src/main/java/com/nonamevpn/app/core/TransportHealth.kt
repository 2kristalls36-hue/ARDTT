package com.nonamevpn.app.core

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

    fun reset() {
        activeWorkers = 0
        lastStatsAtMs = 0L
        backendAlive = false
        trafficKb = 0L
        downBytes = 0L
        upBytes = 0L
        lastTrafficGrowthAtMs = 0L
    }

    fun noteBackendStarted() {
        backendAlive = true
        activeWorkers = 0
        lastStatsAtMs = 0L
        trafficKb = 0L
        downBytes = 0L
        upBytes = 0L
        lastTrafficGrowthAtMs = 0L
    }

    fun noteBackendStopped() {
        backendAlive = false
        activeWorkers = 0
    }

    fun markSoftRestart() {
        // Soft restart will reset counters when backend restarts; keep alive flag.
        backendAlive = true
    }

    fun onLogLine(line: String) {
        val match = statsRe.find(line) ?: return
        activeWorkers = match.groupValues[1].toIntOrNull() ?: return
        lastStatsAtMs = System.currentTimeMillis()
        backendAlive = true
        parseDownUpBytes(line)?.let { (down, up) ->
            downBytes = down
            upBytes = up
        }
        val kb = parseTrafficKb(line)
        if (kb != null && kb > trafficKb) {
            trafficKb = kb
            lastTrafficGrowthAtMs = lastStatsAtMs
        } else if (kb != null && trafficKb == 0L) {
            trafficKb = kb
            lastTrafficGrowthAtMs = lastStatsAtMs
        }
    }

    fun hasFreshStatsSince(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastStatsAtMs >= sinceMs && activeWorkers > 0 && nowMs - lastStatsAtMs < 90_000L

    internal fun parseTrafficKb(line: String): Long? {
        val pair = parseDownUpBytes(line)
        if (pair != null) {
            return (pair.first + pair.second) / 1024L
        }
        val total = totalRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return (total * 1024.0).toLong()
    }

    /** Convert go_client `↓X.XX МБ / ↑Y.YY МБ` into approximate byte totals. */
    internal fun parseDownUpBytes(line: String): Pair<Long, Long>? {
        val down = downRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val up = upRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return (down * 1024.0 * 1024.0).toLong() to (up * 1024.0 * 1024.0).toLong()
    }
}
