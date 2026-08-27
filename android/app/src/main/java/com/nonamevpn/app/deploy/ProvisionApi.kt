package com.nonamevpn.app.deploy

import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.profile.VpnProfileJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Provision HTTP API on VPS (`http://{publicHost}:9100`).
 * Create user by name only — free, no days/devices limits.
 */
object ProvisionApi {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    data class ProvisionUser(
        val name: String,
        val hostId: Int,
        val deviceId: String,
        val hideIp: Boolean = false,
    )

    fun baseUrl(publicHost: String): String {
        val host = publicHost.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        require(host.isNotBlank()) { "Пустой publicHost" }
        return "http://$host:9100"
    }

    suspend fun listUsers(publicHost: String): Result<List<ProvisionUser>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl(publicHost)}/v1/users"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GET users HTTP ${resp.code}: $body")
                }
                parseUsers(body)
            }
        }
    }

    /**
     * POST /v1/users with `{ "name": "..." }` — returns full profile JSON.
     * Free create: no days / maxDevices limits.
     */
    suspend fun createUser(publicHost: String, name: String): Result<Pair<VpnProfile, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clean = name.trim()
                require(clean.isNotBlank()) { "Имя обязательно" }
                val url = "${baseUrl(publicHost)}/v1/users"
                val payload = JSONObject().put("name", clean).toString()
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(jsonMedia))
                    .header("Content-Type", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("POST users HTTP ${resp.code}: $body")
                    }
                    val profile = VpnProfileJson.parse(body)
                    profile to body
                }
            }
        }

    /** GET /v1/profile/{name} — full profile JSON for an existing user. */
    suspend fun fetchProfile(publicHost: String, name: String): Result<Pair<VpnProfile, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clean = name.trim()
                require(clean.isNotBlank()) { "Имя обязательно" }
                val url = "${baseUrl(publicHost)}/v1/profile/${java.net.URLEncoder.encode(clean, Charsets.UTF_8.name())}"
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GET profile HTTP ${resp.code}: $body")
                    }
                    val profile = VpnProfileJson.parse(body)
                    profile to body
                }
            }
        }

    suspend fun health(publicHost: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl(publicHost)}/health"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }
    }

    private fun parseUsers(raw: String): List<ProvisionUser> {
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    ProvisionUser(
                        name = o.optString("name", ""),
                        hostId = o.optInt("hostId", 0),
                        deviceId = o.optString("deviceId", ""),
                        hideIp = o.optBoolean("hideIp", false),
                    ),
                )
            }
        }
    }
}
