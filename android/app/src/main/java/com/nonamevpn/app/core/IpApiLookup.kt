package com.nonamevpn.app.core

import android.content.Context
import android.net.Network
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
 * Provider path binds to underlay; tunnel path uses [EgressIpProbe] then geo lookup by IP.
 */
object IpApiLookup {
    private const val FIELDS = "status,message,query,isp,city,country,countryCode"
    private const val LOOKUP_ENDPOINT = "http://ip-api.com/json/?fields=$FIELDS"

    suspend fun fetchUnderlay(context: Context): IpApiInfo = withContext(Dispatchers.IO) {
        fetchJson(LOOKUP_ENDPOINT, pickBestUnderlayNetwork(context))
    }

    /**
     * Geo lookup for an already known public address. On geo failure the IP is
     * still returned so the Network map never blanks a hop that we already know.
     */
    suspend fun lookupAddress(context: Context, address: String): IpApiInfo = withContext(Dispatchers.IO) {
        val ip = address.trim()
        if (ip.isBlank()) {
            return@withContext IpApiInfo.Empty.copy(error = friendlyError(null))
        }
        val geo = runCatching { fetchJson(lookupEndpoint(ip), pickBestUnderlayNetwork(context)) }
            .getOrElse { IpApiInfo.Empty.copy(error = friendlyError(it.message)) }
        when {
            geo.ip.isNotBlank() -> geo.copy(
                ip = if (EgressIpProbe.looksLikeIp(ip)) ip else geo.ip,
            )
            else -> IpApiInfo(ip = ip, subtitle = "")
        }
    }

    /**
     * Tunnel egress IP from provision (works when the app process is excluded from TUN),
     * then ISP/location via ip-api for that address.
     */
    suspend fun fetchTunnelEgress(
        context: Context,
        hideIp: Boolean,
        provisionBaseUrl: String?,
        deviceId: String?,
        viaVpn: Boolean,
    ): IpApiInfo = withContext(Dispatchers.IO) {
        val ip = EgressIpProbe.refresh(
            hideIp = hideIp,
            provisionBaseUrl = provisionBaseUrl,
            deviceId = deviceId,
            context = context,
            viaVpn = viaVpn,
        )
        if (ip.isNullOrBlank()) {
            return@withContext IpApiInfo(
                ip = "",
                subtitle = "",
                error = friendlyError(EgressIpProbe.lastError),
            )
        }
        val geo = fetchJson(lookupEndpoint(ip), pickBestUnderlayNetwork(context))
        IpApiInfo(
            ip = ip,
            subtitle = geo.subtitle,
            error = null,
        )
    }

    internal fun lookupEndpoint(ip: String): String =
        "http://ip-api.com/json/${ip.trim()}?fields=$FIELDS"

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

    internal fun friendlyError(raw: String?): String {
        val msg = raw?.trim().orEmpty()
        if (msg.isBlank()) return "Не удалось определить IP"
        return when {
            msg.contains("EPERM", ignoreCase = true) ||
                msg.contains("Binding socket", ignoreCase = true) ||
                msg.contains("Operation not permitted", ignoreCase = true) ->
                "Не удалось определить IP"
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                "Превышено время ожидания"
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ->
                "Нет доступа к сети"
            else -> msg.take(80)
        }
    }

    private fun fetchJson(url: String, bindNetwork: Network?): IpApiInfo {
        val conn = openHttp(URL(url), bindNetwork).apply {
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
                return IpApiInfo(ip = "", subtitle = "", error = friendlyError("HTTP $code"))
            }
            parseResponse(body)
        } catch (t: Throwable) {
            IpApiInfo(ip = "", subtitle = "", error = friendlyError(t.message))
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttp(url: URL, bindNetwork: Network?): HttpURLConnection {
        val raw = if (bindNetwork != null) bindNetwork.openConnection(url) else url.openConnection()
        return raw as HttpURLConnection
    }
}
