package com.ardtt.app.tunnel

/**
 * Holds the last session config for [com.ardtt.app.core.VpnTunnelService]
 * (extras alone are too small for full profile JSON).
 */
object TunnelSessionHolder {
    @Volatile
    var config: TunnelSessionConfig? = null
}
