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
     * Public host and SSH host are considered together. If several cards match
     * the profile, a cascade entry wins over a plain VPS card so VPS 2 is not
     * dropped when an older publicHost-only row is still in the list.
     */
    fun matchingServer(servers: List<DeployTarget>, profileHost: String?): DeployTarget? {
        val host = host(profileHost) ?: return null
        val matches = servers.filter { server ->
            same(server.publicHost, host) || same(server.host, host)
        }
        if (matches.isEmpty()) return null
        val cascade = matches.filter { isCascadeEntry(it, host) }
        return (cascade.ifEmpty { matches }).maxByOrNull { it.lastDeployedAtMs }
    }

    fun isCascadeEntry(server: DeployTarget, profileHost: String?): Boolean {
        if (!server.cascadeEnabled) return false
        val exit = host(server.cascadeHost) ?: return false
        val entry = host(profileHost) ?: host(server.publicHost) ?: host(server.host)
        return entry == null || !same(entry, exit)
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

    /**
     * Provision of the last hop only.
     * Cascade: exit VPS, never the entry. Single hop: that VPS.
     */
    fun lastHopProvisionUrl(
        entryProvision: String?,
        exitProvision: String?,
        cascade: Boolean = false,
    ): String? {
        fun clean(raw: String?) = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        val exit = clean(exitProvision)
        if (exit != null) return exit
        if (cascade) return null
        return clean(entryProvision)
    }
}
