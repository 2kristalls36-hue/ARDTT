package com.nonamevpn.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Public egress IP for traffic leaving the VPS (and Cloudflare when Hide-IP/WARP is on).
 *
 * The VPN app process is usually excluded from the TUN (Bypass TURN dial), so a
 * plain HttpURLConnection from the phone often never sees tunnel/WARP SNAT.
 * Primary source is therefore provision `/v1/egress-ip` on the VPS; local
 * ifconfig endpoints are only a last-resort fallback.
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

    /** Last successful probe path for diagnostics (provision/warp, provision, ifconfig…). */
    @Volatile
    var lastVia: String? = null
        private set

    fun current(): String? = cached.get()

    fun clear() {
        cached.set(null)
        lastError = null
        lastVia = null
    }

    /** Mark unknown while egress is changing (Hide-IP / reconnect). */
    fun invalidate() {
        cached.set(null)
        lastError = null
    }

    /**
     * @param hideIp When true, provision probes via warp0 (Cloudflare).
     * @param provisionBaseUrl Profile provision base (e.g. http://vps:9100).
     * @param deviceId Profile device id for per-user hideIp lookup on VPS.
     * @param context Used to bind sockets to underlay / VPN when needed.
     * @param viaVpn Prefer default/VPN route when talking to provision (tunnel up).
     */
    suspend fun refresh(
        hideIp: Boolean = false,
        provisionBaseUrl: String? = null,
        deviceId: String? = null,
        context: Context? = null,
        viaVpn: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // 1) Provision on VPS — source of truth for client tunnel egress / WARP.
        if (!provisionBaseUrl.isNullOrBlank()) {
            val fromProvision = runCatching {
                fetchProvisionEgressIp(
                    provisionBaseUrl = provisionBaseUrl,
                    deviceId = deviceId,
                    context = context,
                    viaVpn = viaVpn,
                    viaWarp = hideIp,
                )
            }.getOrElse {
                val msg = it.message ?: "provision failed"
                errors += msg
                AppLog.w(TAG, "provision egress failed viaWarp=$hideIp: $msg")
                null
            }
            if (!fromProvision.isNullOrBlank()) {
                cached.set(fromProvision)
                lastError = null
                lastVia = if (hideIp) "provision/warp" else "provision"
                AppLog.v(TAG, "egress ip=$fromProvision via=$lastVia")
                return@withContext fromProvision
            }
        } else {
            errors += "нет provision URL"
        }

        // 2) Local ifconfig — last resort (often wrong while app is excluded from TUN).
        val vpnNet = context?.let { pickVpnNetwork(it) }
        for (url in endpoints) {
            val ip = runCatching { fetchIp(url, vpnNet) }.getOrElse {
                errors += "${hostOf(url)}: ${it.message}"
                null
            }
            if (!ip.isNullOrBlank()) {
                cached.set(ip)
                lastError = null
                lastVia = if (vpnNet != null) "vpn+$url" else url
                AppLog.v(TAG, "egress ip=$ip via=$lastVia")
                return@withContext ip
            }
        }

        lastError = errors.firstOrNull()?.take(80) ?: "не удалось определить IP"
        AppLog.w(TAG, "egress ip failed: $lastError")
        null
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    private fun fetchProvisionEgressIp(
        provisionBaseUrl: String?,
        deviceId: String?,
        context: Context?,
        viaVpn: Boolean,
        viaWarp: Boolean,
    ): String {
        val base = provisionBaseUrl?.trimEnd('/')
            ?: error("Нет provision URL")
        val q = buildString {
            append("$base/v1/egress-ip?")
            val params = mutableListOf<String>()
            if (!deviceId.isNullOrBlank()) {
                params += "deviceId=" + java.net.URLEncoder.encode(deviceId.trim(), Charsets.UTF_8.name())
            }
            params += if (viaWarp) "viaWarp=1" else "viaWarp=0"
            append(params.joinToString("&"))
        }
        // Prefer underlay when tunnel is up but app is excluded from TUN —
        // provision :9100 is on the VPS public host, reachable without VPN.
        val underlay = context?.let { pickUnderlayNetwork(it) }
        val firstBind = when {
            !viaVpn -> underlay
            underlay != null -> underlay
            else -> null
        }
        val first = runCatching { getProvisionIp(q, firstBind) }
        if (first.isSuccess) return first.getOrThrow()
        if (firstBind != null) {
            AppLog.i(TAG, "provision egress bind failed (${first.exceptionOrNull()?.message}) — retry default")
            return getProvisionIp(q, bindNetwork = null)
        }
        throw first.exceptionOrNull() ?: IllegalStateException("provision egress failed")
    }

    private fun getProvisionIp(url: String, bindNetwork: Network?): String {
        val conn = openHttp(URL(url), bindNetwork).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) error("HTTP $code: ${body.take(120)}")
            val ip = JSONObject(body).optString("ip").trim()
            require(looksLikeIp(ip)) { "not an ip: ${ip.take(40)}" }
            return ip
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchIp(url: String, bindNetwork: Network?): String {
        val conn = openHttp(URL(url), bindNetwork).apply {
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

    internal fun looksLikeIp(value: String): Boolean {
        if (value.isBlank() || value.length > 45) return false
        if (value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
        if (value.contains(':') && value.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
            return true
        }
        return false
    }

    private const val TAG = "EgressIp"
}
