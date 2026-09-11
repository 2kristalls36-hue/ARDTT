package com.ardtt.app.core

/**
 * Pre-warm operator-whitelist (БС) scoring on the cellular underlay while
 * Wi‑Fi / Ethernet is the default route.
 *
 * Wi‑Fi probe samples never enter [ConnectionSnapshot.cellularEvidence].
 * A cellular probe binds sockets to the cellular [android.net.Network]
 * (NOT_VPN) so traffic does not use Wi‑Fi or the VPN tunnel.
 */
fun NetworkKey.sameCellularSim(other: NetworkKey?): Boolean {
    if (other == null) return false
    if (transport != UnderlayKind.Cellular || other.transport != UnderlayKind.Cellular) {
        return false
    }
    val a = simId ?: return false
    val b = other.simId ?: return false
    return a == b
}

fun NetworkKey.matchesCellularUnderlay(other: NetworkKey?): Boolean =
    samePhysicalNetwork(other) || sameCellularSim(other)

/** Direct proven on this radio / SIM; unknown keys keep the old Stay behaviour. */
fun NetworkKey?.directConfirmedOn(current: NetworkKey?): Boolean {
    if (this == null || current == null) return true
    return samePhysicalNetwork(current) || sameCellularSim(current)
}

fun shouldPreProbeCellular(
    mode: ConnPathMode,
    effectiveKind: UnderlayKind,
): Boolean = mode == ConnPathMode.Auto &&
    effectiveKind.prefersDirectInAuto() &&
    WhitelistDetection.appliesTo(UnderlayKind.Cellular)

/**
 * Rebind a cellular stash onto the live LTE key (handle may change after
 * Wi‑Fi drops). Different SIM → no match.
 */
fun adoptCellularEvidence(
    stash: ReachabilityEvidence?,
    liveKey: NetworkKey?,
): ReachabilityEvidence? {
    if (stash == null) return null
    val stashKey = stash.networkKey
    if (stashKey != null && !stashKey.isCellular) return null
    if (liveKey != null && !liveKey.isCellular) return null
    if (liveKey == null) return stash
    if (stashKey == null || stashKey.matchesCellularUnderlay(liveKey)) {
        return if (stashKey?.samePhysicalNetwork(liveKey) == true) {
            stash
        } else {
            stash.copy(networkKey = liveKey)
        }
    }
    return null
}

fun whitelistEvidenceForUnderlay(
    evidence: ReachabilityEvidence?,
    cellularEvidence: ReachabilityEvidence?,
    key: NetworkKey?,
    profileId: String?,
): ReachabilityEvidence? {
    if (key?.isCellular == true) {
        val adopted = adoptCellularEvidence(cellularEvidence, key)
        if (hasSameNetworkProbeEvidence(adopted, key, profileId)) return adopted
    }
    if (hasSameNetworkProbeEvidence(evidence, key, profileId)) return evidence
    return null
}
