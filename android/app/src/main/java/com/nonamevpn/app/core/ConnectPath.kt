package com.nonamevpn.app.core

/**
 * Initial Connect path. Auto uses Bypass on cellular (operator LTE / dual-SIM)
 * when a call hash exists — TCP :9100 is not proof AWG UDP works. On Wi‑Fi,
 * follow the underlay probe. Forced Direct/Bypass ignore the probe.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    lastGood: ProbeResult?,
    fresh: ProbeResult,
    underlayKind: UnderlayKind = UnderlayKind.Other,
    bypassAllowed: Boolean = true,
): VpnPath? {
    when (mode) {
        ConnPathMode.Direct -> return VpnPath.Direct
        ConnPathMode.Bypass -> return VpnPath.Bypass
        ConnPathMode.Auto -> Unit
    }
    if (shouldAutoUseBypassOnCellular(bypassAllowed, underlayKind)) {
        return VpnPath.Bypass
    }
    return fresh.preselectedPath ?: probePreferred.takeIf {
        lastGood?.networkClass == NetworkClass.DirectOk || lastGood?.preselectedPath != null
    }
}
