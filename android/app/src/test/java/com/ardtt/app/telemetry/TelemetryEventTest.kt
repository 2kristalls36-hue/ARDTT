package com.ardtt.app.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryEventTest {
    @Test
    fun jsonLineUsesRequiredSchema() {
        val event = TelemetryEvent(
            timestamp = 1_712_345_678_000,
            eventType = TelemetryEventType.Touch,
            sessionId = "session-1",
            data = JSONObject()
                .put("screen", "testing")
                .put("x", 12.5)
                .put("y", 34.0),
        )

        val json = JSONObject(event.toJsonLine())
        assertEquals(1_712_345_678_000, json.getLong("timestamp"))
        assertEquals("touch", json.getString("event_type"))
        assertEquals("session-1", json.getString("session_id"))
        assertEquals("testing", json.getJSONObject("data").getString("screen"))
        assertEquals(12.5, json.getJSONObject("data").getDouble("x"), 0.0)
    }
}
