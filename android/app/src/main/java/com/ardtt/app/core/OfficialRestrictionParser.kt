package com.ardtt.app.core

/**
 * Parses official Russian news RSS (TASS / RIA / Interfax) for mobile-internet
 * restriction bulletins. These agencies are on the Minkomsvyaz whitelist, so
 * the feeds stay reachable when operator БС is on.
 *
 * This is not a live TSPU status API: it corroborates probes, never replaces them.
 */
internal object OfficialRestrictionParser {

    const val ACTIVE_TTL_MS = 36L * 60L * 60L * 1000L

    enum class Kind {
        ActiveRestriction,
        RestrictionLifted,
        ListUpdate,
        Other,
    }

    data class Item(
        val source: String,
        val title: String,
        val link: String?,
        val publishedAtMs: Long,
        val kind: Kind,
        val region: String?,
    )

    data class RssEntry(
        val title: String,
        val description: String,
        val link: String?,
        val publishedAtMs: Long?,
    )

    fun parseRss(xml: String, source: String, nowMs: Long = System.currentTimeMillis()): List<Item> {
        return extractEntries(xml).mapNotNull { entry ->
            val published = entry.publishedAtMs ?: return@mapNotNull null
            val blob = "${entry.title}\n${entry.description}"
            val kind = classify(blob)
            Item(
                source = source,
                title = entry.title.trim(),
                link = entry.link,
                publishedAtMs = published,
                kind = kind,
                region = extractRegion(blob),
            )
        }.filter { nowMs - it.publishedAtMs <= ACTIVE_TTL_MS * 2 }
    }

    fun headline(items: List<Item>, nowMs: Long = System.currentTimeMillis()): String? {
        val recent = items.filter { nowMs - it.publishedAtMs in 0..ACTIVE_TTL_MS }
        val newestActive = recent
            .filter { it.kind == Kind.ActiveRestriction }
            .maxByOrNull { it.publishedAtMs }
        val newestLifted = recent
            .filter { it.kind == Kind.RestrictionLifted }
            .maxByOrNull { it.publishedAtMs }
        if (newestLifted != null &&
            (newestActive == null || newestLifted.publishedAtMs >= newestActive.publishedAtMs)
        ) {
            return null
        }
        return newestActive?.let { formatHeadline(it) }
    }

    fun formatHeadline(item: Item): String {
        val region = item.region?.takeIf { it.isNotBlank() }?.let { " в $it" }.orEmpty()
        return "${item.source}: ограничения мобильного интернета$region"
    }

    fun classify(raw: String): Kind {
        val text = normalize(raw)
        if (text.isBlank()) return Kind.Other
        val restrictionContext = RESTRICTION_CONTEXT.any { it.containsMatchIn(text) }
        if (!restrictionContext) return Kind.Other
        if (LIST_UPDATE.any { it.containsMatchIn(text) }) return Kind.ListUpdate
        if (LIFTED.any { it.containsMatchIn(text) }) return Kind.RestrictionLifted
        if (ACTIVATED.any { it.containsMatchIn(text) }) return Kind.ActiveRestriction
        return Kind.Other
    }

    fun extractRegion(raw: String): String? {
        val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ")
        for (pattern in REGION_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val value = match.groupValues.last { it.isNotBlank() && it != match.value }.trim()
            if (value.isBlank()) continue
            return displayRegion(value)
        }
        val lower = normalize(raw)
        return REGION_ALIASES.entries.firstOrNull { lower.contains(it.key) }?.value
    }

    fun extractEntries(xml: String): List<RssEntry> {
        val items = mutableListOf<RssEntry>()
        var cursor = 0
        while (true) {
            val start = xml.indexOf("<item", cursor, ignoreCase = true)
            if (start < 0) break
            val openEnd = xml.indexOf('>', start)
            if (openEnd < 0) break
            val end = xml.indexOf("</item>", openEnd, ignoreCase = true)
            if (end < 0) break
            val block = xml.substring(openEnd + 1, end)
            cursor = end + 7
            val title = innerXml(block, "title")?.let { decodeXml(it) }?.trim().orEmpty()
            if (title.isBlank()) continue
            val description = innerXml(block, "description")?.let { decodeXml(it) }.orEmpty()
            val link = innerXml(block, "link")?.let { decodeXml(it) }?.trim()
                ?: innerXml(block, "guid")?.let { decodeXml(it) }?.trim()
            val published = innerXml(block, "pubDate")?.let { decodeXml(it) }?.let { parsePubDate(it) }
            items += RssEntry(
                title = stripTags(title),
                description = stripTags(description),
                link = link,
                publishedAtMs = published,
            )
        }
        return items
    }

    fun parsePubDate(raw: String): Long? {
        val trimmed = normalizePubDate(raw.trim())
        if (trimmed.isBlank()) return null
        for (pattern in PUB_DATE_PATTERNS) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    isLenient = false
                }.parse(trimmed)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    internal fun decodeXml(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("<![CDATA[", ignoreCase = true) && text.endsWith("]]>")) {
            text = text.substring(9, text.length - 3)
        }
        return text
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("&#(\\d+);")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
            }
    }

    private fun innerXml(block: String, tag: String): String? {
        val open = Regex("<$tag(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(block) ?: return null
        val closeTag = "</$tag>"
        val close = block.indexOf(closeTag, open.range.last + 1, ignoreCase = true)
        if (close < 0) return null
        return block.substring(open.range.last + 1, close)
    }

    private fun stripTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace('ё', 'е')
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[«»\"“”]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizePubDate(raw: String): String {
        return if (Regex("""[+-]\d{3}$""").containsMatchIn(raw)) raw + "0" else raw
    }

    private fun displayRegion(raw: String): String {
        val trimmed = raw.trim().trim(',', '.', ' ')
        return when {
            trimmed.equals("москве", ignoreCase = true) ||
                trimmed.equals("москвы", ignoreCase = true) -> "Москве"
            trimmed.equals("санкт-петербурге", ignoreCase = true) ||
                trimmed.equals("петербурге", ignoreCase = true) -> "Санкт-Петербурге"
            else -> trimmed.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(java.util.Locale.forLanguageTag("ru-RU")) else ch.toString()
            }
        }
    }

    private val RESTRICTION_CONTEXT = listOf(
        Regex("мобильн[а-я]{0,8}\\s+интернет"),
        Regex("мобильн[а-я]{0,8}\\s+связ"),
        Regex("бел[а-я]{0,6}\\s+списк"),
        Regex("ограничен[а-я]{0,8}\\s+связ"),
    )

    private val ACTIVATED = listOf(
        Regex("введен[а-я]{0,4}\\s+.{0,40}ограничен"),
        Regex("ввел[а-я]{0,4}\\s+.{0,40}ограничен"),
        Regex("вводят\\s+.{0,40}ограничен"),
        Regex("ограничена?\\s+работа\\s+мобильн"),
        Regex("ограничен[а-я]{0,8}.{0,40}мобильн"),
        Regex("временн[а-я]{0,4}\\s+ограничен[а-я]{0,8}\\s+.{0,30}мобильн"),
        Regex("отключен[а-я]{0,4}\\s+.{0,30}мобильн[а-я]{0,8}\\s+интернет"),
        Regex("блокировк[а-я]{0,4}\\s+.{0,30}мобильн[а-я]{0,8}\\s+интернет"),
    )

    private val LIFTED = listOf(
        Regex("ограничен[а-я]{0,8}.{0,80}(снят|завершен|прекращен|отменен)"),
        Regex("блокировк[а-я]{0,6}.{0,80}(снят|завершен|прекращен)"),
        Regex("доступ.{0,40}восстановлен"),
        Regex("оператор[а-я]{0,4}.{0,40}восстанавливают\\s+доступ"),
        Regex("мобильн[а-я]{0,8}\\s+интернет.{0,40}восстановлен"),
    )

    private val LIST_UPDATE = listOf(
        Regex("пополнил"),
        Regex("расширил"),
        Regex("стало больше"),
        Regex("включен[а-я]{0,4}\\s+в\\s+.{0,12}бел"),
        Regex("какие сайты"),
        Regex("перечень.{0,20}(сайт|сервис|ресурс)"),
        Regex("доступн[а-я]{0,6}\\s+при\\s+ограничен"),
        Regex("работают при ограничении"),
    )

    private val REGION_PATTERNS = listOf(
        Regex(
            """(?iu)в\s+(москве|санкт-петербурге|севастополе)""",
        ),
        Regex(
            """(?iu)в\s+([а-яё-]+(?:ской|ском|кой|цкой)\s+области)""",
        ),
        Regex(
            """(?iu)в\s+([а-яё-]+\s+крае)""",
        ),
        Regex(
            """(?iu)в\s+республике\s+([а-яё-]+)""",
        ),
        Regex(
            """(?iu)в\s+(донецкой|луганской|запорожской|херсонской)\s+народной\s+республике""",
        ),
    )

    private val REGION_ALIASES = mapOf(
        "москвич" to "Москве",
        "москве" to "Москве",
        "москвы" to "Москве",
        "московской области" to "Московской области",
        "петербург" to "Санкт-Петербурге",
        "ленинградской области" to "Ленинградской области",
    )

    private val PUB_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "dd MMM yyyy HH:mm:ss Z",
    )
}
