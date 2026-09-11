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
 * Direct does not wait for Cloudflare. Restriction uses a weighted БС score:
 * Yandex OK plus two independent ordinary-target failures is a full sample.
 */
internal object NetworkProbePolicy {

    fun decideProbePath(
        provisionOk: Boolean?,
        yandexOk: Boolean?,
        cloudflareOk: Boolean?,
        captive: Boolean?,
        googleOk: Boolean? = null,
    ): ProbePathHint {
        if (captive == true) return ProbePathHint.Captive
        if (provisionOk == true ||
            yandexOk == true ||
            googleOk == true ||
            cloudflareOk == true
        ) {
            return ProbePathHint.Direct
        }
        val known = listOf(provisionOk, yandexOk, cloudflareOk, googleOk)
        if (known.all { it == false }) {
            return if (captive == false) ProbePathHint.NoNetwork else ProbePathHint.Wait
        }
        return ProbePathHint.Wait
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
        googleOk: Boolean = false,
        googleOutcome: CheckOutcome? = null,
        @Suppress("UNUSED_PARAMETER") seriesCount: Int = 1,
        previousWhitelistScore: Int = 0,
    ): ProbeResult {
        val yandex = yandexOutcome ?: if (yandexOk) CheckOutcome.Success else CheckOutcome.Timeout
        val bigtech = bigtechOutcome ?: if (bigtechOk) CheckOutcome.Success else CheckOutcome.Timeout
        val provision = provisionOutcome ?: if (provisionOk) CheckOutcome.Success else CheckOutcome.Timeout
        val google = googleOutcome ?: if (googleOk) CheckOutcome.Success else CheckOutcome.NotRun
        val cellular = underlayKind == UnderlayKind.Cellular
        val sample = RestrictionScore.sample(cellular, yandex, bigtech, google)
        val whitelistScore = if (cellular) {
            RestrictionScore.apply(previousWhitelistScore, sample)
        } else {
            0
        }
        val restriction = if (cellular) {
            RestrictionScore.hint(whitelistScore, sample)
        } else {
            RestrictionHint.None
        }
        if (captive) {
            return ProbeResult(
                networkClass = NetworkClass.Captive,
                preselectedPath = null,
                systemOnline = systemOnline,
                yandexOk = yandex.isSuccess,
                bigtechOk = bigtech.isSuccess,
                googleOk = google.isSuccess,
                captive = true,
                provisionOk = provision.isSuccess,
                message = "Войдите в сеть (captive portal)",
                elapsedMs = 0,
                yandexOutcome = yandex,
                bigtechOutcome = bigtech,
                googleOutcome = google,
                provisionOutcome = provision,
                restriction = RestrictionHint.Unknown,
                whitelistScorePercent = whitelistScore,
                routeReason = "captive",
                restrictionReason = null,
            )
        }
        if (!yandex.isSuccess && !bigtech.isSuccess && !google.isSuccess && !provision.isSuccess) {
            val physical = systemOnline || underlayKind != UnderlayKind.Other
            return ProbeResult(
                networkClass = if (physical) NetworkClass.DataUnconfirmed else NetworkClass.NoNetwork,
                preselectedPath = if (physical) VpnPath.Direct else null,
                systemOnline = systemOnline,
                yandexOk = false,
                bigtechOk = false,
                googleOk = false,
                captive = false,
                provisionOk = false,
                message = if (physical) {
                    "Передача данных не подтверждена"
                } else {
                    "Нет сети"
                },
                elapsedMs = 0,
                yandexOutcome = yandex,
                bigtechOutcome = bigtech,
                googleOutcome = google,
                provisionOutcome = provision,
                restriction = RestrictionHint.Unknown,
                whitelistScorePercent = whitelistScore,
                routeReason = "data-unconfirmed",
                restrictionReason = null,
            )
        }
        val internetOk = yandex.isSuccess || bigtech.isSuccess || google.isSuccess
        val likely = RestrictionScore.likely(whitelistScore, alreadyBypass = false)
        val restrictionReason = when (restriction) {
            RestrictionHint.Suspected -> "control-ok-ordinary-down"
            RestrictionHint.Confirmed -> "whitelist-score"
            RestrictionHint.None -> null
            RestrictionHint.Unknown -> null
        }
        if (provision.isFailure && internetOk && restriction == RestrictionHint.None) {
            return ProbeResult(
                networkClass = NetworkClass.OpenNeedBypass,
                preselectedPath = VpnPath.Direct,
                systemOnline = systemOnline,
                yandexOk = yandex.isSuccess,
                bigtechOk = bigtech.isSuccess,
                googleOk = google.isSuccess,
                captive = false,
                provisionOk = false,
                message = "Сеть есть, сервер управления не ответил. Прямое подключение к VPS проверяется",
                elapsedMs = 0,
                yandexOutcome = yandex,
                bigtechOutcome = bigtech,
                googleOutcome = google,
                provisionOutcome = provision,
                restriction = RestrictionHint.None,
                whitelistScorePercent = whitelistScore,
                routeReason = "direct-unavailable",
                restrictionReason = null,
            )
        }
        val message = when {
            likely ->
                "Похоже на белый список оператора. Подключаемся через обход"
            restriction == RestrictionHint.Suspected ->
                "Прямое подключение к VPS проверяется. Возможны ограничения мобильной сети"
            provision.isSuccess -> "Готово: прямое"
            internetOk -> "Сеть подключена, доступ в интернет не подтверждён для всех целей"
            else -> "Готово: прямое"
        }
        val networkClass = if (
            restriction == RestrictionHint.Suspected ||
            restriction == RestrictionHint.Confirmed
        ) {
            NetworkClass.NeedBypass
        } else {
            NetworkClass.DirectOk
        }
        return ProbeResult(
            networkClass = networkClass,
            preselectedPath = if (likely) VpnPath.Bypass else VpnPath.Direct,
            systemOnline = systemOnline,
            yandexOk = yandex.isSuccess,
            bigtechOk = bigtech.isSuccess,
            googleOk = google.isSuccess,
            captive = false,
            provisionOk = provision.isSuccess,
            message = message,
            elapsedMs = 0,
            yandexOutcome = yandex,
            bigtechOutcome = bigtech,
            googleOutcome = google,
            provisionOutcome = provision,
            restriction = restriction,
            whitelistScorePercent = whitelistScore,
            routeReason = if (likely) "whitelist" else "direct",
            restrictionReason = restrictionReason,
        )
    }

    fun restrictionHint(
        cellular: Boolean,
        yandex: CheckOutcome,
        bigtech: CheckOutcome,
        google: CheckOutcome = CheckOutcome.NotRun,
        @Suppress("UNUSED_PARAMETER") seriesCount: Int = 1,
        previousScore: Int = 0,
    ): RestrictionHint {
        val sample = RestrictionScore.sample(cellular, yandex, bigtech, google)
        val score = if (cellular) RestrictionScore.apply(previousScore, sample) else 0
        return if (cellular) RestrictionScore.hint(score, sample) else RestrictionHint.None
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

fun isRestrictionSeriesSample(
    yandex: CheckOutcome,
    bigtech: CheckOutcome,
    google: CheckOutcome = CheckOutcome.NotRun,
): Boolean {
    if (yandex.invalidatesRestrictionSeries() ||
        bigtech.invalidatesRestrictionSeries() ||
        google.invalidatesRestrictionSeries()
    ) {
        return false
    }
    return yandex.ran &&
        yandex.isSuccess &&
        bigtech.countsAsOrdinaryBlock() &&
        google.countsAsOrdinaryBlock()
}

fun nextProbeSeriesCount(
    previous: ReachabilityEvidence?,
    next: ReachabilityEvidence,
    elapsedMs: Long = next.measuredAtElapsedMs,
): Int {
    if (next.yandex.invalidatesRestrictionSeries() ||
        next.bigtech.invalidatesRestrictionSeries() ||
        next.google.invalidatesRestrictionSeries()
    ) {
        return 0
    }
    if (!isRestrictionSeriesSample(next.yandex, next.bigtech, next.google)) {
        return 0
    }
    if (previous == null) return 1
    if (previous.seriesId.isNotEmpty() &&
        next.seriesId.isNotEmpty() &&
        previous.seriesId == next.seriesId
    ) {
        return previous.seriesCount.coerceAtLeast(1)
    }
    if (previous.profileId != next.profileId) {
        return 1
    }
    if (previous.networkKey.physicalIdentityChanged(next.networkKey)) {
        return 1
    }
    if (elapsedMs > previous.ttlUntilElapsedMs && previous.ttlUntilElapsedMs > 0L) {
        return 1
    }
    if (!previous.yandex.ran || !previous.bigtech.ran || !previous.google.ran) return 1
    if (previous.measuredAtElapsedMs == next.measuredAtElapsedMs &&
        previous.bindHandle == next.bindHandle
    ) {
        return previous.seriesCount.coerceAtLeast(1)
    }
    if (!isRestrictionSeriesSample(previous.yandex, previous.bigtech, previous.google)) {
        return 1
    }
    if (previous.seriesCount <= 0) return 1
    return previous.seriesCount + 1
}
