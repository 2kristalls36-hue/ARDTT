package com.ardtt.app.deploy

import com.ardtt.app.profile.NetworkEndpoint

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
     * Public host and SSH host are considered together. Newest card wins.
     */
    fun matchingServer(servers: List<DeployTarget>, profileHost: String?): DeployTarget? {
        val host = host(profileHost) ?: return null
        return servers
            .filter { same(it.publicHost, host) || same(it.host, host) }
            .maxByOrNull { it.lastDeployedAtMs }
    }

    fun isCascadeEntry(server: DeployTarget, profileHost: String?): Boolean {
        if (!server.cascadeEnabled) return false
        val exit = host(server.cascadeHost) ?: return false
        val entry = host(profileHost) ?: host(server.publicHost) ?: host(server.host)
        return entry == null || !same(entry, exit)
    }

    fun provisionUrl(host: String?, port: Int = 9100): String? {
        val h = host(host) ?: return null
        val p = if (port in 1..65535) port else 9100
        return "http://$h:$p"
    }

    fun exitProvisionUrl(server: DeployTarget?): String? {
        if (server?.cascadeEnabled != true) return null
        val exit = host(server.cascadeHost) ?: return null
        val entry = host(server.publicHost) ?: host(server.host)
        if (entry != null && same(entry, exit)) return null
        return provisionUrl(exit, server.cascadeProvisionPort)
    }

    /** Last hop first (cascade exit), then entry. Tunnel tab uses this for the public egress IP. */
    fun lastHopProvisionUrls(entryProvision: String?, exitProvision: String?): List<String> {
        val urls = linkedSetOf<String>()
        exitProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
        entryProvision?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { urls += it }
        return urls.toList()
    }
}
