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

data class IpApiInfo(
    val ip: String,
    val subtitle: String,
    val error: String? = null,
) {
    companion object {
        val Empty = IpApiInfo(ip = "", subtitle = "")
    }
}

/**
 * Public IP + ISP/location via ip-api.com.
 * Binds HTTP to underlay (provider) or VPN (tunnel egress) as requested.
 */
object IpApiLookup {
    private const val ENDPOINT = "http://ip-api.com/json/?fields=status,message,query,isp,city,country,countryCode"

    suspend fun fetchUnderlay(context: Context): IpApiInfo = withContext(Dispatchers.IO) {
        fetch(context, bindNetwork = pickBestUnderlayNetwork(context))
    }

    suspend fun fetchViaVpn(context: Context): IpApiInfo = withContext(Dispatchers.IO) {
        fetch(context, bindNetwork = pickVpnNetwork(context))
    }

    internal fun parseResponse(body: String): IpApiInfo {
        val json = JSONObject(body)
        if (json.optString("status") != "success") {
            val msg = json.optString("message").ifBlank { "не удалось определить" }
            return IpApiInfo(ip = "", subtitle = "", error = msg)
        }
        val ip = json.optString("query").trim()
        val isp = json.optString("isp").trim()
        val city = json.optString("city").trim()
        val country = json.optString("country").trim()
        val countryCode = json.optString("countryCode").trim()
        val location = when {
            city.isNotBlank() && countryCode.isNotBlank() -> "$city, $countryCode"
            city.isNotBlank() && country.isNotBlank() -> "$city, $country"
            countryCode.isNotBlank() -> countryCode
            country.isNotBlank() -> country
            else -> ""
        }
        val subtitle = listOf(isp, location)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return IpApiInfo(ip = ip, subtitle = subtitle)
    }

    private fun fetch(context: Context, bindNetwork: Network?): IpApiInfo {
        val conn = openHttp(URL(ENDPOINT), bindNetwork).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                return IpApiInfo(ip = "", subtitle = "", error = "HTTP $code")
            }
            parseResponse(body)
        } catch (t: Throwable) {
            IpApiInfo(ip = "", subtitle = "", error = t.message?.take(80) ?: "ошибка запроса")
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttp(url: URL, bindNetwork: Network?): HttpURLConnection {
        val raw = if (bindNetwork != null) bindNetwork.openConnection(url) else url.openConnection()
        return raw as HttpURLConnection
    }

    private fun pickVpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
