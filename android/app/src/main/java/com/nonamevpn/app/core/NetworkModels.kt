package com.nonamevpn.app.core

enum class NetworkClass {
    NoNetwork,
    Captive,
    DirectOk,
    NeedBypass,
    OpenNeedBypass,
}

enum class VpnPath {
    Direct,
    Bypass,
}

data class ProbeResult(
    val networkClass: NetworkClass,
    val preselectedPath: VpnPath?,
    val systemOnline: Boolean,
    /** 77.88.8.8 — Yandex DNS, reaches even on operator whitelist (БС). */
    val yandexOk: Boolean,
    /** 1.1.1.1 — Cloudflare, typically blocked on БС. */
    val bigtechOk: Boolean,
    val captive: Boolean,
    val provisionOk: Boolean,
    val message: String,
    val elapsedMs: Long,
) {
    /** Operator whitelist: Yandex DNS lives, Cloudflare does not. */
    val whitelistRestricted: Boolean get() = yandexOk && !bigtechOk
}
