package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.profile.NetworkEndpoint

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
    val vps2Host: String? get() = hops.firstOrNull { it.kind == NetworkMapHopKind.Vps2 }?.knownHost
}

/**
 * Connection map: provider → VPS (or VPS 1 → VPS 2) → CloudFlare when Hide-IP is on.
 * Incognito never hides addresses; it adds the CloudFlare hop.
 */
internal fun buildNetworkMapLayout(
    profileHost: String?,
    server: DeployTarget?,
    hideIp: Boolean,
): NetworkMapLayout {
    val vps1 = hopHost(profileHost)
        ?: hopHost(server?.publicHost)
        ?: hopHost(server?.host)
    val cascadeHost = if (server?.cascadeEnabled == true) hopHost(server.cascadeHost) else null
    val twoVps = !vps1.isNullOrBlank() &&
        !cascadeHost.isNullOrBlank() &&
        !sameHopHost(vps1, cascadeHost)
    val hops = buildList {
        add(NetworkMapHop(NetworkMapHopKind.Provider, NetworkMapCopy.PROVIDER))
        if (!vps1.isNullOrBlank()) {
            if (twoVps) {
                add(NetworkMapHop(NetworkMapHopKind.Vps1, NetworkMapCopy.VPS1, vps1))
                add(NetworkMapHop(NetworkMapHopKind.Vps2, NetworkMapCopy.VPS2, cascadeHost))
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
 * Exact profile-host match only — do not fall back to the latest deploy,
 * or the map would show another server's cascade hop.
 */
internal fun findMatchingDeployServer(
    servers: List<DeployTarget>,
    profileHost: String?,
): DeployTarget? {
    val host = hopHost(profileHost) ?: return null
    val byPublic = servers.filter {
        hopHost(it.publicHost)?.let { pub -> sameHopHost(pub, host) } == true
    }
    if (byPublic.isNotEmpty()) {
        return byPublic.maxByOrNull { it.lastDeployedAtMs }
    }
    val byHost = servers.filter { hopHost(it.host)?.let { h -> sameHopHost(h, host) } == true }
    return byHost.maxByOrNull { it.lastDeployedAtMs }
}

internal fun hopHost(raw: String?): String? {
    val trimmed = raw?.trim()?.ifBlank { null } ?: return null
    return NetworkEndpoint.hostOf(trimmed)?.ifBlank { null } ?: trimmed
}

internal fun sameHopHost(a: String?, b: String?): Boolean {
    val left = hopHost(a) ?: return false
    val right = hopHost(b) ?: return false
    return left.equals(right, ignoreCase = true)
}

internal fun provisionUrlForHost(host: String?): String? {
    val h = hopHost(host) ?: return null
    return "http://$h:9100"
}
