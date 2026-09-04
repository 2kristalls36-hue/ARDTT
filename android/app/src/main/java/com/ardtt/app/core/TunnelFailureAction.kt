package com.ardtt.app.core

/** What VpnTunnelService should do after a backend failure. */
sealed class TunnelFailureAction {
    /** Tear down the VPN service. */
    data object Stop : TunnelFailureAction()

    /** Cancel / stale — ignore. */
    data object Ignore : TunnelFailureAction()

    /** Auto: Direct died, start Bypass now. */
    data object SwitchToBypass : TunnelFailureAction()

    /** Keep the service; ConnectionManager will recreate the VK call and restart Bypass. */
    data object HoldForCallRecreate : TunnelFailureAction()
}
