package com.ardtt.app.core

import android.content.Context
import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Provider (underlay) and tunnel egress looked up the same way as the Network map. */
data class PublicIpPair(
    val provider: IpApiInfo,
    val tunnel: IpApiInfo,
)

private data class IpApiHopMemo(
    val key: String,
    val info: IpApiInfo,
)

/**
 * Public IP + ISP/location via ip-api.com.
 * Provider path binds to underlay; tunnel path uses provision / WARP then geo lookup by IP.
 * [fetchPublicIps] is the single entry for both addresses (parallel).
 * Tunnel and Network map share hop keys via [com.ardtt.app.deploy.DeployHop.warpExitProvisionUrl]
 * and the geo cache in [lookupAddress].
 */
object IpApiLookup {
    private const val FIELDS = "status,message,query,isp,city,country,countryCode"
    private const val LOOKUP_ENDPOINT = "http://ip-api.com/json/?fields=$FIELDS"

    private val underlayHop = AtomicReference<IpApiHopMemo?>(null)
    private val tunnelHop = AtomicReference<IpApiHopMemo?>(null)
    private val addressCache = ConcurrentHashMap<String, IpApiInfo>()
    private val underlayLock = Mutex()
    private val tunnelLock = Mutex()
    private val addressLocks = ConcurrentHashMap<String, Mutex>()

    internal fun underlayHopKey(underlayId: String): String = underlayId

    internal fun tunnelHopKey(
        hideIp: Boolean,
        provisionBaseUrl: String?,
        exitProvisionBaseUrl: String?,
        deviceId: String?,
        viaVpn: Boolean,
    ): String = listOf(
        if (hideIp) "warp" else "egress",
        provisionBaseUrl.orEmpty(),
        exitProvisionBaseUrl.orEmpty(),
        deviceId.orEmpty(),
        viaVpn,
    ).joinToString("|")

    internal fun rememberUnderlayHop(key: String, info: IpApiInfo) {
        underlayHop.set(IpApiHopMemo(key, info))
    }

    internal fun rememberTunnelHop(key: String, info: IpApiInfo) {
        tunnelHop.set(IpApiHopMemo(key, info))
    }

    internal fun peekUnderlayHop(key: String, force: Boolean): IpApiInfo? =
        peekHop(underlayHop, key, force)

    internal fun peekTunnelHop(key: String, force: Boolean): IpApiInfo? =
        peekHop(tunnelHop, key, force)

    internal fun clearPublicIpHopCache() {
        underlayHop.set(null)
        tunnelHop.set(null)
        addressCache.clear()
    }

    internal fun rememberAddress(ip: String, info: IpApiInfo) {
        val key = ip.trim()
        if (key.isBlank() || info.ip.isBlank()) return
        addressCache[key] = info
    }

    internal fun peekAddress(ip: String, force: Boolean): IpApiInfo? {
        if (force) return null
        return addressCache[ip.trim()]
    }

    private fun peekHop(
        slot: AtomicReference<IpApiHopMemo?>,
        key: String,
        force: Boolean,
    ): IpApiInfo? {
        if (force) return null
        return slot.get()?.takeIf { it.key == key }?.info
    }

    suspend fun fetchUnderlay(
        context: Context,
        rejectIps: Collection<String> = emptyList(),
        force: Boolean = false,
    ): IpApiInfo {
        val key = underlayHopKey(underlayIdentity(context))
        peekUnderlayHop(key, force)?.let { return it }
        return underlayLock.withLock {
            peekUnderlayHop(key, force)?.let { return it }
            val info = probeUnderlay(context, rejectIps, force = force)
            rememberUnderlayHop(key, info)
            info
        }
    }

    private suspend fun probeUnderlay(
        context: Context,
        rejectIps: Collection<String>,
        force: Boolean,
    ): IpApiInfo = withContext(Dispatchers.IO) {
        val bound = runCatching { fetchJson(LOOKUP_ENDPOINT, pickBestUnderlayNetwork(context)) }
            .getOrElse { IpApiInfo.Empty.copy(error = friendlyError(it.message)) }
        val boundIp = pickUnderlayIp(bound, probedIp = null, cachedIp = null, rejectIps = rejectIps)
        if (!boundIp.isNullOrBlank()) {
            EgressIpProbe.rememberUnderlay(boundIp, "ip-api")
            return@withContext bound.copy(ip = boundIp, error = null)
        }
        if (bound.error != null || bound.ip.isNotBlank()) {
            AppLog.w(TAG, "underlay ip-api failed: ${bound.error ?: bound.ip}")
        }
        val probed = runCatching { EgressIpProbe.refreshUnderlay(context, rejectIps) }.getOrNull()
        val ip = pickUnderlayIp(
            boundLookup = IpApiInfo.Empty,
            probedIp = probed,
            cachedIp = EgressIpProbe.currentUnderlay(),
            rejectIps = rejectIps,
        )
        if (ip.isNullOrBlank()) {
            return@withContext IpApiInfo.Empty.copy(
                error = bound.error ?: friendlyError(EgressIpProbe.lastUnderlayError),
            )
        }
        lookupAddress(context, ip, force = force)
    }

    /**
     * Prefer a bound ip-api hit, then HTTPS ipify, then a cache from before the
     * VPN went up. CloudFlare / VPS addresses are never the provider.
     */
    internal fun pickUnderlayIp(
        boundLookup: IpApiInfo,
        probedIp: String?,
        cachedIp: String?,
        rejectIps: Collection<String> = emptyList(),
    ): String? {
        val candidates = listOf(boundLookup.ip, probedIp, cachedIp)
        return candidates.firstNotNullOfOrNull { EgressIpProbe.usableUnderlayIp(it, rejectIps) }
    }

    /**
     * Geo lookup for an already known public address. On geo failure the IP is
     * still returned so the Network map never blanks a hop that we already know.
     * Cached by IP so Tunnel and the map share one ip-api hit.
     */
    suspend fun lookupAddress(
        context: Context,
        address: String,
        force: Boolean = false,
    ): IpApiInfo {
        val ip = address.trim()
        if (ip.isBlank()) {
            return IpApiInfo.Empty.copy(error = friendlyError(null))
        }
        peekAddress(ip, force)?.let { return it }
        val lock = addressLocks.getOrPut(ip) { Mutex() }
        return lock.withLock {
            peekAddress(ip, force)?.let { return it }
            val info = withContext(Dispatchers.IO) {
                val geo = runCatching { fetchJson(lookupEndpoint(ip), pickBestUnderlayNetwork(context)) }
                    .getOrElse { IpApiInfo.Empty.copy(error = friendlyError(it.message)) }
                when {
                    geo.ip.isNotBlank() -> geo.copy(
                        ip = if (EgressIpProbe.looksLikeIp(ip)) ip else geo.ip,
                    )
                    else -> IpApiInfo(ip = ip, subtitle = "")
                }
            }
            rememberAddress(ip, info)
            info
        }
    }

    /**
     * Provider IP and tunnel egress, in parallel, with the same probes as the Network map.
     * When [probeTunnel] is false the tunnel side stays on the in-memory cache (no extra probe).
     */
    suspend fun fetchPublicIps(
        context: Context,
        hideIp: Boolean,
        provisionBaseUrl: String?,
        exitProvisionBaseUrl: String?,
        deviceId: String?,
        viaVpn: Boolean,
        rejectIps: Collection<String> = emptyList(),
        probeTunnel: Boolean,
        force: Boolean = false,
    ): PublicIpPair = supervisorScope {
        val provider = async {
            runCatching { fetchUnderlay(context, rejectIps, force) }
                .getOrElse { IpApiInfo.Empty.copy(error = friendlyError(it.message)) }
        }
        val tunnel = async {
            val tunnelKey = tunnelHopKey(
                hideIp = hideIp,
                provisionBaseUrl = provisionBaseUrl,
                exitProvisionBaseUrl = exitProvisionBaseUrl,
                deviceId = deviceId,
                viaVpn = viaVpn,
            )
            when {
                !probeTunnel -> peekTunnelHop(tunnelKey, force = false) ?: cachedTunnelInfo()
                hideIp -> runCatching {
                    fetchWarpEgress(
                        context = context,
                        entryProvision = provisionBaseUrl,
                        exitProvision = exitProvisionBaseUrl,
                        deviceId = deviceId,
                        viaVpn = viaVpn,
                        hideIp = true,
                        force = force,
                    )
                }.getOrElse { IpApiInfo.Empty.copy(error = friendlyError(it.message)) }
                else -> runCatching {
                    fetchTunnelEgress(
                        context = context,
                        hideIp = false,
                        provisionBaseUrl = provisionBaseUrl,
                        exitProvisionBaseUrl = exitProvisionBaseUrl,
                        deviceId = deviceId,
                        viaVpn = viaVpn,
                        force = force,
                    )
                }.getOrElse { IpApiInfo.Empty.copy(error = friendlyError(it.message)) }
            }
        }
        PublicIpPair(provider = provider.await(), tunnel = tunnel.await())
    }

    /**
     * CloudFlare / WARP hop: last-hop provision with viaWarp, then ip-api geo.
     * Same sequence as the Network map CloudFlare card.
     */
    suspend fun fetchWarpEgress(
        context: Context,
        entryProvision: String?,
        exitProvision: String?,
        deviceId: String?,
        viaVpn: Boolean,
        hideIp: Boolean,
        force: Boolean = false,
    ): IpApiInfo {
        val key = tunnelHopKey(
            hideIp = hideIp,
            provisionBaseUrl = entryProvision,
            exitProvisionBaseUrl = exitProvision,
            deviceId = deviceId,
            viaVpn = viaVpn,
        )
        peekTunnelHop(key, force)?.let { return it }
        return tunnelLock.withLock {
            peekTunnelHop(key, force)?.let { return it }
            val info = probeWarpEgress(
                context = context,
                entryProvision = entryProvision,
                exitProvision = exitProvision,
                deviceId = deviceId,
                viaVpn = viaVpn,
                hideIp = hideIp,
                force = force,
            )
            rememberTunnelHop(key, info)
            info
        }
    }

    private suspend fun probeWarpEgress(
        context: Context,
        entryProvision: String?,
        exitProvision: String?,
        deviceId: String?,
        viaVpn: Boolean,
        hideIp: Boolean,
        force: Boolean,
    ): IpApiInfo = withContext(Dispatchers.IO) {
        val urls = linkedSetOf<String>()
        exitProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
        entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
        var lastIp: String? = null
        for (base in urls) {
            val ip = EgressIpProbe.probeProvision(
                viaWarp = true,
                provisionBaseUrl = base,
                deviceId = deviceId,
                context = context,
                viaVpn = viaVpn,
            )
            if (ip.isNullOrBlank()) continue
            lastIp = ip
            if (EgressIpProbe.isLikelyCloudflare(ip) || urls.size == 1) {
                if (hideIp) EgressIpProbe.remember(ip, "provision/warp")
                return@withContext lookupAddress(context, ip, force = force)
            }
        }
        if (!lastIp.isNullOrBlank()) {
            if (hideIp) EgressIpProbe.remember(lastIp, "provision/warp")
            return@withContext lookupAddress(context, lastIp, force = force)
        }
        IpApiInfo.Empty.copy(error = "Не удалось определить IP")
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
        exitProvisionBaseUrl: String? = null,
        force: Boolean = false,
    ): IpApiInfo {
        val key = tunnelHopKey(
            hideIp = hideIp,
            provisionBaseUrl = provisionBaseUrl,
            exitProvisionBaseUrl = exitProvisionBaseUrl,
            deviceId = deviceId,
            viaVpn = viaVpn,
        )
        peekTunnelHop(key, force)?.let { return it }
        return tunnelLock.withLock {
            peekTunnelHop(key, force)?.let { return it }
            val info = probeTunnelEgress(
                context = context,
                hideIp = hideIp,
                provisionBaseUrl = provisionBaseUrl,
                deviceId = deviceId,
                viaVpn = viaVpn,
                exitProvisionBaseUrl = exitProvisionBaseUrl,
                force = force,
            )
            rememberTunnelHop(key, info)
            info
        }
    }

    private suspend fun probeTunnelEgress(
        context: Context,
        hideIp: Boolean,
        provisionBaseUrl: String?,
        deviceId: String?,
        viaVpn: Boolean,
        exitProvisionBaseUrl: String?,
        force: Boolean,
    ): IpApiInfo = withContext(Dispatchers.IO) {
        val ip = EgressIpProbe.refresh(
            hideIp = hideIp,
            provisionBaseUrl = provisionBaseUrl,
            exitProvisionBaseUrl = exitProvisionBaseUrl,
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
        lookupAddress(context, ip, force = force)
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

    private fun cachedTunnelInfo(): IpApiInfo {
        val cached = EgressIpProbe.current().orEmpty()
        if (cached.isBlank()) return IpApiInfo.Empty
        return IpApiInfo(ip = cached, subtitle = "")
    }

    private const val TAG = "IpApi"
}
