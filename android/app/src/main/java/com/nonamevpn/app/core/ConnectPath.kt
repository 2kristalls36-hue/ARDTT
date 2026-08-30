package com.nonamevpn.app.core

/**
 * Initial Connect path. Auto follows the underlay probe (Direct when the VPS
 * IP is reachable, Bypass on operator whitelist). Forced Direct/Bypass ignore
 * the probe.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    lastGood: ProbeResult?,
    fresh: ProbeResult,
): VpnPath? {
    when (mode) {
        ConnPathMode.Direct -> return VpnPath.Direct
        ConnPathMode.Bypass -> return VpnPath.Bypass
        ConnPathMode.Auto -> Unit
    }
    return fresh.preselectedPath ?: probePreferred.takeIf {
        lastGood?.networkClass == NetworkClass.DirectOk || lastGood?.preselectedPath != null
    }
}
