package com.ardtt.app.ui.profiles

import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.admin.formatClientBytes

internal data class ProfileLiveFacts(
    val usedBytes: Long,
    val trafficLimitBytes: Long,
    val expiresAt: Long,
)

internal fun profileCardFacts(
    profile: VpnProfile,
    live: ProfileLiveFacts?,
): ProfileLiveFacts = ProfileLiveFacts(
    usedBytes = live?.usedBytes ?: profile.usedBytes,
    trafficLimitBytes = live?.trafficLimitBytes ?: profile.trafficLimitBytes,
    expiresAt = live?.expiresAt ?: profile.expiresAt,
)

internal fun profileTrafficRemainingLabel(limitBytes: Long, usedBytes: Long): String {
    if (limitBytes <= 0L) return "без лимита"
    val left = (limitBytes - usedBytes).coerceAtLeast(0L)
    return "осталось ${formatClientBytes(left)}"
}

internal fun profileLiveFactsFromUsers(
    profileName: String,
    users: List<ProvisionAdminApi.UserSummary>,
): ProfileLiveFacts? {
    val user = users.find { it.name == profileName } ?: return null
    return ProfileLiveFacts(
        usedBytes = user.usedBytes,
        trafficLimitBytes = user.trafficLimitBytes,
        expiresAt = user.expiresAt,
    )
}

internal enum class ProfileCardSlot {
    Title,
    ExpiresBadge,
    Overflow,
    Hosts,
    Presence,
    Traffic,
}

internal enum class ProfileCardOverflowAnchor {
    AfterExpires,
    TrailingOutside,
}

/** Expiry chrome: OS badge (`Surface` + FillSoft), not `ArdttStatusChip` accent fill. */
internal enum class ProfileCardBadgeKind {
    StatusChip,
    OsSurface,
}

/** Active profile must keep the default compact contour, like a server card. */
internal enum class ProfileCardSelectionChrome {
    ConnectedBorder,
    DefaultContour,
}

/** Title stays onSurface; presence already marks the active profile. */
internal enum class ProfileCardTitleTone {
    ConnectedWhenActive,
    OnSurface,
}

/** Host chips sit in an inner weighted row so they do not stretch toward «Активен». */
internal enum class ProfileCardHostLayout {
    WeightedHostRow,
    InnerWeightedRow,
}

/**
 * Same identity order as the server list card: title → badge,
 * trailing ⋮ outside the column like the chevron, hosts left / presence right,
 * remaining traffic on the fact row.
 */
internal fun profileCardSlotOrder(): List<ProfileCardSlot> = listOf(
    ProfileCardSlot.Title,
    ProfileCardSlot.ExpiresBadge,
    ProfileCardSlot.Overflow,
    ProfileCardSlot.Hosts,
    ProfileCardSlot.Presence,
    ProfileCardSlot.Traffic,
)

internal fun profileCardOverflowAnchor(): ProfileCardOverflowAnchor =
    ProfileCardOverflowAnchor.TrailingOutside

/** Same 24 dp glyph box as the servers-list chevron, not a 48 dp IconButton. */
internal fun profileCardOverflowUsesCompactIcon(): Boolean = true

internal fun profileCardBadgeKind(): ProfileCardBadgeKind = ProfileCardBadgeKind.OsSurface

internal fun profileCardSelectionChrome(): ProfileCardSelectionChrome =
    ProfileCardSelectionChrome.DefaultContour

internal fun profileCardTitleTone(): ProfileCardTitleTone = ProfileCardTitleTone.OnSurface

internal fun profileCardHostLayout(): ProfileCardHostLayout =
    ProfileCardHostLayout.InnerWeightedRow

/** Profiles use the same top inset as Settings: chrome padding only. */
internal fun profileFeedAddsTopSpacing(): Boolean = false

internal fun profileCardPresenceLabel(active: Boolean): String? =
    if (active) "● Активен" else null

/** Public hops for the address row: cascade `entry → exit`, else one host, ports stripped. */
internal fun profileCardAddressHosts(
    profile: VpnProfile,
    servers: List<DeployTarget>,
): List<String> {
    val direct = NetworkEndpoint.hostOf(profile.direct.endpoint)?.trim()?.ifBlank { null }
    val bypass = NetworkEndpoint.hostOf(profile.bypass.peer)?.trim()?.ifBlank { null }
    val entry = direct ?: bypass ?: return emptyList()
    val server = DeployHop.matchingServer(servers, entry)
    if (server?.cascadeEnabled == true) {
        val shownEntry = (
            DeployHop.host(server.publicHost)
                ?: server.publicHost.trim().ifBlank { null }
                ?: DeployHop.host(server.host)
                ?: server.host.trim().ifBlank { null }
                ?: entry
            )
        val exit = DeployHop.host(server.cascadeHost)?.takeIf { it.isNotBlank() }
        if (exit != null && !shownEntry.equals(exit, ignoreCase = true)) {
            return listOf(shownEntry, exit)
        }
    }
    if (direct != null && bypass != null && !direct.equals(bypass, ignoreCase = true)) {
        return listOf(direct, bypass)
    }
    return listOf(entry)
}
