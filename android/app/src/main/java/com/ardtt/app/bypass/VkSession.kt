package com.ardtt.app.bypass

import android.os.Build
import android.text.Html
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cookie helpers for VK login WebView → calls.start. */
object VkSession {
    @Volatile
    private var cachedDisplayName: String? = null

    fun hasSessionCookie(): Boolean = remixSid().length >= 8

    fun remixSid(): String {
        val cm = CookieManager.getInstance()
        val raw = listOf(
            cm.getCookie("https://vk.com"),
            cm.getCookie("https://vk.ru"),
            cm.getCookie("https://m.vk.com"),
            cm.getCookie("https://m.vk.ru"),
            cm.getCookie("https://id.vk.com"),
            cm.getCookie("https://id.vk.ru"),
        ).filterNotNull().joinToString(";")
        return raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("remixsid=") }
            ?.removePrefix("remixsid=")
            ?.trim()
            .orEmpty()
    }

    fun cookieHeader(): String {
        val cm = CookieManager.getInstance()
        val domains = listOf(
            "https://vk.com",
            "https://vk.ru",
            "https://m.vk.com",
            "https://m.vk.ru",
            "https://login.vk.com",
            "https://oauth.vk.com",
            "https://id.vk.com",
            "https://id.vk.ru",
        )
        return domains.flatMap { domain ->
            cm.getCookie(domain)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }.distinct().joinToString("; ")
    }

    fun clear() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        cachedDisplayName = null
    }

    fun loginStartUrl(attempt: Int): String = when (attempt) {
        // Login sequence: mobile vk.ru → home → desktop login.
        0 -> "https://m.vk.ru/login"
        1 -> "https://m.vk.ru/"
        else -> "https://vk.ru/login"
    }

    fun looksLikeLoginUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/login") ||
            u.contains("id.vk.") ||
            u.contains("/oauth") ||
            u.contains("act=auth") ||
            u.contains("act=login") ||
            u.contains("authorize")
    }

    suspend fun resolveDisplayName(): String? = withContext(Dispatchers.IO) {
        cachedDisplayName?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        if (!hasSessionCookie()) return@withContext null
        val cookie = cookieHeader().takeIf { it.isNotBlank() } ?: return@withContext null
        val urls = listOf("https://m.vk.com/feed", "https://vk.com/feed")
        for (url in urls) {
            val html = runCatching {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                    )
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    error("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                body
            }.getOrNull() ?: continue

            parseDisplayNameFromHtml(html)?.let { name ->
                cachedDisplayName = name
                return@withContext name
            }
        }
        null
    }

    fun rememberDisplayName(name: String?) {
        val normalized = normalizeCandidateName(name)
        if (normalized != null) {
            cachedDisplayName = normalized
        }
    }

    private fun parseDisplayNameFromHtml(html: String): String? {
        val fromProfile = Regex("top_profile_name\"\\s*:\\s*\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
        val fromOg = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
        val fromTitle = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.removeSuffix(" | ВКонтакте")
            ?.removeSuffix(" | VK")
        return sequenceOf(fromProfile, fromOg, fromTitle)
            .mapNotNull { decodeHtml(it) }
            .mapNotNull { normalizeCandidateName(it) }
            .firstOrNull()
    }

    private fun decodeHtml(value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(raw).toString()
        }
    }

    private fun normalizeCandidateName(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.contains("vk", ignoreCase = true)) return null
        if (candidate.contains("вход", ignoreCase = true)) return null
        if (candidate.contains("login", ignoreCase = true)) return null
        if (candidate.equals("ВКонтакте", ignoreCase = true)) return null
        return candidate
    }
}
