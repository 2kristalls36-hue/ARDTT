package com.ardtt.app.core

enum class SessionDiagnostic {
    CallCreated,
    CallReused,
    CredentialsRefreshed,
    TransportResumed,
    TransportRebuilt,
}

data class SessionDiagnosticEvent(
    val kind: SessionDiagnostic,
    val sessionEpoch: Long,
    val networkEpoch: Long,
    val transportEpoch: Long,
    val fromPath: VpnPath?,
    val toPath: VpnPath?,
    val attempt: Int,
    val durationMs: Long,
    val reason: String,
)

class SessionDiagnosticLog {
    private val events = ArrayList<SessionDiagnosticEvent>()

    fun record(event: SessionDiagnosticEvent) {
        synchronized(events) { events.add(event) }
    }

    fun snapshot(): List<SessionDiagnosticEvent> = synchronized(events) { events.toList() }

    fun counts(): Map<SessionDiagnostic, Int> =
        snapshot().groupingBy { it.kind }.eachCount()

    fun clear() {
        synchronized(events) { events.clear() }
    }
}
