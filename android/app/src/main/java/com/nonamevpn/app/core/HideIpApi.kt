package com.nonamevpn.app.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Talks to provision `/v1/hide-ip` so VPS policy-routes this host via WARP. */
object HideIpApi {
    suspend fun setHideIp(
        provisionBaseUrl: String?,
        deviceId: String?,
        enabled: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val base = provisionBaseUrl?.trimEnd('/') ?: return@withContext Result.failure(
            IllegalStateException("Нет provision URL в профиле"),
        )
        if (deviceId.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Нет deviceId в профиле"))
        }
        runCatching {
            val url = URL("$base/v1/hide-ip")
            val body = JSONObject()
                .put("deviceId", deviceId)
                .put("hideIp", enabled)
                .toString()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException("hide-ip HTTP $code: $resp")
            }
            AppLog.i("HideIP", "provision hideIp=$enabled ok device=$deviceId")
        }
    }
}
