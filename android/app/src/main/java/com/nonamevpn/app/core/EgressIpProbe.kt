package com.nonamevpn.app.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public egress IP as seen through the active VPN (ifconfig-style).
 * Fetched after connect and after Hide-IP flips — not on a timer.
 */
object EgressIpProbe {
    private val endpoints = listOf(
        "https://ifconfig.me/ip",
        "https://api.ipify.org",
        "https://icanhazip.com",
    )

    private val cached = AtomicReference<String?>(null)

    @Volatile
    var lastError: String? = null
        private set

    fun current(): String? = cached.get()

    fun clear() {
        cached.set(null)
        lastError = null
    }

    /** Mark unknown while egress is changing (Hide-IP / reconnect). */
    fun invalidate() {
        cached.set(null)
    }

    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        var lastFail: String? = null
        for (url in endpoints) {
            val ip = runCatching { fetchIp(url) }.getOrElse {
                lastFail = it.message
                null
            }
            if (!ip.isNullOrBlank()) {
                cached.set(ip)
                lastError = null
                AppLog.i(TAG, "egress ip=$ip via=$url")
                return@withContext ip
            }
        }
        lastError = lastFail ?: "empty"
        AppLog.w(TAG, "egress ip failed: $lastError")
        null
    }

    private fun fetchIp(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "curl/8.0")
            setRequestProperty("Accept", "text/plain")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.trim()
                .orEmpty()
            if (code !in 200..299) error("HTTP $code")
            val ip = body.lineSequence().firstOrNull()?.trim().orEmpty()
            require(looksLikeIp(ip)) { "not an ip: ${ip.take(40)}" }
            return ip
        } finally {
            conn.disconnect()
        }
    }

    internal fun looksLikeIp(value: String): Boolean {
        if (value.isBlank() || value.length > 45) return false
        // IPv4
        if (value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
        // Rough IPv6
        if (value.contains(':') && value.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
            return true
        }
        return false
    }

    private const val TAG = "EgressIp"
}
