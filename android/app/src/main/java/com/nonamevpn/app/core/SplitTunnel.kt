package com.nonamevpn.app.core

/**
 * Resolves Android VpnService split-tunnel lists.
 *
 * VpnService cannot mix [android.net.VpnService.Builder.addAllowedApplication]
 * and [android.net.VpnService.Builder.addDisallowedApplication]:
 *
 * - ЧС (blacklist): disallowed = selected ∪ self
 * - БС (whitelist) with apps: allowed = selected ∪ self
 * - Empty БС: full tunnel (same as empty ЧС) — otherwise only this app
 *   would enter the TUN and the session looks connected with no internet.
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
        // Empty БС would only include this app → “connected” with no user traffic
        // (0.09 MB keepalives). Fall back to full tunnel like empty ЧС / 0.5.83.
        return if (whitelistMode && selected.isNotEmpty()) {
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
