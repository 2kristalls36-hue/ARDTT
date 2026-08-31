package com.nonamevpn.app.deploy

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Provision admin API on VPS (:9100) — health + users/profiles. */
object ProvisionAdminApi {
    data class HealthInfo(
        val ok: Boolean,
        val deployVersion: String = "",
    )

    data class UserSummary(
        val name: String,
        val hostId: Int,
        val deviceId: String,
        val deviceIds: List<String> = emptyList(),
        val maxDevices: Int = 1,
        val hideIp: Boolean,
        val createdAt: String,
        val expiresAt: Long = 0L,
        val deactivated: Boolean = false,
        val lastSeenAt: Long = 0L,
        val lastExternalIp: String = "",
        val online: Boolean = false,
        val offlineForSec: Long = 0L,
        val downBytes: Long = 0L,
        val upBytes: Long = 0L,
        val trafficLimitBytes: Long = 0L,
    ) {
        val usedBytes: Long get() = (downBytes + upBytes).coerceAtLeast(0L)
    }

    fun provisionBase(target: DeployTarget): String {
        val host = target.publicHost.ifBlank { target.host }.trim()
        return "http://$host:9100"
    }

    suspend fun health(baseUrl: String): Result<HealthInfo> = withContext(Dispatchers.IO) {
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
            val o = runCatching { JSONObject(body) }.getOrNull()
            val ok = o?.optBoolean("ok", false)
                ?: body.contains("ok", ignoreCase = true)
                || code == 200
            HealthInfo(
                ok = ok,
                deployVersion = o?.optString("deployVersion").orEmpty().trim(),
            )
        }
    }

    /** Convenience for callers that only need online boolean. */
    suspend fun healthOk(baseUrl: String): Result<Boolean> =
        health(baseUrl).map { it.ok }

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

    suspend fun createUser(
        baseUrl: String,
        name: String,
        days: Int = 0,
        maxDevices: Int = 1,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users")
            val payload = JSONObject()
                .put("name", name.trim())
                .put("days", days.coerceAtLeast(0))
                .put("maxDevices", maxDevices.coerceAtLeast(1))
                .toString()
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

    suspend fun updateUser(
        baseUrl: String,
        name: String,
        maxDevices: Int? = null,
        days: Int? = null,
        deactivated: Boolean? = null,
        clearDevices: Boolean = false,
        trafficLimitGb: Int? = null,
    ): Result<UserSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users/update")
            val payload = JSONObject().put("name", name.trim())
            maxDevices?.let { payload.put("maxDevices", it.coerceAtLeast(1)) }
            days?.let { payload.put("days", it.coerceAtLeast(0)) }
            deactivated?.let { payload.put("deactivated", it) }
            if (clearDevices) payload.put("clearDevices", true)
            trafficLimitGb?.let { payload.put("trafficLimitGb", it.coerceAtLeast(0)) }
            postJsonUser(url, payload)
        }
    }

    suspend fun unbindDevice(
        baseUrl: String,
        name: String,
        deviceId: String,
    ): Result<UserSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users/unbind-device")
            val payload = JSONObject()
                .put("name", name.trim())
                .put("deviceId", deviceId.trim())
            postJsonUser(url, payload)
        }
    }

    suspend fun deleteUser(
        baseUrl: String,
        name: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users/delete")
            val payload = JSONObject().put("name", name.trim()).toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 12_000
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
            Unit
        }
    }

    suspend fun reportPresence(
        baseUrl: String,
        deviceId: String,
        name: String,
        externalIp: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/presence")
            val payload = JSONObject()
                .put("deviceId", deviceId.trim())
                .put("name", name.trim())
                .put("externalIp", externalIp.trim())
                .toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 6_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code")
            Unit
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

    private fun postJsonUser(url: URL, payload: JSONObject): UserSummary {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        }.getOrDefault("")
        conn.disconnect()
        if (code !in 200..299) error("HTTP $code: $body")
        return parseUser(JSONObject(body))
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
                add(parseUser(arr.getJSONObject(i)))
            }
        }
    }

    private fun parseUser(o: JSONObject): UserSummary {
        val deviceIds = mutableListOf<String>()
        o.optJSONArray("deviceIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { deviceIds.add(it) }
            }
        }
        val primary = o.optString("deviceId")
        if (primary.isNotBlank() && primary !in deviceIds) deviceIds.add(0, primary)
        val downBytes = optLongAny(o, "downBytes", "down_bytes")
        val upBytes = optLongAny(o, "upBytes", "up_bytes")
        val trafficLimitBytes = optLongAny(o, "trafficLimitBytes", "traffic_limit_bytes")
        return UserSummary(
            name = o.optString("name"),
            hostId = o.optInt("hostId", 0),
            deviceId = primary,
            deviceIds = deviceIds,
            maxDevices = o.optInt("maxDevices", 1).coerceAtLeast(1),
            hideIp = o.optBoolean("hideIp", false),
            createdAt = o.optString("createdAt"),
            expiresAt = o.optLong("expiresAt", 0L),
            deactivated = o.optBoolean("deactivated", false),
            lastSeenAt = o.optLong("lastSeenAt", 0L),
            lastExternalIp = o.optString("lastExternalIp"),
            online = o.optBoolean("online", false),
            offlineForSec = o.optLong("offlineForSec", 0L),
            downBytes = downBytes,
            upBytes = upBytes,
            trafficLimitBytes = trafficLimitBytes,
        )
    }

    private fun optLongAny(o: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            if (o.has(key) && !o.isNull(key)) {
                return o.optLong(key, 0L)
            }
        }
        return 0L
    }
}
