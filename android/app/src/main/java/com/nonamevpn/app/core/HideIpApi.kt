package com.nonamevpn.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        context: Context? = null,
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
            // Prefer underlay so hide-ip still works while the VPN tunnel is up / broken.
            val underlay = context?.let { pickUnderlayNetwork(it) }
            val conn = openHttp(url, underlay).apply {
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

    private fun openHttp(url: URL, bindNetwork: Network?): HttpURLConnection {
        val raw = if (bindNetwork != null) bindNetwork.openConnection(url) else url.openConnection()
        return raw as HttpURLConnection
    }

    private fun pickUnderlayNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun score(n: Network): Int {
            val caps = cm.getNetworkCapabilities(n) ?: return -1
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return -1
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return -1
            var s = 1
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 4
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 8
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 2
            return s
        }
        return cm.allNetworks.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }
    }
}
