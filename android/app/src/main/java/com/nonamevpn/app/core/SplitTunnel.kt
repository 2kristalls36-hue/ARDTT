package com.nonamevpn.app.core

/**
 * Resolves Android VpnService split-tunnel lists.
 *
 * VpnService cannot mix [android.net.VpnService.Builder.addAllowedApplication]
 * and [android.net.VpnService.Builder.addDisallowedApplication]:
 *
 * - ЧС (blacklist): disallowed = selected ∪ self
 * - БС (whitelist): allowed = selected ∪ self
 */
data class SplitTunnelPlan(
    val whitelistMode: Boolean,
    val allowed: Set<String>,
    val disallowed: Set<String>,
)

object SplitTunnel {
    fun resolve(
        whitelistMode: Boolean,
        selectedApps: Set<String>,
        selfPackage: String,
    ): SplitTunnelPlan {
        val self = selfPackage.trim()
        val selected = sanitizePackages(selectedApps, self)
        return if (whitelistMode) {
            SplitTunnelPlan(
                whitelistMode = true,
                allowed = selected + self,
                disallowed = emptySet(),
            )
        } else {
            SplitTunnelPlan(
                whitelistMode = false,
                allowed = emptySet(),
                disallowed = selected + self,
            )
        }
    }

    private fun sanitizePackages(packages: Set<String>, selfPackage: String): Set<String> =
        packages
            .map { it.trim() }
            .filter { it.isNotBlank() && it != selfPackage }
            .toSet()
}
