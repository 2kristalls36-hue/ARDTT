package com.ardtt.app.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelemetryFileCommentTest {
    @Test
    fun buildsEmbeddedUserCommentEventAndRedactsSecrets() {
        val line = TelemetryFileManager.buildUserCommentLine(
            """Не работает обход, {"password":"secret"}""",
            timestamp = 123L,
        )
        val json = JSONObject(line)

        assertEquals(123L, json.getLong("timestamp"))
        assertEquals("user_comment", json.getString("event_type"))
        assertEquals("user-comment", json.getString("session_id"))
        assertEquals("testing_screen", json.getJSONObject("data").getString("source"))
        assertFalse(json.getJSONObject("data").has("ticket"))
        assertFalse(json.getJSONObject("data").getString("comment").contains("secret"))

        val numbered = JSONObject(
            TelemetryFileManager.buildUserCommentLine("коротко", timestamp = 1L, ticketNumber = 7),
        )
        assertEquals(7, numbered.getJSONObject("data").getInt("ticket"))
    }
}
