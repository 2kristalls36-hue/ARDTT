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
 * Pure probe classifier (no Android).
 *
 * Cloudflare is not required for Direct. A Yandex-up / Cloudflare-down pair
 * is only a mobile restriction hint after both checks actually ran.
 * Auto still tries Direct first; Bypass is a recovery path.
 */
internal object NetworkProbePolicy {

    fun decideProbePath(
        provisionOk: Boolean?,
        yandexOk: Boolean?,
        cloudflareOk: Boolean?,
        captive: Boolean?,
    ): ProbePathHint {
        if (captive == true) return ProbePathHint.Captive
        val allKnown = provisionOk != null && yandexOk != null && cloudflareOk != null
        if (!allKnown) return ProbePathHint.Wait
        val anyInternet = yandexOk == true || cloudflareOk == true || provisionOk == true
        if (!anyInternet) return ProbePathHint.NoNetwork
        return ProbePathHint.Direct
    }

    fun classify(
        systemOnline: Boolean,
        yandexOk: Boolean,
        bigtechOk: Boolean,
        captive: Boolean,
        provisionOk: Boolean,
        underlayKind: UnderlayKind = UnderlayKind.Other,
        yandexOutcome: CheckOutcome? = null,
        bigtechOutcome: CheckOutcome? = null,
        provisionOutcome: CheckOutcome? = null,
        seriesCount: Int = 1,
    ): ProbeResult {
        val yandex = yandexOutcome ?: if (yandexOk) CheckOutcome.Success else CheckOutcome.Timeout
        val bigtech = bigtechOutcome ?: if (bigtechOk) CheckOutcome.Success else CheckOutcome.Timeout
        val provision = provisionOutcome ?: if (provisionOk) CheckOutcome.Success else CheckOutcome.Timeout
        val restriction = restrictionHint(
            cellular = underlayKind == UnderlayKind.Cellular,
            yandex = yandex,
            bigtech = bigtech,
            seriesCount = seriesCount.coerceAtLeast(1),
        )
        if (captive) {
            return ProbeResult(
                networkClass = NetworkClass.Captive,
                preselectedPath = null,
                systemOnline = systemOnline,
                yandexOk = yandex.isSuccess,
                bigtechOk = bigtech.isSuccess,
                captive = true,
                provisionOk = provision.isSuccess,
                message = "Войдите в сеть (captive portal)",
                elapsedMs = 0,
                yandexOutcome = yandex,
                bigtechOutcome = bigtech,
                provisionOutcome = provision,
                restriction = RestrictionHint.Unknown,
            )
        }
        if (!yandex.isSuccess && !bigtech.isSuccess && !provision.isSuccess) {
            return ProbeResult(
                networkClass = NetworkClass.NoNetwork,
                preselectedPath = null,
                systemOnline = systemOnline,
                yandexOk = false,
                bigtechOk = false,
                captive = false,
                provisionOk = false,
                message = "Нет сети",
                elapsedMs = 0,
                yandexOutcome = yandex,
                bigtechOutcome = bigtech,
                provisionOutcome = provision,
                restriction = RestrictionHint.Unknown,
            )
        }
        val internetOk = yandex.isSuccess || bigtech.isSuccess
        if (provision.isFailure && internetOk && restriction == RestrictionHint.None) {
            return ProbeResult(
                networkClass = NetworkClass.OpenNeedBypass,
                preselectedPath = VpnPath.Direct,
                systemOnline = systemOnline,
                yandexOk = yandex.isSuccess,
                bigtechOk = bigtech.isSuccess,
                captive = false,
                provisionOk = false,
                message = "Сеть есть, сервер не отвечает — пробуем прямое подключение",
                elapsedMs = 0,
                yandexOutcome = yandex,
                bigtechOutcome = bigtech,
                provisionOutcome = provision,
                restriction = RestrictionHint.None,
            )
        }
        val message = when {
            restriction == RestrictionHint.Suspected ->
                "Похоже на ограничения мобильной сети"
            provision.isSuccess -> "Готово: прямое"
            internetOk -> "Сеть подключена, доступ в интернет не подтверждён для всех целей"
            else -> "Готово: прямое"
        }
        val networkClass = if (restriction == RestrictionHint.Suspected) {
            NetworkClass.NeedBypass
        } else {
            NetworkClass.DirectOk
        }
        return ProbeResult(
            networkClass = networkClass,
            preselectedPath = VpnPath.Direct,
            systemOnline = systemOnline,
            yandexOk = yandex.isSuccess,
            bigtechOk = bigtech.isSuccess,
            captive = false,
            provisionOk = provision.isSuccess,
            message = message,
            elapsedMs = 0,
            yandexOutcome = yandex,
            bigtechOutcome = bigtech,
            provisionOutcome = provision,
            restriction = restriction,
        )
    }

    fun restrictionHint(
        cellular: Boolean,
        yandex: CheckOutcome,
        bigtech: CheckOutcome,
        seriesCount: Int,
    ): RestrictionHint {
        if (!cellular) return RestrictionHint.None
        if (!yandex.ran || !bigtech.ran) return RestrictionHint.Unknown
        if (yandex.isSuccess && bigtech.isFailure) {
            return if (seriesCount >= RecoverySettings.RESTRICTION_CONFIRM_SERIES) {
                RestrictionHint.Confirmed
            } else {
                RestrictionHint.Suspected
            }
        }
        if (yandex.isSuccess && bigtech.isSuccess) return RestrictionHint.None
        return RestrictionHint.Unknown
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

fun nextProbeSeriesCount(
    previous: ReachabilityEvidence?,
    next: ReachabilityEvidence,
): Int {
    if (!next.yandex.ran || !next.bigtech.ran) {
        return previous?.seriesCount ?: 1
    }
    if (previous == null) return 1
    if (previous.networkKey != next.networkKey || previous.profileId != next.profileId) {
        return 1
    }
    if (!previous.yandex.ran || !previous.bigtech.ran) return 1
    if (previous.measuredAtElapsedMs == next.measuredAtElapsedMs &&
        previous.bindHandle == next.bindHandle
    ) {
        return previous.seriesCount
    }
    return previous.seriesCount + 1
}
