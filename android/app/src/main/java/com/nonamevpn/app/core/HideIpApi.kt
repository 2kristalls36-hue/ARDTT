package com.nonamevpn.app.core

import android.content.Context
import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Talks to provision `/v1/hide-ip` so VPS policy-routes this host via WARP. */
object HideIpApi {
    /**
     * @param viaVpn When true, use the default route (through an active VPN tunnel).
     *   Required on Bypass/whitelist where underlay cannot reach VPS :9100.
     * @param tryVpnFallback After underlay failure, retry on default route (tunnel).
     */
    suspend fun setHideIp(
        provisionBaseUrl: String?,
        deviceId: String?,
        enabled: Boolean,
        context: Context? = null,
        viaVpn: Boolean = false,
        tryVpnFallback: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val base = provisionBaseUrl?.trimEnd('/') ?: return@withContext Result.failure(
            IllegalStateException("Нет provision URL в профиле"),
        )
        if (deviceId.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Нет deviceId в профиле"))
        }
        if (viaVpn) {
            return@withContext postHideIp(base, deviceId, enabled, bindNetwork = null)
        }
        // Prefer underlay so hide-ip still works while the VPN tunnel is up / broken.
        // If bind is denied (EPERM) or fails, fall back to default route immediately.
        val underlay = context?.let { pickBestUnderlayNetwork(it) }
        val first = if (underlay != null) {
            postHideIp(base, deviceId, enabled, bindNetwork = underlay)
        } else {
            postHideIp(base, deviceId, enabled, bindNetwork = null)
        }
        if (first.isSuccess || !tryVpnFallback) {
            return@withContext first
        }
        if (underlay != null) {
            AppLog.i("HideIP", "underlay failed (${first.exceptionOrNull()?.message}) — retry via default route")
            return@withContext postHideIp(base, deviceId, enabled, bindNetwork = null)
        }
        first
    }

    private fun postHideIp(
        base: String,
        deviceId: String,
        enabled: Boolean,
        bindNetwork: Network?,
    ): Result<Unit> = runCatching {
        val url = URL("$base/v1/hide-ip")
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("hideIp", enabled)
            .toString()
        val conn = openHttp(url, bindNetwork).apply {
            requestMethod = "POST"
            // Short timeouts: underlay often cannot reach :9100; fail fast and
            // retry via VPN / defer rather than block Connect for ~8s.
            connectTimeout = 2_000
            readTimeout = 2_000
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

    private fun openHttp(url: URL, bindNetwork: Network?): HttpURLConnection {
        val raw = if (bindNetwork != null) bindNetwork.openConnection(url) else url.openConnection()
        return raw as HttpURLConnection
    }
}
