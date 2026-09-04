package com.ardtt.app.core

/**
 * Resolves Android VpnService split-tunnel lists the way qWDTT
 * [RawTunVpnService.applyAppFilters] does.
 *
 * VpnService cannot mix [android.net.VpnService.Builder.addAllowedApplication]
 * and [android.net.VpnService.Builder.addDisallowedApplication].
 *
 * Transport packages (this app, VK, VK Calls) always stay on the underlay so
 * TURN can dial. Empty БС is fail-open: only transport is excluded, same as
 * empty ЧС. БС with apps allows those packages only — it does not add self.
 */
data class SplitTunnelPlan(
    val whitelistMode: Boolean,
    val allowed: Set<String>,
    val disallowed: Set<String>,
)

object SplitTunnel {
    val VK_TRANSPORT_PACKAGES: Set<String> = setOf(
        "com.vkontakte.android",
        "com.vk.calls",
    )

    fun transportPackages(selfPackage: String): Set<String> {
        val self = selfPackage.trim()
        return if (self.isBlank()) VK_TRANSPORT_PACKAGES else setOf(self) + VK_TRANSPORT_PACKAGES
    }

    fun resolve(
        whitelistMode: Boolean,
        selectedApps: Set<String>,
        selfPackage: String,
    ): SplitTunnelPlan {
        val self = selfPackage.trim()
        val transport = transportPackages(self)
        val selected = sanitizePackages(selectedApps, transport)
        return if (whitelistMode && selected.isNotEmpty()) {
            SplitTunnelPlan(
                whitelistMode = true,
                allowed = selected,
                disallowed = emptySet(),
            )
        } else {
            SplitTunnelPlan(
                whitelistMode = false,
                allowed = emptySet(),
                disallowed = transport + selected,
            )
        }
    }

    fun browserPackages(selected: Set<String>): List<String> =
        selected.map { it.trim() }.filter { it.isNotBlank() && isLikelyBrowser(it) }.sorted()

    fun logSample(selected: Set<String>, limit: Int = 8): String {
        val sorted = selected.map { it.trim() }.filter { it.isNotBlank() }.sorted()
        val sample = sorted.take(limit).joinToString(",")
        val extra = (sorted.size - limit).coerceAtLeast(0)
        val browsers = browserPackages(selected.toSet())
        val browserPart = if (browsers.isEmpty()) {
            "browsers=0"
        } else {
            "browsers=${browsers.joinToString(",")}"
        }
        val samplePart = if (extra > 0) "sample=$sample +$extra" else "sample=$sample"
        return "$samplePart $browserPart"
    }

    /** Stable TUN key so a БС/ЧС toggle cannot reuse a filter built for another plan. */
    fun tunFilterFingerprint(
        whitelistMode: Boolean,
        selectedApps: Set<String>,
        excludedHosts: Set<String>,
        selfPackage: String,
    ): String {
        val plan = resolve(whitelistMode, selectedApps, selfPackage)
        val allowed = plan.allowed.sorted().joinToString(",")
        val disallowed = plan.disallowed.sorted().joinToString(",")
        val hosts = excludedHosts
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString(",")
        return "wl=${plan.whitelistMode};allow=$allowed;deny=$disallowed;hosts=$hosts"
    }

    internal fun isLikelyBrowser(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("chrome") ||
            p.contains("browser") ||
            p.contains("firefox") ||
            p.contains("opera") ||
            p.contains("brave") ||
            p.contains("edge") ||
            p.contains("duckduckgo") ||
            p.contains("samsung.android.sbrowser") ||
            p.contains("yandex.search") ||
            p.contains("ucmobile") ||
            p.contains("vivaldi")
    }

    private fun sanitizePackages(packages: Set<String>, transport: Set<String>): Set<String> =
        packages
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in transport }
            .toSet()
}
