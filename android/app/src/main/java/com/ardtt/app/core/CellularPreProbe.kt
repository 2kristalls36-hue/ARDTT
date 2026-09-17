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
 * Whether a whitelist measurement taken on [origin] may confirm БС on [live].
 * Rebinding a handle does not upgrade an unknown operator into a known one.
 * [sameCarrier] / [NetworkKey.sameCellularSim] stay as-is for Direct-negative
 * and already-confirmed Direct.
 */
fun whitelistOriginAllowsBind(origin: NetworkKey?, live: NetworkKey?): Boolean {
    if (origin == null || live == null) return false
    if (!origin.isCellular || !live.isCellular) return false
    val originCarrier = origin.carrier?.takeIf { it.isNotBlank() }
    val liveCarrier = live.carrier?.takeIf { it.isNotBlank() }
    if (origin.samePhysicalNetwork(live)) {
        if (originCarrier != null && liveCarrier != null && originCarrier != liveCarrier) {
            return false
        }
        return true
    }
    if (originCarrier == null || liveCarrier == null || originCarrier != liveCarrier) {
        return false
    }
    return origin.sameCellularSim(live)
}

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
 * Wi‑Fi drops). The measurement origin is immutable: a rewrite of the bind
 * key never becomes a stronger confirmation than the origin allowed.
 */
fun adoptCellularEvidence(
    stash: ReachabilityEvidence?,
    liveKey: NetworkKey?,
): ReachabilityEvidence? {
    if (stash == null) return null
    val origin = stash.measurementOrigin()
    val stashKey = stash.networkKey
    if (stashKey != null && !stashKey.isCellular) return null
    if (origin != null && !origin.isCellular) return null
    if (liveKey != null && !liveKey.isCellular) return null
    val preserved = if (stash.originNetworkKey != null) stash else {
        stash.copy(originNetworkKey = origin)
    }
    if (liveKey == null) return preserved
    if (whitelistOriginAllowsBind(origin, liveKey)) {
        return if (stashKey?.samePhysicalNetwork(liveKey) == true &&
            stashKey.carrier == liveKey.carrier
        ) {
            preserved
        } else {
            preserved.copy(networkKey = liveKey)
        }
    }
    return null
}

fun whitelistEvidenceForUnderlay(
    evidence: ReachabilityEvidence?,
    cellularEvidence: ReachabilityEvidence?,
    key: NetworkKey?,
    profileId: String?,
    nowElapsedMs: Long? = null,
): ReachabilityEvidence? {
    if (key?.isCellular == true) {
        val adopted = adoptCellularEvidence(cellularEvidence, key)
        val now = nowElapsedMs ?: adopted?.measuredAtElapsedMs ?: 0L
        if (hasSameNetworkProbeEvidence(adopted, key, profileId, now)) return adopted
    }
    val now = nowElapsedMs ?: evidence?.measuredAtElapsedMs ?: 0L
    if (hasSameNetworkProbeEvidence(evidence, key, profileId, now)) return evidence
    return null
}
