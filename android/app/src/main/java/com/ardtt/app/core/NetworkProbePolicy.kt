package com.ardtt.app.core

import java.net.URI

internal enum class ProbePathHint {
    Wait,
    Direct,
    Bypass,
    NoNetwork,
    Captive,
}

/**
 * Pure Auto-path classifier (no Android).
 *
 * 77.88.8.8 = internet even on operator whitelist.
 * 1.1.1.1 counts as open only after TLS or UDP :53 — a TCP connect to
 * :443 is not enough (MTS: TCP up, TLS dead, AmneziaWG UDP dead).
 * VPS [provisionOk] is HTTP /health, not TCP :9100 (MTS: connect works,
 * GET times out). Direct requires open Cloudflare. Reaching the VPS
 * alone is not Direct: that UDP :51820 path is what the whitelist drops.
 */
internal object NetworkProbePolicy {

    fun decideProbePath(
        provisionOk: Boolean?,
        yandexOk: Boolean?,
        cloudflareOk: Boolean?,
        captive: Boolean?,
    ): ProbePathHint {
        if (captive == true) return ProbePathHint.Captive
        if (cloudflareOk == true && provisionOk == true) return ProbePathHint.Direct
        if (cloudflareOk == true && provisionOk == false) return ProbePathHint.Bypass
        // Yandex lives and Cloudflare is not actually open → Bypass now.
        // Do not wait for TCP :9100: that is not AmneziaWG UDP :51820.
        if (yandexOk == true && cloudflareOk == false) return ProbePathHint.Bypass
        val anyInternet = yandexOk == true || cloudflareOk == true
        val internetDead = yandexOk == false && cloudflareOk == false
        if (provisionOk == false && anyInternet) return ProbePathHint.Bypass
        if (provisionOk == false && internetDead) return ProbePathHint.NoNetwork
        if (provisionOk == true && cloudflareOk == false && yandexOk == false) {
            return ProbePathHint.Bypass
        }
        return ProbePathHint.Wait
    }

    fun classify(
        systemOnline: Boolean,
        yandexOk: Boolean,
        bigtechOk: Boolean,
        captive: Boolean,
        provisionOk: Boolean,
    ): ProbeResult {
        if (captive) {
            return ProbeResult(
                networkClass = NetworkClass.Captive,
                preselectedPath = null,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = true,
                provisionOk = provisionOk,
                message = "Войдите в сеть (captive portal)",
                elapsedMs = 0,
            )
        }
        if (!systemOnline && !yandexOk && !bigtechOk && !provisionOk) {
            return ProbeResult(
                networkClass = NetworkClass.NoNetwork,
                preselectedPath = null,
                systemOnline = false,
                yandexOk = false,
                bigtechOk = false,
                captive = false,
                provisionOk = false,
                message = "Нет сети",
                elapsedMs = 0,
            )
        }
        if (provisionOk && bigtechOk) {
            return ProbeResult(
                networkClass = NetworkClass.DirectOk,
                preselectedPath = VpnPath.Direct,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = true,
                captive = false,
                provisionOk = true,
                message = "Готово: прямое",
                elapsedMs = 0,
            )
        }
        if (yandexOk && !bigtechOk) {
            return ProbeResult(
                networkClass = NetworkClass.NeedBypass,
                preselectedPath = VpnPath.Bypass,
                systemOnline = systemOnline,
                yandexOk = true,
                bigtechOk = false,
                captive = false,
                provisionOk = provisionOk,
                message = if (provisionOk) {
                    "Готово: обход (белый список — UDP до VPS, скорее всего, закрыт)"
                } else {
                    "Готово: обход (белый список, VPS недоступен)"
                },
                elapsedMs = 0,
            )
        }
        if (provisionOk && !bigtechOk) {
            return ProbeResult(
                networkClass = NetworkClass.NeedBypass,
                preselectedPath = VpnPath.Bypass,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = false,
                captive = false,
                provisionOk = true,
                message = "Готово: обход (VPS отвечает, открытого интернета нет)",
                elapsedMs = 0,
            )
        }
        if (yandexOk || bigtechOk) {
            val open = bigtechOk
            return ProbeResult(
                networkClass = if (open) NetworkClass.OpenNeedBypass else NetworkClass.NeedBypass,
                preselectedPath = VpnPath.Bypass,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = false,
                provisionOk = false,
                message = if (open) {
                    "Готово: обход (VPS недоступен)"
                } else {
                    "Готово: обход (белый список, VPS недоступен)"
                },
                elapsedMs = 0,
            )
        }
        return ProbeResult(
            networkClass = NetworkClass.NoNetwork,
            preselectedPath = null,
            systemOnline = systemOnline,
            yandexOk = yandexOk,
            bigtechOk = bigtechOk,
            captive = false,
            provisionOk = provisionOk,
            message = "Нет сети",
            elapsedMs = 0,
        )
    }

    fun parseProvisionEndpoint(baseUrl: String?): Pair<String, Int>? {
        if (baseUrl.isNullOrBlank()) return null
        return try {
            val url = URI(baseUrl.trim()).toURL()
            val host = url.host?.takeIf { it.isNotBlank() } ?: return null
            val port = when {
                url.port > 0 -> url.port
                url.defaultPort > 0 -> url.defaultPort
                else -> 9100
            }
            host to port
        } catch (_: Exception) {
            null
        }
    }
}
