package com.ardtt.app.deploy

import com.ardtt.app.core.AppLog
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
        /** HTTP RTT of GET /health, milliseconds. */
        val pingMs: Long = -1L,
        val cascade: Boolean = false,
        val role: String = "",
        /** Exit VPS host when this provision is a cascade entry. */
        val cascadeHost: String = "",
    )

    data class LiveCascadeInfo(
        val enabled: Boolean,
        val host: String? = null,
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
        val deviceModels: Map<String, String> = emptyMap(),
        val appVersion: String = "",
        val appVersionCode: Int = 0,
        val deviceAppVersions: Map<String, String> = emptyMap(),
        val deviceAppVersionCodes: Map<String, Int> = emptyMap(),
    ) {
        val usedBytes: Long get() = (downBytes + upBytes).coerceAtLeast(0L)
    }

    fun provisionBase(target: DeployTarget): String {
        val host = target.publicHost.ifBlank { target.host }.trim()
        return "http://$host:9100"
    }

    suspend fun health(baseUrl: String): Result<HealthInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.nanoTime()
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
            val pingMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (code !in 200..299) error("HTTP $code")
            val o = runCatching { JSONObject(body) }.getOrNull()
            val ok = o?.optBoolean("ok", false)
                ?: body.contains("ok", ignoreCase = true)
                || code == 200
            HealthInfo(
                ok = ok,
                deployVersion = o?.optString("deployVersion").orEmpty().trim(),
                pingMs = pingMs,
                cascade = o?.optBoolean("cascade", false) == true,
                role = o?.optString("role").orEmpty().trim(),
                cascadeHost = cascadeHostFromHealth(o),
            )
        }
    }

    /** Convenience for callers that only need online boolean. */
    suspend fun healthOk(baseUrl: String): Result<Boolean> =
        health(baseUrl).map { it.ok }

    internal fun cascadeHostFromHealth(o: JSONObject?): String {
        if (o == null) return ""
        val direct = o.optString("cascadeHost").trim()
        if (direct.isNotBlank()) return DeployHop.host(direct).orEmpty()
        return DeployHop.host(o.optString("cascadePeer")).orEmpty()
    }

    internal fun liveCascadeInfo(info: HealthInfo): LiveCascadeInfo {
        val enabled = info.cascade && !info.role.equals("exit", ignoreCase = true)
        val host = if (enabled) DeployHop.host(info.cascadeHost) else null
        return LiveCascadeInfo(enabled = enabled, host = host)
    }

    internal fun liveCascadeHost(info: HealthInfo): String? = liveCascadeInfo(info).host

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
            if (code !in 200..299) error(fail(code, body, "listUsers"))
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
            if (code !in 200..299) error(fail(code, body, "createUser"))
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
        newName: String? = null,
    ): Result<UserSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/users/update")
            val payload = JSONObject().put("name", name.trim())
            maxDevices?.let { payload.put("maxDevices", it.coerceAtLeast(1)) }
            days?.let { payload.put("days", it.coerceAtLeast(0)) }
            deactivated?.let { payload.put("deactivated", it) }
            if (clearDevices) payload.put("clearDevices", true)
            trafficLimitGb?.let { payload.put("trafficLimitGb", it.coerceAtLeast(0)) }
            newName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("newName", it) }
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
            if (code !in 200..299) error(fail(code, body, "deleteUser"))
            Unit
        }
    }

    suspend fun reportPresence(
        baseUrl: String,
        deviceId: String,
        name: String,
        externalIp: String = "",
        deviceModel: String = "",
        appVersion: String = "",
        appVersionCode: Int = 0,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${baseUrl.trimEnd('/')}/v1/presence")
            val payload = JSONObject()
                .put("deviceId", deviceId.trim())
                .put("name", name.trim())
                .put("externalIp", externalIp.trim())
                .put("deviceModel", deviceModel.trim())
                .put("appVersion", appVersion.trim())
                .put("appVersionCode", appVersionCode)
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
            val enc = encodePathSegment(name.trim())
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
            if (code !in 200..299) error(fail(code, body, "profileJson name=$name"))
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
        if (code !in 200..299) error(fail(code, body, "postJson ${url.path}"))
        return parseUser(JSONObject(body))
    }

    internal fun parseUsers(raw: String): List<UserSummary> {
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

    internal fun parseUser(o: JSONObject): UserSummary {
        val deviceIds = boundDeviceIdsFromJson(o)
        val primary = deviceIds.firstOrNull().orEmpty()
        val deviceModels = linkedMapOf<String, String>()
        o.optJSONObject("deviceModels")?.let { mo ->
            val keys = mo.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                mo.optString(key).trim().takeIf { it.isNotEmpty() }?.let { deviceModels[key] = it }
            }
        }
        val deviceAppVersions = linkedMapOf<String, String>()
        o.optJSONObject("deviceAppVersions")?.let { mo ->
            val keys = mo.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                mo.optString(key).trim().takeIf { it.isNotEmpty() }?.let { deviceAppVersions[key] = it }
            }
        }
        val deviceAppVersionCodes = linkedMapOf<String, Int>()
        o.optJSONObject("deviceAppVersionCodes")?.let { mo ->
            val keys = mo.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val code = jsonObjectInt(mo, key)
                if (code > 0) deviceAppVersionCodes[key] = code
            }
        }
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
            deviceModels = deviceModels,
            appVersion = o.optString("appVersion").trim(),
            appVersionCode = jsonObjectInt(o, "appVersionCode"),
            deviceAppVersions = deviceAppVersions,
            deviceAppVersionCodes = deviceAppVersionCodes,
        )
    }

    private fun fail(code: Int, body: String, op: String): String {
        val message = httpErrorMessage(code, body)
        AppLog.e("Provision", "$op → $message")
        return message
    }

    private fun optLongAny(o: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            if (o.has(key) && !o.isNull(key)) {
                return o.optLong(key, 0L)
            }
        }
        return 0L
    }

    internal fun jsonObjectInt(o: JSONObject, key: String, default: Int = 0): Int {
        if (!o.has(key) || o.isNull(key)) return default
        return when (val value = o.opt(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: default
            else -> o.optInt(key, default)
        }
    }
}

internal fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value.trim(), Charsets.UTF_8.name()).replace("+", "%20")

internal fun httpErrorMessage(code: Int, body: String): String {
    val parsed = runCatching { JSONObject(body).optString("error") }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val detail = parsed ?: body.trim().take(180)
    return when {
        code == 409 && detail.contains("already exists", ignoreCase = true) ->
            "Клиент с таким именем уже есть"
        code == 404 -> "Клиент не найден"
        detail.isNotBlank() -> detail
        else -> "Ошибка сервера ($code)"
    }
}

/** Labels for bound devices: phone model when known, otherwise the device id. */
fun deviceDisplayLabels(
    deviceIds: List<String>,
    deviceModels: Map<String, String>,
): List<String> {
    val ids = deviceIds.map { it.trim() }.filter { it.isNotEmpty() }
    return ids.map { id ->
        val model = deviceModels[id]?.trim().orEmpty()
        if (model.isNotEmpty()) model else id
    }
}

/**
 * Bound slots come from `deviceIds`. A template `deviceId` in the profile JSON
 * is not a binding — unless the payload is legacy and has no `deviceIds` key.
 */
internal fun boundDeviceIdsFromJson(o: JSONObject): List<String> {
    val fromArray = mutableListOf<String>()
    val arr = o.optJSONArray("deviceIds") ?: return listOfNotNull(
        o.optString("deviceId").trim().takeIf { it.isNotEmpty() },
    )
    for (i in 0 until arr.length()) {
        arr.optString(i).takeIf { it.isNotBlank() }?.let(fromArray::add)
    }
    return fromArray
}
