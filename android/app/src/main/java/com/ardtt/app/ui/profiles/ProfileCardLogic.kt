package com.ardtt.app.ui.profiles

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.admin.trafficUsageProgress
import java.util.Locale
import kotlin.math.roundToInt

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

/**
 * Left side of the traffic row: used out of the limit in one unit,
 * for example `2,2/100 ГБ`. No limit stays `без лимита`.
 */
internal fun profileTrafficUsageLabel(limitBytes: Long, usedBytes: Long): String {
    if (limitBytes <= 0L) return "без лимита"
    val limit = limitBytes.toDouble()
    val used = usedBytes.coerceAtLeast(0L).toDouble()
    val (divisor, unit) = when {
        limit < TRAFFIC_KB -> 1.0 to "Б"
        limit < TRAFFIC_MB -> TRAFFIC_KB to "КБ"
        limit < TRAFFIC_GB -> TRAFFIC_MB to "МБ"
        else -> TRAFFIC_GB to "ГБ"
    }
    return "${formatTrafficAmount(used / divisor)}/${formatTrafficAmount(limit / divisor)} $unit"
}

/** Right side of the traffic row. Missing when the profile has no limit. */
internal fun profileTrafficPercentLabel(limitBytes: Long, usedBytes: Long): String? {
    if (limitBytes <= 0L) return null
    val percent = (trafficUsageProgress(usedBytes, limitBytes) * 100f).roundToInt().coerceIn(0, 100)
    return "$percent%"
}

private const val TRAFFIC_KB = 1024.0
private const val TRAFFIC_MB = 1024.0 * 1024.0
private const val TRAFFIC_GB = 1024.0 * 1024.0 * 1024.0

private fun formatTrafficAmount(value: Double): String {
    val text = String.format(Locale("ru"), "%.1f", value)
    return when {
        text.endsWith(",0") -> text.dropLast(2)
        text.endsWith(".0") -> text.dropLast(2)
        else -> text
    }
}

/**
 * Where the usage bar sits on a profile card. Clients add it as an extra row;
 * profiles draw the same bar in the card's existing bottom padding so the
 * card does not grow.
 */
internal enum class ProfileTrafficBarPlacement {
    ExtraRow,
    InsideBottomPadding,
}

internal fun profileTrafficBarPlacement(): ProfileTrafficBarPlacement =
    ProfileTrafficBarPlacement.InsideBottomPadding

internal fun profileTrafficBarVisible(limitBytes: Long): Boolean = limitBytes > 0L

/**
 * Shift of the bar below the last text row. Equals the bar height when the
 * bottom padding can hold it, so the bar leaves the text and stays inside
 * the card.
 */
internal fun profileTrafficBarDrop(bottomPadding: Dp, barHeight: Dp): Dp {
    if (barHeight <= 0.dp || bottomPadding < barHeight) return 0.dp
    return barHeight
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
 * used/limit on the left of the fact row and the percent on the right.
 * A traffic limit draws the usage bar in the card's bottom padding, not as
 * another row.
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
