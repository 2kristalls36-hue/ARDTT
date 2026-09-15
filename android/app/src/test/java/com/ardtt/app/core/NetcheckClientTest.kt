package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetcheckClientTest {
    @Test
    fun pendingSummaryWhileConnectedAndReportMissing() {
        val rows = NetcheckClient.summaryRows(null, probeActive = true)
        assertEquals(2, rows.size)
        assertEquals(NetcheckCopy.VERDICT_LABEL, rows[0].label)
        assertEquals(NetcheckCopy.IP_TYPE_LABEL, rows[1].label)
        assertTrue(rows.all { it.pending && it.value.isEmpty() })
    }

    @Test
    fun dashWithoutSpinnersWhenTunnelOff() {
        val rows = NetcheckClient.summaryRows(null, probeActive = false)
        assertTrue(rows.all { !it.pending && it.value == NetcheckCopy.IDLE })
    }

    @Test
    fun verdictIgnoresServiceNamesAndKeepsIpType() {
        val report = NetcheckClient.parse(
            """
            {
              "ok": true,
              "viaWarp": false,
              "cached": true,
              "items": [
                {"id":"netflix","label":"Netflix","status":"ok","detail":"доступен"},
                {"id":"youtube","label":"YouTube","status":"ok","detail":"доступен"},
                {"id":"ip_type","label":"Тип IP","status":"hosting","detail":"хостинг · Cloudflare"}
              ]
            }
            """.trimIndent(),
        )
        val rows = NetcheckClient.summaryRows(report, probeActive = true)
        assertEquals(NetcheckCopy.OK, rows[0].value)
        assertEquals(NetcheckTone.Ok, rows[0].tone)
        assertEquals("хостинг · Cloudflare", rows[1].value)
        assertEquals(NetcheckTone.Warn, rows[1].tone)
        assertFalse(rows.any { it.label.contains("Netflix") })
        assertFalse(NetcheckClient.summaryRows(report, probeActive = false).any { it.pending })
    }

    @Test
    fun verdictBlockedBeatsRestrictions() {
        val report = NetcheckReport(
            ok = true,
            viaWarp = false,
            cached = false,
            items = listOf(
                NetcheckItem("netflix", "Netflix", "blocked", "недоступен"),
                NetcheckItem("youtube", "YouTube", "restricted", "ограничен"),
            ),
        )
        val (value, tone) = NetcheckClient.verdictOf(report)
        assertEquals(NetcheckCopy.BLOCKED, value)
        assertEquals(NetcheckTone.Error, tone)
    }

    @Test
    fun verdictRestrictedWhenNoBlocks() {
        val report = NetcheckReport(
            ok = true,
            viaWarp = false,
            cached = false,
            items = listOf(
                NetcheckItem("google", "Google", "ok", "доступен"),
                NetcheckItem("chatgpt", "ChatGPT", "restricted", "ограничен"),
            ),
        )
        val (value, tone) = NetcheckClient.verdictOf(report)
        assertEquals(NetcheckCopy.RESTRICTED, value)
        assertEquals(NetcheckTone.Warn, tone)
    }

    @Test
    fun verdictPartialWhenSomeProbesFail() {
        val report = NetcheckReport(
            ok = true,
            viaWarp = false,
            cached = false,
            items = listOf(
                NetcheckItem("google", "Google", "ok", "доступен"),
                NetcheckItem("reddit", "Reddit", "error", "не удалось проверить"),
            ),
        )
        val (value, tone) = NetcheckClient.verdictOf(report)
        assertEquals(NetcheckCopy.PARTIAL, value)
        assertEquals(NetcheckTone.Warn, tone)
    }

    @Test
    fun verdictFailedWhenAllServicesError() {
        val report = NetcheckReport(
            ok = false,
            viaWarp = true,
            cached = false,
            items = emptyList(),
        )
        val (value, tone) = NetcheckClient.verdictOf(report)
        assertEquals(NetcheckCopy.FAILED, value)
        assertEquals(NetcheckTone.Error, tone)
    }

    @Test
    fun ipTypeHostingDoesNotCountAsServiceRestriction() {
        val report = NetcheckReport(
            ok = true,
            viaWarp = true,
            cached = false,
            items = listOf(
                NetcheckItem("ip_type", "Тип IP", "hosting", "хостинг"),
                NetcheckItem("google", "Google", "ok", "доступен"),
            ),
        )
        val (value, tone) = NetcheckClient.verdictOf(report)
        assertEquals(NetcheckCopy.OK, value)
        assertEquals(NetcheckTone.Ok, tone)
    }
}
