package com.ardtt.app.core

/** How to reach provision `/v1/hide-ip` from the current VPN state. */
enum class HideIpDispatch {
    /** TUN is up and underlay cannot reach :9100 — bind the default (VPN) route. */
    ViaVpn,
    /** Open internet — try underlay first (HideIpApi may fall back to default route). */
    Underlay,
    /** Need the tunnel, but it is not Connected yet — retry after [ConnectionManager.onTunnelRunning]. */
    QueueUntilTunnel,
}

/**
 * Bypass / operator-whitelist cannot POST to provision :9100 on the underlay.
 * Sending `viaVpn=true` while still Idle/Ready/Connecting makes Hide-IP fail
 * instead of waiting for the TUN.
 */
fun decideHideIpDispatch(
    state: ConnState,
    provisionOnlyViaVpn: Boolean,
): HideIpDispatch {
    val tunnelReady = state == ConnState.Connected
    return when {
        provisionOnlyViaVpn && !tunnelReady -> HideIpDispatch.QueueUntilTunnel
        provisionOnlyViaVpn && tunnelReady -> HideIpDispatch.ViaVpn
        else -> HideIpDispatch.Underlay
    }
}

/** Bypass backend is up but TURN workers (or the Connecting handshake) are not. */
fun isBypassWarming(state: ConnState, path: VpnPath?): Boolean =
    state == ConnState.Connecting && path == VpnPath.Bypass

/** Early releases flipped VPS↔Cloudflare with ip rules only. Restarting the TUN dropped calls. */
fun hideIpShouldRestartTransport(): Boolean = false

/** Failed Hide-IP POST (on or off) must be retried after the TUN is up. */
fun hideIpShouldRetryAfterTunnel(lastSent: Boolean?, want: Boolean, pending: Boolean): Boolean =
    pending || lastSent != want

/** Cap launcher-icon decode size so the Bypass tab cannot OOM on dense icon packs. */
fun appIconDecodeSize(
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    maxPx: Int = 96,
): Pair<Int, Int> {
    val w = if (intrinsicWidth > 0) intrinsicWidth else maxPx
    val h = if (intrinsicHeight > 0) intrinsicHeight else maxPx
    val longest = maxOf(w, h)
    if (longest <= maxPx) return w to h
    val scale = maxPx.toFloat() / longest.toFloat()
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}
