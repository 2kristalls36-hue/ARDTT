package com.nonamevpn.app.bypass

import android.webkit.CookieManager

/** Cookie helpers for VK login WebView → calls.start. */
object VkSession {
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
    }

    fun loginStartUrl(attempt: Int): String = when (attempt) {
        0 -> "https://m.vk.ru/login"
        1 -> "https://m.vk.ru/"
        else -> "https://vk.ru/login"
    }

    fun looksLikeLoginUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("login") || u.contains("id.vk.") || u.contains("/oauth") ||
            u.contains("act=auth") || u.contains("act=login")
    }
}
