package com.nonamevpn.app.core

/**
 * Initial Connect path. Auto follows the underlay probe (Direct when the VPS
 * IP is reachable, Bypass on operator whitelist) unless the in-app apps
 * whitelist (БС) has selected packages — those must ride RAW, like 0.5.83.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    lastGood: ProbeResult?,
    fresh: ProbeResult,
    forceBypass: Boolean = false,
): VpnPath? {
    when (mode) {
        ConnPathMode.Direct -> return VpnPath.Direct
        ConnPathMode.Bypass -> return VpnPath.Bypass
        ConnPathMode.Auto -> Unit
    }
    if (forceBypass) return VpnPath.Bypass
    return fresh.preselectedPath ?: probePreferred.takeIf {
        lastGood?.networkClass == NetworkClass.DirectOk || lastGood?.preselectedPath != null
    }
}

/** БС with at least one app and a call hash → Auto must use Bypass. */
fun appWhitelistForcesBypass(
    whitelistMode: Boolean,
    selectedAppCount: Int,
    hasCallHash: Boolean,
): Boolean = whitelistMode && selectedAppCount > 0 && hasCallHash
