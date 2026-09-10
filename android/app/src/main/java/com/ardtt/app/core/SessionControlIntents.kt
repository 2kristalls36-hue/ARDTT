package com.ardtt.app.core

import android.content.Intent

data class GoNetworkTarget(
    val handle: Long,
    val kind: String,
    val scope: String,
)

/**
 * RAW sockets stay on cellular when Direct has moved to Wi-Fi.
 * A handle-only SESSION_CONTROL must not be treated as ALLOW.
 */
fun goBypassNetworkTarget(
    activePath: VpnPath?,
    transport: TransportLifecycle,
    parkedRawAlive: Boolean,
    underlayHandle: Long?,
    underlayKind: UnderlayKind,
    cellularHandle: Long?,
): GoNetworkTarget? {
    val bypassLive = activePath == VpnPath.Bypass &&
        transport != TransportLifecycle.Stopped &&
        transport != TransportLifecycle.Failed
    val parked = parkedRawAlive && activePath != VpnPath.Bypass
    if (!bypassLive && !parked) return null
    val handle: Long
    val kind: String
    if (parked) {
        handle = cellularHandle ?: return null
        kind = UnderlayKind.Cellular.name
    } else if (cellularHandle != null &&
        (underlayKind == UnderlayKind.Wifi || underlayKind == UnderlayKind.Ethernet)
    ) {
        handle = cellularHandle
        kind = UnderlayKind.Cellular.name
    } else {
        handle = underlayHandle ?: return null
        kind = underlayKind.name
    }
    val scope = if (parked) {
        VpnTunnelService.NETWORK_SCOPE_PARKED
    } else {
        VpnTunnelService.NETWORK_SCOPE_ACTIVE
    }
    return GoNetworkTarget(handle, kind, scope)
}

/** Absent extra must not be treated as ALLOW. */
fun sessionControlNetOpsDelta(hasNetOpsExtra: Boolean, allowed: Boolean): Boolean? {
    if (!hasNetOpsExtra) return null
    return allowed
}

fun sessionControlShouldDiscardParked(hasDiscardExtra: Boolean, discard: Boolean): Boolean =
    hasDiscardExtra && discard

fun sessionControlShouldApplyDiscard(
    hasDiscardExtra: Boolean,
    discard: Boolean,
    extraCallEpoch: Long?,
    liveCallEpoch: Long,
): Boolean {
    if (!hasDiscardExtra || !discard) return false
    if (extraCallEpoch == null) return true
    return extraCallEpoch == liveCallEpoch
}

fun sessionControlNetOpsDelta(intent: Intent): Boolean? =
    sessionControlNetOpsDelta(
        intent.hasExtra(VpnTunnelService.EXTRA_NET_OPS_ALLOWED),
        intent.getBooleanExtra(VpnTunnelService.EXTRA_NET_OPS_ALLOWED, false),
    )
