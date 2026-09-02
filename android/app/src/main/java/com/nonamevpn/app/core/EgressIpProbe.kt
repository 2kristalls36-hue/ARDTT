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
    internal const val PRIMARY_ENDPOINT = "https://api.ipify.org/"
    internal val endpoints = listOf(
        PRIMARY_ENDPOINT,
        "https://ifconfig.me/ip",
        "https://icanhazip.com",
    )

    private val cached = AtomicReference<String?>(null)
    private val underlayCached = AtomicReference<String?>(null)

    @Volatile
    var lastError: String? = null
        private set

    /** Last successful probe path for diagnostics (provision/warp, provision, ifconfig…). */
    @Volatile
    var lastVia: String? = null
        private set

    @Volatile
    var lastUnderlayError: String? = null
        private set

    @Volatile
    var lastUnderlayVia: String? = null
        private set

    fun current(): String? = cached.get()

    /** Public IP of the phone's provider path (Wi‑Fi / LTE), never through the VPN TUN. */
    fun currentUnderlay(): String? = underlayCached.get()

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

    fun invalidateUnderlay() {
        underlayCached.set(null)
        lastUnderlayError = null
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

    /**
     * Provider public IP bound to the underlay (NOT_VPN). Used on the tunnel
     * tab even when the VPN is down.
     */
    suspend fun refreshUnderlay(context: Context): String? = withContext(Dispatchers.IO) {
        val bind = pickBestUnderlayNetwork(context)
        val errors = mutableListOf<String>()
        for (url in endpoints) {
            val ip = runCatching { fetchIp(url, bind) }.getOrElse {
                errors += "${hostOf(url)}: ${it.message}"
                null
            }
            if (!ip.isNullOrBlank()) {
                underlayCached.set(ip)
                lastUnderlayError = null
                lastUnderlayVia = url
                AppLog.v(TAG, "underlay ip=$ip via=$url bind=${bind?.networkHandle}")
                return@withContext ip
            }
        }
        lastUnderlayError = errors.firstOrNull()?.take(80) ?: "не удалось определить IP"
        AppLog.w(TAG, "underlay ip failed: $lastUnderlayError")
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
        // Route selection depends on connection mode:
        // - viaVpn=true: Bypass/whitelist may require VPN route to reach provision.
        // - viaVpn=false: prefer direct underlay route.
        val underlay = context?.let { pickBestUnderlayNetwork(it) }
        val vpn = context?.let { pickVpnNetwork(it) }
        val firstBind = when {
            viaVpn -> vpn
            else -> underlay
        }
        val secondBind = when {
            viaVpn -> underlay
            else -> vpn
        }
        val first = runCatching { getProvisionIp(q, firstBind) }
        if (first.isSuccess) return first.getOrThrow()
        if (firstBind != null) {
            AppLog.i(TAG, "provision egress bind failed (${first.exceptionOrNull()?.message}) — retry default")
            val second = runCatching { getProvisionIp(q, bindNetwork = null) }
            if (second.isSuccess) return second.getOrThrow()
            if (secondBind != null && secondBind != firstBind) {
                AppLog.i(TAG, "provision egress default failed (${second.exceptionOrNull()?.message}) — retry alt bind")
                return getProvisionIp(q, bindNetwork = secondBind)
            }
            throw second.exceptionOrNull() ?: IllegalStateException("provision egress failed")
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

    internal fun looksLikeIp(value: String): Boolean {
        if (value.isBlank() || value.length > 45) return false
        if (value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
        if (value.contains(':') && value.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
            return true
        }
        return false
    }

    /** Heuristic for Cloudflare / WARP egress (Hide-IP). */
    fun isLikelyCloudflare(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val trimmed = ip.trim()
        if (!looksLikeIp(trimmed) || trimmed.contains(':')) return false
        val parts = trimmed.split('.')
        if (parts.size != 4) return false
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return when (a) {
            104 -> b in 16..31
            172 -> b in 64..71
            162 -> b == 159
            141 -> b == 101
            173 -> b == 245
            else -> false
        }
    }

    private const val TAG = "EgressIp"
}

/** Shown for IP VPN / status until provision reports a public egress address. */
const val VPN_EGRESS_CONNECTING_LABEL = "Выполняется подключение…"

fun vpnEgressIpLabel(
    publicIp: String?,
    vpnSessionActive: Boolean,
    pausedOnTrustedWifi: Boolean = false,
): String {
    if (pausedOnTrustedWifi) return "—"
    val ip = publicIp?.trim().orEmpty()
    if (ip.isNotEmpty()) return ip
    if (vpnSessionActive) return VPN_EGRESS_CONNECTING_LABEL
    return "—"
}

fun vpnSessionStatusText(
    state: ConnState,
    statusText: String,
    publicIp: String?,
): String {
    if (state == ConnState.Connecting || state == ConnState.Probing) {
        return statusText.ifBlank { VPN_EGRESS_CONNECTING_LABEL }
    }
    return statusText.ifBlank { "—" }
}
