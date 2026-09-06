package com.ardtt.app.core

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
    /** 1.1.1.1 — Cloudflare TLS or UDP :53. TCP :443 alone is not open internet. */
    val bigtechOk: Boolean,
    val captive: Boolean,
    /** AWG UDP handshake response from direct.endpoint (not used in live probe). */
    val awgUdpOk: Boolean = false,
    /** HTTP /health on the VPS — not TCP :9100, not AWG UDP :51820. */
    val provisionOk: Boolean,
    val message: String,
    val elapsedMs: Long,
) {
    /** Operator whitelist: Yandex DNS lives, Cloudflare does not. */
    val whitelistRestricted: Boolean get() = yandexOk && !bigtechOk
}
