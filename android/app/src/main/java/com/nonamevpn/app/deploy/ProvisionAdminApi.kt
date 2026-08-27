package com.nonamevpn.app.deploy

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Provision admin API on VPS (:9100) — health + users/profiles. */
object ProvisionAdminApi {
    data class UserSummary(
        val name: String,
        val hostId: Int,
        val deviceId: String,
        val hideIp: Boolean,
        val createdAt: String,
    )

    fun provisionBase(target: DeployTarget): String {
        val host = target.publicHost.ifBlank { target.host }.trim()
        return "http://$host:9100"
    }

    suspend fun health(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code")
            val ok = runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(false)
            if (!ok && body.isNotBlank()) {
                // Some builds may return plain ok
                body.contains("ok", ignoreCase = true)
            } else {
                ok || code == 200
            }
        }
    }

    suspend fun listUsers(baseUrl: String): Result<List<UserSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
            }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code: $body")
            parseUsers(body)
        }
    }

    suspend fun createUser(baseUrl: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users")
            val payload = JSONObject().put("name", name.trim()).toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code: $body")
            body
        }
    }

    suspend fun profileJson(baseUrl: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val enc = java.net.URLEncoder.encode(name.trim(), Charsets.UTF_8.name())
            val url = URL("${baseUrl.trimEnd('/')}/v1/profile/$enc")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code: $body")
            body
        }
    }

    private fun parseUsers(raw: String): List<UserSummary> {
        val trimmed = raw.trim()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val o = JSONObject(trimmed)
                o.optJSONArray("users") ?: o.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    UserSummary(
                        name = o.optString("name"),
                        hostId = o.optInt("hostId", 0),
                        deviceId = o.optString("deviceId"),
                        hideIp = o.optBoolean("hideIp", false),
                        createdAt = o.optString("createdAt"),
                    ),
                )
            }
        }
    }
}
