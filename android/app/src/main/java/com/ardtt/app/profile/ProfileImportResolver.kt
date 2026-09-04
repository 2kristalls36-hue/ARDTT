package com.ardtt.app.profile

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolve profile JSON from link, subscription URL, or raw JSON text. */
object ProfileImportResolver {
    suspend fun resolve(raw: String): List<VpnProfile> = withContext(Dispatchers.IO) {
        val trimmed = raw.trim()
        when {
            trimmed.isBlank() -> error("Пустые данные")
            ProfileLinkCodec.looksLikeLink(trimmed) -> listOf(ProfileLinkCodec.parseLink(trimmed))
            looksLikeHttp(trimmed) -> VpnProfileJson.parseMany(fetchUrl(trimmed))
            else -> VpnProfileJson.parseMany(trimmed)
        }
    }

    private fun looksLikeHttp(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun fetchUrl(urlText: String): String {
        val url = URL(urlText.trim())
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.readText()
                .orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${body.take(120)}")
            }
            if (body.isBlank()) error("Пустой ответ сервера")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
