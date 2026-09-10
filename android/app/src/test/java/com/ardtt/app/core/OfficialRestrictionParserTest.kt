package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialRestrictionParserTest {

    @Test
    fun activationInRegionIsActive() {
        val kind = OfficialRestrictionParser.classify(
            "В Свердловской области введены временные ограничения работы мобильного интернета",
        )
        assertEquals(OfficialRestrictionParser.Kind.ActiveRestriction, kind)
        assertEquals(
            "Свердловской области",
            OfficialRestrictionParser.extractRegion(
                "В Свердловской области введены временные ограничения работы мобильного интернета",
            ),
        )
    }

    @Test
    fun moscowSmsRestrictionsAreActive() {
        assertEquals(
            OfficialRestrictionParser.Kind.ActiveRestriction,
            OfficialRestrictionParser.classify(
                "Москвичам начали приходить SMS об ограничении мобильного интернета",
            ),
        )
        assertEquals(
            "Москве",
            OfficialRestrictionParser.extractRegion(
                "Москвичам начали приходить SMS об ограничении мобильного интернета",
            ),
        )
    }

    @Test
    fun listExpansionIsNotActivation() {
        assertEquals(
            OfficialRestrictionParser.Kind.ListUpdate,
            OfficialRestrictionParser.classify(
                "Минцифры расширило белый список сайтов, доступных при ограничениях мобильного интернета",
            ),
        )
        assertEquals(
            OfficialRestrictionParser.Kind.ListUpdate,
            OfficialRestrictionParser.classify(
                "Сервисов, доступных при ограничениях мобильного интернета, стало больше. В белый список включены сайты банков",
            ),
        )
    }

    @Test
    fun liftedRestrictionsAreDetected() {
        assertEquals(
            OfficialRestrictionParser.Kind.RestrictionLifted,
            OfficialRestrictionParser.classify(
                "Временные блокировки мобильного интернета по соображениям безопасности в Москве завершены, операторы восстанавливают доступ к интернет-ресурсам",
            ),
        )
    }

    @Test
    fun unrelatedNewsIsIgnored() {
        assertEquals(
            OfficialRestrictionParser.Kind.Other,
            OfficialRestrictionParser.classify(
                "Суд отказал AstraZeneca, просившей запретить препарат",
            ),
        )
        assertEquals(
            OfficialRestrictionParser.Kind.Other,
            OfficialRestrictionParser.classify(
                "В регионе введен режим беспилотной опасности. Нельзя подбирать обломки",
            ),
        )
    }

    @Test
    fun rssParsesCdataAndEntities() {
        val xml = """
            <rss><channel>
            <item>
              <title><![CDATA[В Пензенской области введены ограничения мобильного интернета]]></title>
              <link>https://ria.ru/example</link>
              <pubDate>Thu, 10 Sep 2026 12:00:00 +0300</pubDate>
              <description>В целях безопасности граждан введены временные ограничения работы мобильного интернета</description>
            </item>
            <item>
              <title>На Запорожье назвали действия &quot;обнулением&quot;</title>
              <pubDate>Thu, 10 Sep 2026 12:01:00 +0300</pubDate>
              <description>Политика</description>
            </item>
            </channel></rss>
        """.trimIndent()
        val now = OfficialRestrictionParser.parsePubDate("Thu, 10 Sep 2026 12:05:00 +0300")!!
        val items = OfficialRestrictionParser.parseRss(xml, "РИА Новости", nowMs = now)
        assertEquals(2, items.size)
        assertEquals(OfficialRestrictionParser.Kind.ActiveRestriction, items[0].kind)
        assertEquals("Пензенской области", items[0].region)
        assertTrue(items[1].title.contains("обнулением"))
        assertEquals(
            "РИА Новости: ограничения мобильного интернета в Пензенской области",
            OfficialRestrictionParser.headline(items, now),
        )
    }

    @Test
    fun newerLiftClearsHeadline() {
        val now = 1_778_061_600_000L
        val items = listOf(
            OfficialRestrictionParser.Item(
                source = "ТАСС",
                title = "В Москве введены ограничения мобильного интернета",
                link = null,
                publishedAtMs = now - 60_000L,
                kind = OfficialRestrictionParser.Kind.ActiveRestriction,
                region = "Москве",
            ),
            OfficialRestrictionParser.Item(
                source = "ТАСС",
                title = "В Москве ограничения мобильного интернета сняты",
                link = null,
                publishedAtMs = now - 10_000L,
                kind = OfficialRestrictionParser.Kind.RestrictionLifted,
                region = "Москве",
            ),
        )
        assertNull(OfficialRestrictionParser.headline(items, now))
    }

    @Test
    fun staleActivationIsIgnored() {
        val now = 1_778_061_600_000L
        val items = listOf(
            OfficialRestrictionParser.Item(
                source = "ТАСС",
                title = "Введены ограничения",
                link = null,
                publishedAtMs = now - OfficialRestrictionParser.ACTIVE_TTL_MS - 1_000L,
                kind = OfficialRestrictionParser.Kind.ActiveRestriction,
                region = "Москве",
            ),
        )
        assertNull(OfficialRestrictionParser.headline(items, now))
    }

    @Test
    fun riaShortTimezoneParses() {
        val ms = OfficialRestrictionParser.parsePubDate("Thu, 10 Sep 2026 18:16:34 +030")
        val full = OfficialRestrictionParser.parsePubDate("Thu, 10 Sep 2026 18:16:34 +0300")
        assertEquals(full, ms)
    }
}
