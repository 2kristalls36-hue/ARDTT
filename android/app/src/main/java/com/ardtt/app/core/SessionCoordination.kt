package com.ardtt.app.core

/**
 * Race-safe handover / connect primitives. Volatile flags alone do not own
 * overlapping Jobs; callers must pair these with a generation token.
 */

data class PendingHandoverEvent(
    val reason: String,
    val underlayChanged: Boolean,
    val previousNetworkId: Long?,
    val currentNetworkId: Long? = null,
    val subscriptionId: Int = -1,
    val evidenceSinceMs: Long,
    val generation: Long,
    val rebuildTun: Boolean = false,
)

/** Latest event wins identity; underlay-change / rebuild flags are sticky ORs. */
fun mergePendingHandover(
    previous: PendingHandoverEvent?,
    incoming: PendingHandoverEvent,
): PendingHandoverEvent {
    if (previous == null) return incoming
    return incoming.copy(
        underlayChanged = previous.underlayChanged || incoming.underlayChanged,
        previousNetworkId = incoming.previousNetworkId ?: previous.previousNetworkId,
        currentNetworkId = incoming.currentNetworkId ?: previous.currentNetworkId,
        subscriptionId = if (incoming.subscriptionId >= 0) {
            incoming.subscriptionId
        } else {
            previous.subscriptionId
        },
        evidenceSinceMs = maxOf(previous.evidenceSinceMs, incoming.evidenceSinceMs),
        rebuildTun = previous.rebuildTun || incoming.rebuildTun,
        generation = incoming.generation,
    )
}

fun ownsJobEpoch(jobEpoch: Long, currentEpoch: Long): Boolean = jobEpoch == currentEpoch

fun shouldClearSoftRestartFlag(
    handedOff: Boolean,
    jobEpoch: Long,
    currentEpoch: Long,
): Boolean = !handedOff && ownsJobEpoch(jobEpoch, currentEpoch)

/**
 * Apply a probe snapshot only if mode and path are still the ones that
 * started the probe. A newer manual Direct/Bypass must win.
 */
fun shouldApplyHandoverProbe(
    snapshotMode: ConnPathMode,
    liveMode: ConnPathMode,
    snapshotPath: VpnPath,
    livePath: VpnPath,
): Boolean = snapshotMode == liveMode && snapshotPath == livePath

/** After [probeJob] is cancelled, [Job.join] still completes — do not Connect. */
fun shouldConnectAfterProbeJoin(state: ConnState): Boolean = when (state) {
    ConnState.Idle, ConnState.Ready, ConnState.Probing, ConnState.Error -> true
    ConnState.Connecting,
    ConnState.Connected,
    ConnState.PausedTrustedWifi,
    ConnState.Disconnecting,
    -> false
}

fun isValidatedUnderlay(
    hasInternet: Boolean,
    notVpn: Boolean,
    validated: Boolean,
): Boolean = hasInternet && notVpn && validated

/**
 * Leaving trusted Wi‑Fi onto cellular Auto must re-probe: the saved Direct
 * path is from home Wi‑Fi, not from the new underlay.
 */
fun trustedWifiResumeNeedsProbe(mode: ConnPathMode, underlayKind: UnderlayKind): Boolean =
    mode == ConnPathMode.Auto && !autoUsesDirectOnWifi(mode, underlayKind)

fun resolveTrustedWifiResumePath(
    mode: ConnPathMode,
    savedPath: VpnPath,
    probePath: VpnPath?,
    hasCallHash: Boolean,
    underlayKind: UnderlayKind,
): VpnPath = resolveLiveSwitchPath(
    mode = mode,
    currentPath = savedPath,
    probePath = probePath,
    hasCallHash = hasCallHash,
    underlayKind = underlayKind,
) ?: savedPath
