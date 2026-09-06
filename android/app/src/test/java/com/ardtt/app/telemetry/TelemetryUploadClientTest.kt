package com.ardtt.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUploadClientTest {
    @Test
    fun apiBaseStripsUploadPath() {
        assertEquals(
            "https://45.129.2.3",
            telemetryApiBase("https://45.129.2.3/api/upload-log"),
        )
        assertEquals(
            "https://example.com",
            telemetryApiBase("https://example.com/api/upload-log/"),
        )
        assertEquals("", telemetryApiBase("  "))
    }

    @Test
    fun clientStatusUrlUsesClientId() {
        assertEquals(
            "https://45.129.2.3/api/logs/client_abc/status",
            telemetryClientStatusUrl("https://45.129.2.3/api/upload-log", "client_abc"),
        )
        assertEquals("", telemetryClientStatusUrl("https://host/api/upload-log", "  "))
    }

    @Test
    fun parseUploadResponseReadsTicket() {
        val parsed = parseUploadResponse(
            """{"ok":true,"ticket":12,"filename":"a.json","read":false,"bytes":44}""",
            "fallback.json",
        )
        assertEquals(12, parsed.ticket)
        assertEquals("a.json", parsed.filename)
        assertFalse(parsed.read)
        assertEquals(44L, parsed.bytes)
    }

    @Test
    fun parseUploadResponseFallsBackWhenBodyIsNotJson() {
        val parsed = parseUploadResponse("ok", "log-a.json")
        assertEquals(0, parsed.ticket)
        assertEquals("log-a.json", parsed.filename)
    }

    @Test
    fun parseClientInboxMapsReviewSidecar() {
        val items = parseClientInbox(
            """
            {"ok":true,"logs":[
              {"filename":"a.json","ticket":4,"read":true,"comment":"после Wi‑Fi",
               "uploaded_at":"2026-09-06T12:00:00Z",
               "review":{"read":true,"processed_at":"2026-09-06T13:00:00Z","processed_by":"author","note":"разобрано"}},
              {"filename":"","ticket":5}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("a.json", item.logName)
        assertEquals(4, item.number)
        assertTrue(item.read)
        assertEquals("после Wi‑Fi", item.comment)
        assertEquals("author", item.processedBy)
        assertEquals("разобрано", item.reviewNote)
        assertTrue(item.uploadedAtMs > 0L)
    }
}
