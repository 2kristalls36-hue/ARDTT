package com.ardtt.app.core

/**
 * Weighted operator-whitelist (БС) confidence.
 *
 * One flaky ordinary-target success must not wipe a run of БС samples, and a
 * single incomplete round must not lock Bypass. Enter at
 * [RecoverySettings.WHITELIST_ENTER_PERCENT]; stay on Bypass until the score
 * falls below [RecoverySettings.WHITELIST_EXIT_PERCENT].
 */
enum class RestrictionSample {
    /** Yandex OK and both ordinary targets timed out or refused. */
    Positive,
    /** Yandex OK and exactly one ordinary target blocked; the other did not succeed. */
    WeakPositive,
    /** Yandex OK and at least one ordinary target succeeded. */
    Open,
    /** Lost radio, TLS, cancelled, or incomplete — leave the score unchanged. */
    Ignore,
}

object RestrictionScore {
    fun sample(
        cellular: Boolean,
        yandex: CheckOutcome,
        bigtech: CheckOutcome,
        google: CheckOutcome,
    ): RestrictionSample {
        if (!cellular) return RestrictionSample.Ignore
        if (yandex.invalidatesRestrictionSeries() ||
            bigtech.invalidatesRestrictionSeries() ||
            google.invalidatesRestrictionSeries()
        ) {
            return RestrictionSample.Ignore
        }
        if (!yandex.ran || !yandex.isSuccess) return RestrictionSample.Ignore
        val ordinary = listOf(bigtech, google)
        val ordinaryOk = ordinary.count { it.isSuccess }
        val ordinaryBlock = ordinary.count { it.countsAsOrdinaryBlock() }
        val dirty = ordinary.any { it.ran && !it.isSuccess && !it.countsAsOrdinaryBlock() }
        if (ordinaryOk >= 1) return RestrictionSample.Open
        if (dirty) return RestrictionSample.Ignore
        if (ordinaryBlock >= 2) return RestrictionSample.Positive
        if (ordinaryBlock == 1) return RestrictionSample.WeakPositive
        return RestrictionSample.Ignore
    }

    fun apply(previousPercent: Int, sample: RestrictionSample): Int {
        val delta = when (sample) {
            RestrictionSample.Positive -> RecoverySettings.WHITELIST_SCORE_RISE
            RestrictionSample.WeakPositive -> RecoverySettings.WHITELIST_SCORE_WEAK
            RestrictionSample.Open -> RecoverySettings.WHITELIST_SCORE_FALL
            RestrictionSample.Ignore -> 0
        }
        return (previousPercent + delta).coerceIn(0, 100)
    }

    fun hint(scorePercent: Int, sample: RestrictionSample): RestrictionHint {
        if (scorePercent >= RecoverySettings.WHITELIST_ENTER_PERCENT) {
            return RestrictionHint.Confirmed
        }
        if (scorePercent <= 0) {
            return when (sample) {
                RestrictionSample.Open -> RestrictionHint.None
                RestrictionSample.Ignore,
                RestrictionSample.WeakPositive,
                RestrictionSample.Positive,
                -> RestrictionHint.Unknown
            }
        }
        return RestrictionHint.Suspected
    }

    fun likely(scorePercent: Int, alreadyBypass: Boolean): Boolean {
        val threshold = if (alreadyBypass) {
            RecoverySettings.WHITELIST_EXIT_PERCENT
        } else {
            RecoverySettings.WHITELIST_ENTER_PERCENT
        }
        return scorePercent >= threshold
    }
}

fun ReachabilityEvidence.whitelistScoreAt(
    key: NetworkKey?,
    profileId: String?,
): Int {
    if (key != null && !WhitelistDetection.appliesTo(key)) {
        return WhitelistDetection.STUB_SCORE_PERCENT
    }
    if (networkKey != null && key != null && !networkKey.samePhysicalNetwork(key)) return 0
    if (networkKey != null && key != null && !networkKey.sameCarrier(key)) return 0
    if (this.profileId != null && profileId != null && this.profileId != profileId) return 0
    return whitelistScorePercent
}

fun hasSameNetworkProbeEvidence(
    evidence: ReachabilityEvidence?,
    key: NetworkKey?,
    profileId: String?,
): Boolean {
    if (evidence == null) return false
    if (evidence.networkKey != null && key != null && !evidence.networkKey.samePhysicalNetwork(key)) {
        return false
    }
    if (evidence.networkKey != null && key != null && !evidence.networkKey.sameCarrier(key)) {
        return false
    }
    if (evidence.profileId != null && profileId != null && evidence.profileId != profileId) {
        return false
    }
    return evidence.yandex.ran || evidence.whitelistScorePercent > 0
}

fun foldReachabilityEvidence(
    previous: ReachabilityEvidence?,
    incoming: ReachabilityEvidence,
    cellular: Boolean,
    elapsedMs: Long,
): ReachabilityEvidence {
    val series = nextProbeSeriesCount(previous, incoming, elapsedMs)
    val completed = (previous?.completedSeries ?: 0) + 1
    val samePhysical = previous != null &&
        (previous.networkKey == null ||
            incoming.networkKey == null ||
            previous.networkKey.samePhysicalNetwork(incoming.networkKey)) &&
        (previous.profileId == null ||
            incoming.profileId == null ||
            previous.profileId == incoming.profileId)
    val sample = RestrictionScore.sample(
        cellular = cellular,
        yandex = incoming.yandex,
        bigtech = incoming.bigtech,
        google = incoming.google,
    )
    val previousScore = if (cellular && samePhysical) {
        previous?.whitelistScorePercent ?: 0
    } else {
        0
    }
    val score = if (cellular) {
        RestrictionScore.apply(previousScore, sample)
    } else {
        WhitelistDetection.STUB_SCORE_PERCENT
    }
    val restriction = if (cellular) {
        RestrictionScore.hint(score, sample)
    } else {
        WhitelistDetection.stubRestriction
    }
    return incoming.copy(
        seriesCount = series,
        completedSeries = completed,
        ttlUntilElapsedMs = elapsedMs + RecoverySettings.PROBE_RESTRICTION_TTL_MS,
        restriction = restriction,
        whitelistScorePercent = score,
        restrictionReason = when {
            restriction == RestrictionHint.Confirmed -> "whitelist-score"
            restriction == RestrictionHint.Suspected -> "control-ok-ordinary-down"
            else -> incoming.restrictionReason
        },
    )
}

internal fun ProbeResult.withWhitelistEvidence(
    evidence: ReachabilityEvidence,
    cellular: Boolean,
): ProbeResult {
    if (!cellular) {
        return copy(
            whitelistScorePercent = WhitelistDetection.STUB_SCORE_PERCENT,
            restriction = WhitelistDetection.stubRestriction,
        )
    }
    if (captive ||
        networkClass == NetworkClass.Captive ||
        networkClass == NetworkClass.NoNetwork
    ) {
        return copy(
            whitelistScorePercent = evidence.whitelistScorePercent,
            restriction = evidence.restriction,
        )
    }
    val likely = RestrictionScore.likely(evidence.whitelistScorePercent, alreadyBypass = false)
    if (!likely) {
        return copy(
            whitelistScorePercent = evidence.whitelistScorePercent,
            restriction = evidence.restriction,
        )
    }
    return copy(
        whitelistScorePercent = evidence.whitelistScorePercent,
        restriction = evidence.restriction,
        preselectedPath = VpnPath.Bypass,
        networkClass = NetworkClass.NeedBypass,
        routeReason = "whitelist",
        restrictionReason = evidence.restrictionReason,
        message = "Похоже на белый список оператора. Подключаемся через обход",
    )
}
