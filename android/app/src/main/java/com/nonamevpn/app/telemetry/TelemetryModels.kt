package com.nonamevpn.app.telemetry

import org.json.JSONObject
import java.io.File

enum class TelemetryEventType(val wire: String) {
    Touch("touch"),
    Navigation("navigation"),
    Scroll("scroll"),
    Network("network"),
    Error("error"),
    System("system"),
    Lifecycle("lifecycle"),
}

data class TelemetryEvent(
    val timestamp: Long,
    val eventType: TelemetryEventType,
    val sessionId: String,
    val data: JSONObject,
) {
    fun toJsonLine(): String = JSONObject()
        .put("timestamp", timestamp)
        .put("event_type", eventType.wire)
        .put("session_id", sessionId)
        .put("data", data)
        .toString()
}

data class TelemetryLogEntry(
    val file: File,
    val displayName: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
)
