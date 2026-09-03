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
): NetworkMapLayout {
    val hops = buildList {
        add(NetworkMapHop(NetworkMapHopKind.Provider, NetworkMapCopy.PROVIDER))
        if (!sessionUp) return@buildList
        val vps1 = hopHost(profileHost)
            ?: hopHost(server?.publicHost)
            ?: hopHost(server?.host)
        val vps2 = resolveCascadeExitHost(server, vps1, observedLastHop)
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

/** Deploy cascade host, else a live last-hop IP that is not the entry VPS or CloudFlare. */
internal fun resolveCascadeExitHost(
    server: DeployTarget?,
    vps1: String?,
    observedLastHop: String?,
): String? {
    val fromDeploy = if (server?.cascadeEnabled == true) hopHost(server.cascadeHost) else null
    if (!fromDeploy.isNullOrBlank() && lastHopCanBeVps2(vps1, fromDeploy)) {
        return fromDeploy
    }
    val last = hopHost(observedLastHop)
    if (lastHopCanBeVps2(vps1, last)) return last
    return null
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
