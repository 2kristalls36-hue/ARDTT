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
    if (!sameCarrier(other)) return false
    val a = simId ?: return false
    val b = other.simId ?: return false
    return a == b
}

fun NetworkKey.matchesCellularUnderlay(other: NetworkKey?): Boolean =
    sameCarrier(other) && (samePhysicalNetwork(other) || sameCellularSim(other))

/**
 * Whether a Direct UDP failure recorded on this key must be forgotten.
 *
 * LTE mobility hands out a new [NetworkKey.handle] on almost every BS/RAN
 * change. That is a new Android Network, not a new radio/SIM/operator, so
 * Auto must not treat it as a fresh underlay and immediately retry Direct.
 * Wi‑Fi / Ethernet stay handle-scoped. A missing live key is a gap, not a
 * new radio.
 */
fun NetworkKey?.directFailureScopeChanged(next: NetworkKey?): Boolean {
    if (this == null && next == null) return false
    if (this == null || next == null) return true
    if (isCellular && next.isCellular) {
        return !matchesCellularUnderlay(next)
    }
    return !samePhysicalNetwork(next)
}

/**
 * Dead-Direct latch used by handover: stay blocked across same-SIM LTE
 * handle flaps. A missing live key still counts as blocked (no bind).
 */
fun deadDirectBlocksLiveUnderlay(
    blockUntilUnderlayChange: Boolean,
    deadKey: NetworkKey?,
    liveKey: NetworkKey?,
): Boolean {
    if (!blockUntilUnderlayChange) return false
    if (liveKey == null || deadKey == null) return true
    return !deadKey.directFailureScopeChanged(liveKey)
}

/** Direct proven on this radio / SIM / operator; unknown keys keep the old Stay behaviour. */
fun NetworkKey?.directConfirmedOn(current: NetworkKey?): Boolean {
    if (this == null || current == null) return true
    if (!sameCarrier(current)) return false
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
        // Rebind when the operator was unknown at measurement time, so later
        // comparisons are scoped to the PLMN we now know we are on.
        val exact = stashKey?.samePhysicalNetwork(liveKey) == true &&
            stashKey.carrier == liveKey.carrier
        return if (exact) stash else stash.copy(networkKey = liveKey)
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
