package com.ardtt.app.ui.admin

import androidx.compose.ui.graphics.Color
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.EgressIpProbe
import com.ardtt.app.core.IpApiInfo
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi

/** Labels for the Network tab connection map. */
internal object NetworkMapCopy {
    const val SUBTITLE = "Карта подключения"
    const val PROVIDER = "IP провайдера"
    const val VPS = "IP VPS"
    const val VPS1 = "IP VPS 1"
    const val VPS2 = "IP VPS 2"
    const val CLOUDFLARE = "IP CloudFlare"
    const val CLOUDFLARE_IP = "IP"
    const val CLOUDFLARE_NAME = "CloudFlare"
}

internal enum class NetworkMapHopKind {
    Provider,
    Vps,
    Vps1,
    Vps2,
    Cloudflare,
}

internal data class NetworkMapHop(
    val kind: NetworkMapHopKind,
    val title: String,
    /** Known public host/IP for VPS cards; probed live for provider / CloudFlare. */
    val knownHost: String? = null,
)

internal data class NetworkMapHopView(
    val hop: NetworkMapHop,
    val info: IpApiInfo,
    val loading: Boolean,
)

internal data class NetworkMapCacheKey(
    val sessionUp: Boolean,
    val profileHost: String?,
    val hideIp: Boolean,
    val serverId: String?,
    val cascadeEnabled: Boolean,
    val cascadeHost: String,
    val provisionBase: String?,
    val deviceId: String?,
)

internal data class NetworkMapSnapshot(
    val key: NetworkMapCacheKey? = null,
    val liveCascade: ProvisionAdminApi.LiveCascadeInfo? = null,
    val hopPings: HopHealthPings = HopHealthPings(),
    val hops: List<NetworkMapHopView> = emptyList(),
)

internal fun networkMapCacheKey(
    sessionUp: Boolean,
    profileHost: String?,
    hideIp: Boolean,
    server: DeployTarget?,
    provisionBase: String?,
    deviceId: String?,
) = NetworkMapCacheKey(
    sessionUp = sessionUp,
    profileHost = profileHost,
    hideIp = hideIp,
    serverId = server?.id,
    cascadeEnabled = server?.cascadeEnabled == true,
    cascadeHost = server?.cascadeHost.orEmpty(),
    provisionBase = provisionBase?.trim()?.takeIf { it.isNotBlank() },
    deviceId = deviceId?.trim()?.takeIf { it.isNotBlank() },
)

/** Keep filled cards across Connecting (soft reconnect). Drop them once the tunnel is down. */
internal fun networkMapKeepCards(state: ConnState): Boolean = when (state) {
    ConnState.Connected, ConnState.Connecting -> true
    else -> false
}

internal fun shouldClearNetworkMapCards(previous: ConnState, next: ConnState): Boolean =
    networkMapKeepCards(previous) && !networkMapKeepCards(next)

internal fun networkMapHasFilledCards(hops: List<NetworkMapHopView>): Boolean =
    hops.any { it.info.ip.isNotBlank() }

/**
 * Returning to «Сеть» while the same VPN session is up must not rebuild cards.
 * Pull-to-refresh still reloads; Connecting keeps the last filled snapshot.
 */
internal fun shouldSkipNetworkMapAutoload(
    state: ConnState,
    storedKey: NetworkMapCacheKey?,
    currentKey: NetworkMapCacheKey,
    hops: List<NetworkMapHopView>,
): Boolean {
    if (!networkMapHasFilledCards(hops)) return false
    if (state == ConnState.Connecting || state == ConnState.Disconnecting) return true
    if (!currentKey.sessionUp) return false
    return storedKey == currentKey
}

internal fun syncNetworkMapHopViews(
    layout: NetworkMapLayout,
    previous: List<NetworkMapHopView>,
): List<NetworkMapHopView> {
    return layout.hops.map { hop ->
        val old = previous.firstOrNull { it.hop.kind == hop.kind }
        val known = hop.knownHost
        val sameKnown = known != null && sameHopHost(old?.info?.ip, known)
        val info = when {
            known != null && sameKnown -> old?.info ?: IpApiInfo(ip = known, subtitle = "")
            known != null -> IpApiInfo(
                ip = known,
                subtitle = old?.info?.subtitle.orEmpty(),
            )
            else -> old?.info ?: IpApiInfo.Empty
        }
        NetworkMapHopView(
            hop = hop,
            info = info,
            loading = info.ip.isBlank() && info.error == null,
        )
    }
}

internal fun replaceNetworkMapHopView(
    current: List<NetworkMapHopView>,
    next: NetworkMapHopView,
): List<NetworkMapHopView> {
    if (current.none { it.hop.kind == next.hop.kind }) return current + next
    return current.map { if (it.hop.kind == next.hop.kind) next else it }
}

internal data class NetworkMapLayout(
    val hops: List<NetworkMapHop>,
) {
    val titles: List<String> get() = hops.map { it.title }
    val showCloudflare: Boolean get() = hops.any { it.kind == NetworkMapHopKind.Cloudflare }
    val vps1Host: String? get() = hops.firstOrNull {
        it.kind == NetworkMapHopKind.Vps || it.kind == NetworkMapHopKind.Vps1
    }?.knownHost
    val vps2Host: String? get() = hops.firstOrNull { it.kind == NetworkMapHopKind.Vps2 }?.knownHost
}

/** VPN hops (VPS / CloudFlare) only exist while the tunnel is actually up. */
internal fun networkMapShowsVpnHops(state: ConnState): Boolean = state == ConnState.Connected

/**
 * Connection map of the *live* path.
 * Disconnected → provider only. Connected → provider → VPS (or VPS 1 → VPS 2)
 * → CloudFlare when Hide-IP is on. Incognito never hides addresses.
 *
 * VPS 2 comes only from live entry /health or the matching deploy card.
 * A different WAN IP or another server in the list is not a cascade.
 */
internal fun buildNetworkMapLayout(
    profileHost: String?,
    server: DeployTarget?,
    hideIp: Boolean,
    sessionUp: Boolean,
    /** Null until /health is known. enabled=false hides VPS 2 even if the card still has a flag. */
    liveCascade: ProvisionAdminApi.LiveCascadeInfo? = null,
): NetworkMapLayout {
    val hops = buildList {
        add(NetworkMapHop(NetworkMapHopKind.Provider, NetworkMapCopy.PROVIDER))
        if (!sessionUp) return@buildList
        val vps1 = hopHost(profileHost)
            ?: hopHost(server?.publicHost)
            ?: hopHost(server?.host)
        val vps2 = cascadeExitHost(vps1, server, liveCascade)
        if (!vps1.isNullOrBlank()) {
            if (vps2 != null) {
                add(NetworkMapHop(NetworkMapHopKind.Vps1, NetworkMapCopy.VPS1, vps1))
                add(NetworkMapHop(NetworkMapHopKind.Vps2, NetworkMapCopy.VPS2, vps2))
            } else {
                add(NetworkMapHop(NetworkMapHopKind.Vps, NetworkMapCopy.VPS, vps1))
            }
        }
        if (hideIp) {
            add(NetworkMapHop(NetworkMapHopKind.Cloudflare, NetworkMapCopy.CLOUDFLARE))
        }
    }
    return NetworkMapLayout(hops)
}

/** Exit IP when cascade is on: health host first, else the deploy card. */
internal fun cascadeExitHost(
    vps1: String?,
    server: DeployTarget?,
    liveCascade: ProvisionAdminApi.LiveCascadeInfo? = null,
): String? {
    if (liveCascade?.enabled == false) return null
    val fromHealth = hopHost(liveCascade?.host)
    val fromCard = server
        ?.takeIf { DeployHop.isCascadeEntry(it, vps1) }
        ?.let { hopHost(it.cascadeHost) }
    return listOf(fromHealth, fromCard).firstOrNull { usableAsVps2(vps1, it) }
}

internal fun usableAsVps2(vps1: String?, lastHop: String?): Boolean {
    val last = hopHost(lastHop) ?: return false
    if (sameHopHost(vps1, last)) return false
    if (EgressIpProbe.isLikelyCloudflare(last)) return false
    return true
}

internal fun findMatchingDeployServer(
    servers: List<DeployTarget>,
    profileHost: String?,
): DeployTarget? = DeployHop.matchingServer(servers, profileHost)

internal fun hopHost(raw: String?): String? = DeployHop.host(raw)

internal fun sameHopHost(a: String?, b: String?): Boolean = DeployHop.same(a, b)

internal fun provisionUrlForHost(host: String?): String? = DeployHop.provisionUrl(host)

internal fun lastHopProvisionUrls(entry: String?, exit: String?): List<String> =
    DeployHop.lastHopProvisionUrls(entry, exit)

/**
 * Provider stays on the map even when lookup fails (empty IP / error).
 * Other hops need a real address. CloudFlare is omitted if it duplicates a VPS hop.
 */
internal fun shouldShowFilledHop(
    kind: NetworkMapHopKind,
    ip: String,
    earlierIps: Collection<String>,
): Boolean {
    if (kind == NetworkMapHopKind.Provider) return true
    if (ip.isBlank()) return false
    if (kind == NetworkMapHopKind.Cloudflare && earlierIps.any { sameHopHost(it, ip) }) {
        return false
    }
    return true
}

/** Headline for a hop card: the address, or a lookup error when the IP is missing. */
internal fun hopCardPrimaryText(info: IpApiInfo, loading: Boolean = false): String {
    if (info.ip.isNotBlank()) return info.ip
    if (loading) return "Определение…"
    return info.error?.trim()?.takeIf { it.isNotBlank() } ?: "Не удалось определить IP"
}

/** RTT from GET /health on the entry and (when cascade) exit provision. */
internal data class HopHealthPings(
    val entryMs: Long = -1L,
    val exitMs: Long = -1L,
)

internal fun isLastFilledHop(index: Int, filledCount: Int): Boolean =
    filledCount > 0 && index == filledCount - 1

internal enum class HopCardOutline {
    Last,
    Other,
}

internal fun hopCardOutline(index: Int, filledCount: Int): HopCardOutline =
    if (isLastFilledHop(index, filledCount)) HopCardOutline.Last else HopCardOutline.Other

/** Gray rim for unhighlighted hop cards and the stick between them (theme outline is blue-gray). */
internal fun hopMapGrayStroke(outline: Color): Color {
    val luma = 0.299f * outline.red + 0.587f * outline.green + 0.114f * outline.blue
    return Color(red = luma, green = luma, blue = luma, alpha = outline.alpha)
}

internal fun hopCardStrokeColor(highlighted: Boolean, outline: Color, connected: Color): Color =
    if (highlighted) connected else hopMapGrayStroke(outline)

/** VPS / VPS 1 use entry health; VPS 2 uses exit health. Provider / CloudFlare have no provision ping. */
internal fun hopHealthPingMs(kind: NetworkMapHopKind, pings: HopHealthPings): Long = when (kind) {
    NetworkMapHopKind.Vps, NetworkMapHopKind.Vps1 -> pings.entryMs
    NetworkMapHopKind.Vps2 -> pings.exitMs
    NetworkMapHopKind.Provider, NetworkMapHopKind.Cloudflare -> -1L
}

internal fun hopPingLabel(kind: NetworkMapHopKind, pings: HopHealthPings): String =
    formatHealthPingMs(hopHealthPingMs(kind, pings))

/** Cloudflare title is split so the WARP mark can sit between «IP» and the name. */
internal data class HopTitleLayout(
    val leadingText: String,
    val showCloudflareMark: Boolean,
    val trailingText: String = "",
)

internal fun hopTitleLayout(kind: NetworkMapHopKind, title: String): HopTitleLayout {
    if (kind != NetworkMapHopKind.Cloudflare) {
        return HopTitleLayout(leadingText = title, showCloudflareMark = false)
    }
    return HopTitleLayout(
        leadingText = NetworkMapCopy.CLOUDFLARE_IP,
        showCloudflareMark = true,
        trailingText = NetworkMapCopy.CLOUDFLARE_NAME,
    )
}
