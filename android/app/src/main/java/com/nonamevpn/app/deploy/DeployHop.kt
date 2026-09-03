package com.nonamevpn.app.deploy

import com.nonamevpn.app.profile.NetworkEndpoint

/** Public hosts / provision URLs for entry vs last-hop (cascade exit) VPS. */
object DeployHop {
    fun host(raw: String?): String? {
        val trimmed = raw?.trim()?.ifBlank { null } ?: return null
        return NetworkEndpoint.hostOf(trimmed)?.ifBlank { null } ?: trimmed
    }

    fun same(a: String?, b: String?): Boolean {
        val left = host(a) ?: return false
        val right = host(b) ?: return false
        return left.equals(right, ignoreCase = true)
    }

    /**
     * Exact profile-host match only — do not fall back to the latest deploy,
     * or a cascade hop from another server would leak into the map / last-IP probe.
     *
     * Public host and SSH host are considered together. An older card that only
     * matches [DeployTarget.publicHost] must not hide a newer cascade card that
     * matches [DeployTarget.host] for the same profile endpoint.
     */
    fun matchingServer(servers: List<DeployTarget>, profileHost: String?): DeployTarget? {
        val host = host(profileHost) ?: return null
        val matches = servers.filter { server ->
            same(server.publicHost, host) || same(server.host, host)
        }
        return matches.maxByOrNull { it.lastDeployedAtMs }
    }

    fun provisionUrl(host: String?): String? {
        val h = host(host) ?: return null
        return "http://$h:9100"
    }

    fun exitProvisionUrl(server: DeployTarget?): String? {
        if (server?.cascadeEnabled != true) return null
        val exit = host(server.cascadeHost) ?: return null
        val entry = host(server.publicHost) ?: host(server.host)
        if (entry != null && same(entry, exit)) return null
        return provisionUrl(exit)
    }

    /** Last hop first (cascade exit), then entry. Tunnel tab uses this for the public egress IP. */
    fun lastHopProvisionUrls(entryProvision: String?, exitProvision: String?): List<String> {
        val urls = linkedSetOf<String>()
        exitProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
        entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
        return urls.toList()
    }
}
