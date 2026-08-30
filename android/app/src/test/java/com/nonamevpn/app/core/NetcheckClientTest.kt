package com.nonamevpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetcheckClientTest {
    @Test
    fun pendingRowsWhileReportMissing() {
        val rows = NetcheckClient.uiRows(null)
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.pending })
        assertEquals("Netflix", rows[1].label)
    }

    @Test
    fun fillsKnownIdsAndKeepsSpinnersForMissing() {
        val report = NetcheckClient.parse(
            """
            {
              "ok": true,
              "viaWarp": false,
              "cached": true,
              "items": [
                {"id":"netflix","label":"Netflix","status":"ok","detail":"доступен"},
                {"id":"ip_type","label":"Тип IP","status":"hosting","detail":"хостинг · Cloudflare"}
              ]
            }
            """.trimIndent(),
        )
        val rows = NetcheckClient.uiRows(report)
        assertEquals(false, rows[0].pending)
        assertEquals("хостинг · Cloudflare", rows[0].value)
        assertEquals(NetcheckTone.Warn, rows[0].tone)
        assertEquals("доступен", rows[1].value)
        assertEquals(NetcheckTone.Ok, rows[1].tone)
        assertTrue(rows[2].pending)
        assertTrue(rows[3].pending)
        assertTrue(rows[4].pending)
    }
}
