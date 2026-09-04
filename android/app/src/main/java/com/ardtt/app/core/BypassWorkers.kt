package com.ardtt.app.core

/** Path B TURN workers. qWDTT anonymous default is 9 (one VK group). */
object BypassWorkers {
    const val DEFAULT = 9
    const val MIN = 1
    const val MAX = 9
}

fun canReuseBypassTun(
    existingValid: Boolean,
    lastIp: String?,
    lastDns: String?,
    lastMtu: Int,
    ip: String,
    dns: String,
    mtu: Int,
    lastFilterFingerprint: String? = null,
    filterFingerprint: String? = null,
): Boolean {
    if (!existingValid) return false
    val wantIp = ip.substringBefore('/')
    val wantMtu = mtu.coerceIn(576, 1500)
    if (lastIp != wantIp || lastDns != dns || lastMtu != wantMtu) return false
    if (filterFingerprint != null && lastFilterFingerprint != filterFingerprint) return false
    return true
}

/**
 * Keep the Bypass TUN only when already on Path B **and** the underlay did not
 * move. Direct→Bypass must drop TUN so libclient can dial. SIM swap / Wi‑Fi↔LTE
 * must drop TUN too: Android binds VpnService to the network that [establish]
 * saw. Reusing fd=215 after MTS→T-Mobile left apps on a dead underlay while
 * TURN workers rebound on the new SIM.
 */
fun shouldReuseBypassTunOnSoftRestart(
    softRestart: Boolean,
    pathIsBypass: Boolean,
    currentBackendIsBypass: Boolean,
    tunValid: Boolean,
    underlayChanged: Boolean = false,
): Boolean =
    softRestart &&
        pathIsBypass &&
        currentBackendIsBypass &&
        tunValid &&
        !underlayChanged

/** True when the TUN was built on a different Wi‑Fi/SIM than the one now up. */
fun tunUnderlayChanged(
    lastIdentity: String?,
    currentIdentity: String,
): Boolean {
    val last = lastIdentity?.takeIf { it.isNotBlank() && it != "none" } ?: return false
    val current = currentIdentity.takeIf { it.isNotBlank() && it != "none" } ?: return false
    return last != current
}
