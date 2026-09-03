package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.deploy.DeployHop
import com.nonamevpn.app.deploy.DeployTarget

/** Labels for the Network tab connection map. */
internal object NetworkMapCopy {
    const val SUBTITLE = "Карта подключения"
    const val PROVIDER = "IP провайдера"
    const val VPS = "IP VPS"
    const val VPS1 = "IP VPS 1"
    const val VPS2 = "IP VPS 2"
    const val CLOUDFLARE = "IP CloudFlare"
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

internal fun entryHost(profileHost: String?, server: DeployTarget?): String? =
    hopHost(profileHost) ?: hopHost(server?.publicHost) ?: hopHost(server?.host)

internal fun cascadePathLive(
    matched: DeployTarget?,
    profileHost: String?,
    servers: List<DeployTarget>,
    liveCascadeHost: String?,
    cascadeLive: Boolean,
): Boolean {
    val profile = profileHost ?: entryHost(profileHost, matched)
    return cascadeLive ||
        hopHost(liveCascadeHost) != null ||
        (matched != null && DeployHop.isCascadeEntry(matched, profile)) ||
        servers.any { DeployHop.isCascadeEntry(it, profile) }
}

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
        val vps1 = entryHost(profileHost, server)
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
 * Exit hop for a cascade path. Matching deploy card, other cascade cards for
 * this profile, live entry /health, the unique sibling server when cascade is
 * on, then the probed last-hop WAN.
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
    val cascadeCards = servers.filter { DeployHop.isCascadeEntry(it, profile) }
    val cascadeOn = cascadePathLive(
        matched = matched,
        profileHost = profile,
        servers = servers,
        liveCascadeHost = liveCascadeHost,
        cascadeLive = cascadeLive,
    )
    val candidates = buildList {
        if (matched?.cascadeEnabled == true) hopHost(matched.cascadeHost)?.let(::add)
        cascadeCards.forEach { hopHost(it.cascadeHost)?.let(::add) }
        hopHost(liveCascadeHost)?.let(::add)
        if (cascadeOn) uniqueOtherServerHost(servers, vps1)?.let(::add)
        hopHost(observedLastHop)?.let(::add)
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
    return !sameHopHost(vps1, last) && !EgressIpProbe.isLikelyCloudflare(last)
}

internal fun findMatchingDeployServer(
    servers: List<DeployTarget>,
    profileHost: String?,
): DeployTarget? = DeployHop.matchingServer(servers, profileHost)

internal fun hopHost(raw: String?): String? = DeployHop.host(raw)

internal fun sameHopHost(a: String?, b: String?): Boolean = DeployHop.same(a, b)

internal fun provisionUrlForHost(host: String?): String? = DeployHop.provisionUrl(host)

internal fun lastHopProvisionUrl(
    entry: String?,
    exit: String?,
    cascade: Boolean = false,
): String? = DeployHop.lastHopProvisionUrl(entry, exit, cascade)

/** Cards appear only with a real address; CloudFlare is omitted if it duplicates a VPS hop. */
internal fun shouldShowFilledHop(
    kind: NetworkMapHopKind,
    ip: String,
    earlierIps: Collection<String>,
): Boolean {
    if (ip.isBlank()) return false
    if (kind == NetworkMapHopKind.Cloudflare && earlierIps.any { sameHopHost(it, ip) }) {
        return false
    }
    return true
}
