package com.nonamevpn.app.core

/**
 * Live transport health from Path B go_client `[СТАТИСТИКА] Активных: N` lines
 * (same format as WDTT-Plus).
 */
object TransportHealth {
    private val statsRe = Regex("""Активных:\s*(\d+)""")

    @Volatile var activeWorkers: Int = 0
        private set
    @Volatile var lastStatsAtMs: Long = 0L
        private set
    @Volatile var backendAlive: Boolean = false

    fun reset() {
        activeWorkers = 0
        lastStatsAtMs = 0L
        backendAlive = false
    }

    fun noteBackendStarted() {
        backendAlive = true
        activeWorkers = 0
        lastStatsAtMs = 0L
    }

    fun noteBackendStopped() {
        backendAlive = false
        activeWorkers = 0
    }

    fun onLogLine(line: String) {
        val match = statsRe.find(line) ?: return
        activeWorkers = match.groupValues[1].toIntOrNull() ?: return
        lastStatsAtMs = System.currentTimeMillis()
        backendAlive = true
    }

    fun hasFreshStatsSince(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastStatsAtMs >= sinceMs && activeWorkers > 0 && nowMs - lastStatsAtMs < 90_000L
}
