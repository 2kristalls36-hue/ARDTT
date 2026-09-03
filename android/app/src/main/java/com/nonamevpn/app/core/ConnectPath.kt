package com.nonamevpn.app.core

/**
 * Initial Connect path. Auto follows the underlay probe on Wi‑Fi and LTE:
 * open internet + VPS :9100 → Direct; Yandex-only whitelist → Bypass even if
 * :9100 answers (AWG UDP is usually dropped). Forced Direct/Bypass ignore the
 * probe. [underlayKind] / [bypassAllowed] stay on the signature so call sites
 * can keep passing them; they do not override a Direct probe.
 */
fun resolveConnectPath(
    mode: ConnPathMode,
    probePreferred: VpnPath?,
    lastGood: ProbeResult?,
    fresh: ProbeResult,
    @Suppress("UNUSED_PARAMETER") underlayKind: UnderlayKind = UnderlayKind.Other,
    @Suppress("UNUSED_PARAMETER") bypassAllowed: Boolean = true,
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

/**
 * Forced Bypass starts RAW without waiting on TCP :9100. Auto always probes:
 * open LTE (VPS up) must not skip into Bypass.
 */
fun shouldSkipConnectProbe(
    pathMode: ConnPathMode,
    bypassAllowed: Boolean,
    @Suppress("UNUSED_PARAMETER") underlayKind: UnderlayKind,
): Boolean = when (pathMode) {
    ConnPathMode.Direct -> false
    ConnPathMode.Bypass -> bypassAllowed
    ConnPathMode.Auto -> false
}
