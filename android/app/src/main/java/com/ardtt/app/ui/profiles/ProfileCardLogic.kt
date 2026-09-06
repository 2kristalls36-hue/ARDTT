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
