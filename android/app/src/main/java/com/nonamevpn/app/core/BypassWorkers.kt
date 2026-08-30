package com.nonamevpn.app.core

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
): Boolean {
    if (!existingValid) return false
    val wantIp = ip.substringBefore('/')
    val wantMtu = mtu.coerceIn(576, 1500)
    return lastIp == wantIp && lastDns == dns && lastMtu == wantMtu
}

/** Keep the Bypass TUN only when already on Path B. Direct→Bypass must drop TUN so libclient can dial. */
fun shouldReuseBypassTunOnSoftRestart(
    softRestart: Boolean,
    pathIsBypass: Boolean,
    currentBackendIsBypass: Boolean,
    tunValid: Boolean,
): Boolean = softRestart && pathIsBypass && currentBackendIsBypass && tunValid
