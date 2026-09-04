package com.ardtt.app.ui.admin

import com.ardtt.app.core.ConnState
import com.ardtt.app.core.EgressIpProbe
import com.ardtt.app.core.IpApiInfo
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget

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
 */
internal fun buildNetworkMapLayout(
    profileHost: String?,
    server: DeployTarget?,
    hideIp: Boolean,
    sessionUp: Boolean,
    observedLastHop: String? = null,
    liveCascadeHost: String? = null,
    cascadeLive: Boolean = false,
    servers: List<DeployTarget> = emptyList(),
): NetworkMapLayout {
    val hops = buildList {
        add(NetworkMapHop(NetworkMapHopKind.Provider, NetworkMapCopy.PROVIDER))
        if (!sessionUp) return@buildList
        val vps1 = hopHost(profileHost)
            ?: hopHost(server?.publicHost)
            ?: hopHost(server?.host)
        val vps2 = resolveCascadeExitHost(
            servers = servers,
            matched = server,
            profileHost = profileHost,
            vps1 = vps1,
            observedLastHop = observedLastHop,
            liveCascadeHost = liveCascadeHost,
            cascadeLive = cascadeLive,
        )
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

/**
 * Exit hop for a cascade path. Uses the matching deploy card, any cascade card
 * for this profile, live entry /health, the other server card when health says
 * cascade is on, then the probed last-hop WAN.
 */
internal fun resolveCascadeExitHost(
    servers: List<DeployTarget> = emptyList(),
    matched: DeployTarget?,
    profileHost: String?,
    vps1: String?,
    observedLastHop: String?,
    liveCascadeHost: String? = null,
    cascadeLive: Boolean = false,
): String? {
    val profile = profileHost ?: vps1
    val cascadeOn = cascadeLive ||
        !hopHost(liveCascadeHost).isNullOrBlank() ||
        (matched != null && DeployHop.isCascadeEntry(matched, profile)) ||
        servers.any { DeployHop.isCascadeEntry(it, profile) }
    val candidates = buildList {
        if (matched?.cascadeEnabled == true) add(hopHost(matched.cascadeHost))
        servers.forEach { server ->
            if (DeployHop.isCascadeEntry(server, profile)) add(hopHost(server.cascadeHost))
        }
        add(hopHost(liveCascadeHost))
        if (cascadeOn) add(uniqueOtherServerHost(servers, vps1))
        add(hopHost(observedLastHop))
    }
    return candidates.firstOrNull { lastHopCanBeVps2(vps1, it) }
}

internal fun uniqueOtherServerHost(servers: List<DeployTarget>, vps1: String?): String? {
    val others = servers.mapNotNull { hopHost(it.publicHost) ?: hopHost(it.host) }
        .distinctBy { it.lowercase() }
        .filter { lastHopCanBeVps2(vps1, it) }
    return others.singleOrNull()
}

internal fun lastHopCanBeVps2(vps1: String?, lastHop: String?): Boolean {
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
