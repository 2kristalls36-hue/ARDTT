package com.nonamevpn.app.core

/**
 * Resolves Android VpnService split-tunnel lists.
 *
 * VpnService cannot mix [android.net.VpnService.Builder.addAllowedApplication]
 * and [android.net.VpnService.Builder.addDisallowedApplication]. WARP-marked
 * apps are always forced into the tunnel:
 *
 * - ЧС (blacklist): disallowed = selected − warp (self stays disallowed)
 * - БС (whitelist): allowed = selected ∪ warp ∪ self
 *
 * Hide-IP/WARP itself is a VPS-wide egress policy: every tunneled app shares
 * the same exit IP. Per-app WARP only chooses who is inside the TUN.
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
        warpApps: Set<String>,
        selfPackage: String,
    ): SplitTunnelPlan {
        val self = selfPackage.trim()
        val selected = sanitizePackages(selectedApps, self)
        val warp = sanitizePackages(warpApps, self)
        return if (whitelistMode) {
            SplitTunnelPlan(
                whitelistMode = true,
                allowed = selected + warp + self,
                disallowed = emptySet(),
            )
        } else {
            SplitTunnelPlan(
                whitelistMode = false,
                allowed = emptySet(),
                disallowed = (selected - warp) + self,
            )
        }
    }

    private fun sanitizePackages(packages: Set<String>, selfPackage: String): Set<String> =
        packages
            .map { it.trim() }
            .filter { it.isNotBlank() && it != selfPackage }
            .toSet()
}
