package com.ardtt.app.core

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
import com.ardtt.app.deploy.DeployHop

/**
 * Public last-hop egress IP (VPS WAN, cascade exit, or Cloudflare when Hide-IP is on).
 *
 * The VPN app process is usually excluded from the TUN, so unbound ipify would
 * show the provider address. This probe talks to provision on the last hop
 * (`exit` then entry) and only falls back to ipify bound to the VPN network.
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
     * @param provisionBaseUrl Profile provision base (e.g. http://vps:9100) — entry.
     * @param exitProvisionBaseUrl Cascade exit provision, if any. Tried first (last hop).
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
        exitProvisionBaseUrl: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val bases = DeployHop.lastHopProvisionUrls(provisionBaseUrl, exitProvisionBaseUrl)

        // Last-hop provision: cascade exit (then entry). Not the phone's underlay IP.
        for (base in bases) {
            val fromProvision = probeProvision(
                viaWarp = hideIp,
                provisionBaseUrl = base,
                deviceId = deviceId,
                context = context,
                viaVpn = viaVpn,
            )
            if (!fromProvision.isNullOrBlank()) {
                remember(
                    fromProvision,
                    if (hideIp) "provision/warp/$base" else "provision/$base",
                )
                return@withContext fromProvision
            }
            errors += "provision viaWarp=$hideIp $base failed"
        }
        if (bases.isEmpty()) {
            errors += "нет provision URL"
        }

        // VPN-bound ifconfig only — unbound sockets see the provider IP, not last-hop SNAT.
        val vpnNet = context?.let { pickVpnNetwork(it) }
        if (vpnNet != null) {
            for (url in endpoints) {
                val ip = runCatching { fetchIp(url, vpnNet) }.getOrElse {
                    errors += "${hostOf(url)}: ${it.message}"
                    null
                }
                if (!ip.isNullOrBlank()) {
                    remember(ip, "vpn+$url")
                    return@withContext ip
                }
            }
        }

        lastError = errors.firstOrNull()?.take(80) ?: "не удалось определить IP"
        AppLog.w(TAG, "egress ip failed: $lastError")
        null
    }

    /**
     * Last-hop WAN (cascade exit or single VPS), never CloudFlare.
     * Does not use entry provision — that reports VPS1 even when the path SNAT is VPS2.
     * Does not update the cached tunnel egress (Hide-IP on the Tunnel tab stays intact).
     */
    suspend fun probeLastHopWan(
        context: Context,
        exitProvisionBaseUrl: String?,
        deviceId: String?,
        viaVpn: Boolean,
        bindVpnIfNoExit: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val fromExit = probeProvision(
            viaWarp = false,
            provisionBaseUrl = exitProvisionBaseUrl,
            deviceId = deviceId,
            context = context,
            viaVpn = viaVpn,
        )
        if (!fromExit.isNullOrBlank() && !isLikelyCloudflare(fromExit)) {
            return@withContext fromExit
        }
        if (!bindVpnIfNoExit) return@withContext null
        probeVpnBoundIp(context)
    }

    /** Public IP as seen through the VPN TUN. Null when the VPN network is down. */
    suspend fun probeVpnBoundIp(context: Context): String? = withContext(Dispatchers.IO) {
        val vpnNet = pickVpnNetwork(context) ?: return@withContext null
        for (url in endpoints) {
            val ip = runCatching { fetchIp(url, vpnNet) }.getOrNull()
            if (!ip.isNullOrBlank() && !isLikelyCloudflare(ip)) {
                return@withContext ip
            }
        }
        null
    }

    /**
     * Provision `/v1/egress-ip` without touching the cached tunnel egress.
     * Used by the Network tab map so VPS / CloudFlare hops stay distinct.
     */
    suspend fun probeProvision(
        viaWarp: Boolean,
        provisionBaseUrl: String?,
        deviceId: String?,
        context: Context?,
        viaVpn: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (provisionBaseUrl.isNullOrBlank()) return@withContext null
        runCatching {
            fetchProvisionEgressIp(
                provisionBaseUrl = provisionBaseUrl,
                deviceId = deviceId,
                context = context,
                viaVpn = viaVpn,
                viaWarp = viaWarp,
            )
        }.onFailure {
            AppLog.w(TAG, "provision probe failed viaWarp=$viaWarp: ${it.message}")
        }.getOrNull()
    }

    fun remember(ip: String, via: String) {
        if (!looksLikeIp(ip)) return
        cached.set(ip)
        lastError = null
        lastVia = via
        AppLog.v(TAG, "egress ip=$ip via=$via")
    }

    fun rememberUnderlay(ip: String, via: String) {
        val usable = usableUnderlayIp(ip) ?: return
        underlayCached.set(usable)
        lastUnderlayError = null
        lastUnderlayVia = via
        AppLog.v(TAG, "underlay ip=$usable via=$via")
    }

    /**
     * Provider public IP. Prefer the underlay network (NOT_VPN). On Android 16
     * binding that network can fail with EPERM while a VPN is up — retry the
     * default route, but never accept CloudFlare / tunnel egress as provider.
     */
    suspend fun refreshUnderlay(
        context: Context,
        rejectIps: Collection<String> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val underlay = pickBestUnderlayNetwork(context)
        val fromBind = if (underlay != null) {
            probeUnderlayOn(underlay, rejectIps, errors)
        } else {
            null
        }
        if (!fromBind.isNullOrBlank()) return@withContext fromBind
        if (underlay != null) {
            val reason = errors.lastOrNull() ?: "empty"
            AppLog.i(TAG, "underlay bind failed ($reason) — retry default")
        }
        val fromDefault = probeUnderlayOn(bindNetwork = null, rejectIps = rejectIps, errors = errors)
        if (!fromDefault.isNullOrBlank()) return@withContext fromDefault
        val cachedIp = usableUnderlayIp(underlayCached.get(), rejectIps)
        if (!cachedIp.isNullOrBlank()) {
            AppLog.v(TAG, "underlay ip=$cachedIp via=cache")
            return@withContext cachedIp
        }
        lastUnderlayError = errors.firstOrNull()?.take(80) ?: "не удалось определить IP"
        AppLog.w(TAG, "underlay ip failed: $lastUnderlayError")
        null
    }

    private fun probeUnderlayOn(
        bindNetwork: Network?,
        rejectIps: Collection<String>,
        errors: MutableList<String>,
    ): String? {
        for (url in endpoints) {
            val ip = runCatching { fetchIp(url, bindNetwork) }.getOrElse {
                errors += "${hostOf(url)}: ${it.message}"
                null
            }
            val usable = usableUnderlayIp(ip, rejectIps)
            if (!usable.isNullOrBlank()) {
                rememberUnderlay(usable, url)
                return usable
            }
            if (!ip.isNullOrBlank()) {
                errors += "${hostOf(url)}: rejected $ip"
            }
        }
        return null
    }

    /**
     * True provider addresses only: public IP, not CloudFlare, not the cached
     * tunnel egress, not a VPS hop the caller already knows.
     */
    fun usableUnderlayIp(
        ip: String?,
        rejectIps: Collection<String> = emptyList(),
        tunnelIp: String? = cached.get(),
    ): String? {
        val trimmed = ip?.trim().orEmpty()
        if (!looksLikeIp(trimmed)) return null
        if (isLikelyCloudflare(trimmed)) return null
        val tunnel = tunnelIp?.trim().orEmpty()
        if (tunnel.isNotBlank() && tunnel.equals(trimmed, ignoreCase = true)) return null
        val rejected = rejectIps.map { it.trim() }.filter { it.isNotBlank() }
        if (rejected.any { it.equals(trimmed, ignoreCase = true) }) return null
        return trimmed
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /**
     * When [deviceId] is set, omit viaWarp so provision uses users.json hideIp.
     * Forcing viaWarp=0 was showing the VPS WAN while this host's /32 still
     * exited through Cloudflare (the 2ip.ru mismatch).
     */
    internal fun provisionEgressIpUrl(
        base: String,
        deviceId: String?,
        viaWarp: Boolean,
    ): String = buildString {
        append(base.trimEnd('/'))
        append("/v1/egress-ip")
        val params = mutableListOf<String>()
        if (!deviceId.isNullOrBlank()) {
            params += "deviceId=" + java.net.URLEncoder.encode(deviceId.trim(), Charsets.UTF_8.name())
        } else {
            params += if (viaWarp) "viaWarp=1" else "viaWarp=0"
        }
        if (params.isNotEmpty()) {
            append('?')
            append(params.joinToString("&"))
        }
    }

    private fun fetchProvisionEgressIp(
        provisionBaseUrl: String?,
        deviceId: String?,
        context: Context?,
        viaVpn: Boolean,
        viaWarp: Boolean,
    ): String {
        val base = provisionBaseUrl?.trimEnd('/')
            ?: error("Нет provision URL")
        val q = provisionEgressIpUrl(base, deviceId, viaWarp)
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
