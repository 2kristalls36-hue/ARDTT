package com.ardtt.app.core

/**
 * Weighted operator-whitelist (БС) confidence.
 *
 * One flaky ordinary-target success must not wipe a run of БС samples, and a
 * single incomplete round must not lock Bypass. A full sample needs both
 * controls: Yandex DNS answering while vk.com does not is a broken link, not a
 * whitelist, and Bypass rides a VK call anyway. Enter at
 * [RecoverySettings.WHITELIST_ENTER_PERCENT]; stay on Bypass until the score
 * falls below [RecoverySettings.WHITELIST_EXIT_PERCENT].
 *
 * Weak-only accumulation is capped at [RecoverySettings.WHITELIST_WEAK_ONLY_CAP_PERCENT]
 * unless a strong confirmation is still inside TTL. Open without Yandex requires
 * two independent ordinary providers (Cloudflare TLS/DNS is one, Google TLS/DNS
 * is one).
 */
enum class RestrictionSample {
    /** Both controls OK and both ordinary targets timed out or refused. */
    Positive,
    /** Control OK and the whitelist evidence is only partial — one blocked target, or no vk.com verdict. */
    WeakPositive,
    /** Ordinary internet visible — with or without Yandex. */
    Open,
    /** Lost radio, TLS, cancelled, incomplete, or a Russian service down — leave the score unchanged. */
    Ignore,
}

enum class RestrictionDisplay {
    None,
    Possible,
    Confirmed,
    Stale,
}

object RestrictionScore {
    fun sample(
        cellular: Boolean,
        yandex: CheckOutcome,
        bigtech: CheckOutcome,
        google: CheckOutcome,
        ruService: CheckOutcome = CheckOutcome.NotRun,
    ): RestrictionSample {
        if (!cellular) return RestrictionSample.Ignore
        if (yandex.invalidatesRestrictionSeries() ||
            bigtech.invalidatesRestrictionSeries() ||
            google.invalidatesRestrictionSeries() ||
            ruService.invalidatesRestrictionSeries()
        ) {
            return RestrictionSample.Ignore
        }
        val ordinary = listOf(bigtech, google)
        val ordinaryOk = ordinary.count { it.isSuccess }
        val ordinaryBlock = ordinary.count { it.countsAsOrdinaryBlock() }
        val dirty = ordinary.any { it.ran && !it.isSuccess && !it.countsAsOrdinaryBlock() }
        val yandexOk = yandex.ran && yandex.isSuccess
        if (yandexOk && ordinaryOk >= 1) return RestrictionSample.Open
        if (!yandexOk && ordinaryOk >= 2) return RestrictionSample.Open
        if (!yandexOk) return RestrictionSample.Ignore
        if (dirty) return RestrictionSample.Ignore
        // A whitelisted Russian service that does not answer means a bad link
        // (or a VK outage), not a whitelist — and Bypass would not work either.
        if (ruService.isFailure) return RestrictionSample.Ignore
        if (ordinaryBlock >= 2) {
            // Without a vk.com verdict the round is partial evidence; +25 never
            // reaches the enter threshold on its own.
            return if (ruService.isSuccess) RestrictionSample.Positive else RestrictionSample.WeakPositive
        }
        if (ordinaryBlock == 1) return RestrictionSample.WeakPositive
        return RestrictionSample.Ignore
    }

    fun apply(
        previousPercent: Int,
        sample: RestrictionSample,
        freshStrongConfirmation: Boolean = false,
    ): Int {
        val next = when (sample) {
            RestrictionSample.Positive -> previousPercent + RecoverySettings.WHITELIST_SCORE_RISE
            RestrictionSample.WeakPositive -> {
                val raised = previousPercent + RecoverySettings.WHITELIST_SCORE_WEAK
                if (freshStrongConfirmation) raised else {
                    raised.coerceAtMost(RecoverySettings.WHITELIST_WEAK_ONLY_CAP_PERCENT)
                }
            }
            RestrictionSample.Open -> previousPercent + RecoverySettings.WHITELIST_SCORE_FALL
            RestrictionSample.Ignore -> previousPercent
        }
        return next.coerceIn(0, 100)
    }

    fun hint(
        scorePercent: Int,
        sample: RestrictionSample,
        freshStrongConfirmation: Boolean = sample == RestrictionSample.Positive,
    ): RestrictionHint {
        if (freshStrongConfirmation &&
            scorePercent >= RecoverySettings.WHITELIST_ENTER_PERCENT
        ) {
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

    /** New-connect Bypass for whitelist needs the enter threshold AND a fresh strong round. */
    fun mayEnterBypassForWhitelist(
        scorePercent: Int,
        freshStrongConfirmation: Boolean,
    ): Boolean = freshStrongConfirmation &&
        scorePercent >= RecoverySettings.WHITELIST_ENTER_PERCENT

    /**
     * Working Bypass stays until a fresh Open drops the score, or until a
     * bounded unknown streak allows a controlled Direct try. TTL expiry
     * alone does not switch.
     */
    fun bypassHoldsDirectReeval(
        historicalScore: Int,
        freshStrong: Boolean,
        usable: Boolean,
        unknownStreak: Int,
        alreadyBypass: Boolean,
    ): Boolean {
        if (!alreadyBypass) {
            return mayEnterBypassForWhitelist(historicalScore, freshStrong)
        }
        val exitLikely = likely(historicalScore, alreadyBypass = true)
        val staleScoreBlocks = !freshStrong &&
            !usable &&
            historicalScore >= RecoverySettings.WHITELIST_EXIT_PERCENT &&
            unknownStreak < RecoverySettings.WHITELIST_UNKNOWN_DIRECT_TRY_STREAK
        return (freshStrong && exitLikely) ||
            (usable && exitLikely) ||
            staleScoreBlocks
    }

    fun display(
        scorePercent: Int,
        freshStrong: Boolean,
        usable: Boolean,
        sample: RestrictionSample = RestrictionSample.Ignore,
    ): RestrictionDisplay {
        if (freshStrong && scorePercent >= RecoverySettings.WHITELIST_ENTER_PERCENT) {
            return RestrictionDisplay.Confirmed
        }
        if (usable && (scorePercent > 0 || sample == RestrictionSample.WeakPositive)) {
            return RestrictionDisplay.Possible
        }
        if (scorePercent > 0 && !usable && !freshStrong) {
            return RestrictionDisplay.Stale
        }
        return RestrictionDisplay.None
    }
}

fun RestrictionSample.isUsableEvidence(): Boolean = when (this) {
    RestrictionSample.Positive,
    RestrictionSample.WeakPositive,
    RestrictionSample.Open,
    -> true
    RestrictionSample.Ignore -> false
}

fun RestrictionSample.isStrongEvidence(): Boolean = this == RestrictionSample.Positive

internal const val SEEN_PROBE_SERIES_LIMIT = 8

fun rememberProbeSeriesId(ids: List<String>, seriesId: String): List<String> {
    if (seriesId.isEmpty()) return ids
    if (ids.lastOrNull() == seriesId) return ids
    val without = ids.filterNot { it == seriesId }
    return (without + seriesId).takeLast(SEEN_PROBE_SERIES_LIMIT)
}

fun ReachabilityEvidence.historicalWhitelistScore(
    key: NetworkKey?,
    profileId: String?,
): Int {
    if (key != null && !WhitelistDetection.appliesTo(key)) {
        return WhitelistDetection.STUB_SCORE_PERCENT
    }
    if (!originMatches(key, profileId)) return 0
    return whitelistScorePercent
}

fun ReachabilityEvidence.whitelistScoreAt(
    key: NetworkKey?,
    profileId: String?,
    nowElapsedMs: Long = measuredAtElapsedMs,
): Int {
    if (key != null && !WhitelistDetection.appliesTo(key)) {
        return WhitelistDetection.STUB_SCORE_PERCENT
    }
    if (!originMatches(key, profileId)) return 0
    if (RecoverySettings.evidenceExpired(nowElapsedMs, usableUntilElapsedMs())) return 0
    return whitelistScorePercent
}

fun hasSameNetworkProbeEvidence(
    evidence: ReachabilityEvidence?,
    key: NetworkKey?,
    profileId: String?,
    nowElapsedMs: Long = evidence?.measuredAtElapsedMs ?: 0L,
): Boolean {
    if (evidence == null) return false
    if (!evidence.originMatches(key, profileId)) return false
    if (RecoverySettings.evidenceExpired(nowElapsedMs, evidence.usableUntilElapsedMs())) {
        return false
    }
    return evidence.yandex.ran || evidence.whitelistScorePercent > 0 ||
        evidence.bigtech.ran || evidence.google.ran
}

fun foldReachabilityEvidence(
    previous: ReachabilityEvidence?,
    incoming: ReachabilityEvidence,
    cellular: Boolean,
    elapsedMs: Long,
): ReachabilityEvidence {
    val series = nextProbeSeriesCount(previous, incoming, elapsedMs)
    val completed = (previous?.completedSeries ?: 0) + 1
    // Roaming keeps the handle and the SIM, but the whitelist belongs to the
    // serving operator: a sample from PLMN B must not fold onto PLMN A's score.
    val samePhysical = previous != null &&
        (previous.networkKey == null ||
            incoming.networkKey == null ||
            (previous.networkKey.samePhysicalNetwork(incoming.networkKey) &&
                previous.networkKey.sameCarrier(incoming.networkKey))) &&
        (previous.profileId == null ||
            incoming.profileId == null ||
            previous.profileId == incoming.profileId)
    if (previous != null &&
        incoming.measuredAtElapsedMs > 0L &&
        previous.observedAtElapsedMs > 0L &&
        incoming.measuredAtElapsedMs < previous.observedAtElapsedMs
    ) {
        return previous.copy(completedSeries = completed)
    }
    val sample = RestrictionScore.sample(
        cellular = cellular,
        yandex = incoming.yandex,
        bigtech = incoming.bigtech,
        google = incoming.google,
        ruService = incoming.ruService,
    )
    val previousScore = if (cellular && samePhysical) {
        previous?.whitelistScorePercent ?: 0
    } else {
        0
    }
    val previousStrongFresh = samePhysical &&
        previous?.hasFreshStrong(elapsedMs, incoming.networkKey ?: previous.networkKey, incoming.profileId) == true
    val score = if (cellular) {
        RestrictionScore.apply(previousScore, sample, previousStrongFresh)
    } else {
        WhitelistDetection.STUB_SCORE_PERCENT
    }
    val restriction = if (!cellular) {
        WhitelistDetection.stubRestriction
    } else {
        when (sample) {
            RestrictionSample.Positive -> RestrictionHint.Confirmed
            RestrictionSample.Open -> RestrictionScore.hint(score, sample, freshStrongConfirmation = false)
            RestrictionSample.WeakPositive -> RestrictionScore.hint(
                score,
                sample,
                freshStrongConfirmation = previousStrongFresh,
            )
            RestrictionSample.Ignore -> when {
                previousStrongFresh -> previous?.restriction ?: RestrictionHint.Unknown
                previous?.usableAt(elapsedMs, incoming.networkKey, incoming.profileId) == true &&
                    (previous.restriction == RestrictionHint.Suspected ||
                        previous.restriction == RestrictionHint.None) ->
                    previous.restriction
                else -> RestrictionHint.Unknown
            }
        }
    }
    val ttl = RecoverySettings.PROBE_RESTRICTION_TTL_MS
    val observed = elapsedMs
    val usableAt = if (sample.isUsableEvidence()) elapsedMs else previous?.takeIf { samePhysical }?.usableAtElapsedMs ?: 0L
    val strongAt = when {
        sample.isStrongEvidence() -> elapsedMs
        samePhysical -> previous?.strongAtElapsedMs ?: 0L
        else -> 0L
    }
    val usableTtl = if (sample.isUsableEvidence()) elapsedMs + ttl else {
        previous?.takeIf { samePhysical }?.ttlUntilElapsedMs ?: 0L
    }
    val strongUntil = if (sample.isStrongEvidence()) {
        elapsedMs + ttl
    } else {
        previous?.takeIf { samePhysical }?.strongUntilElapsedMs ?: 0L
    }
    val ordinaryOpenAt = if (sample == RestrictionSample.Open) {
        elapsedMs
    } else {
        previous?.takeIf { samePhysical }?.ordinaryOpenAtElapsedMs ?: 0L
    }
    val unknownStreak = when {
        !samePhysical -> if (sample == RestrictionSample.Ignore) 1 else 0
        sample == RestrictionSample.Ignore -> (previous?.unknownStreak ?: 0) + 1
        else -> 0
    }
    val origin = when {
        // Strong TTL keeps the radio it was measured on. Ignore/Weak must not
        // promote an unknown origin into a known carrier for a later rebind.
        sample.isStrongEvidence() ->
            incoming.measurementOrigin() ?: previous?.takeIf { samePhysical }?.measurementOrigin()
        samePhysical ->
            previous?.measurementOrigin() ?: incoming.measurementOrigin()
        else -> incoming.measurementOrigin()
    }
    return incoming.copy(
        originNetworkKey = origin,
        seriesCount = series,
        completedSeries = completed,
        unknownStreak = unknownStreak,
        lastSample = sample,
        measuredAtElapsedMs = elapsedMs,
        observedAtElapsedMs = observed,
        usableAtElapsedMs = usableAt,
        strongAtElapsedMs = strongAt,
        ordinaryOpenAtElapsedMs = ordinaryOpenAt,
        ttlUntilElapsedMs = usableTtl,
        strongUntilElapsedMs = strongUntil,
        restriction = restriction,
        whitelistScorePercent = score,
        restrictionReason = when {
            restriction == RestrictionHint.Confirmed -> "whitelist-score"
            restriction == RestrictionHint.Suspected -> "control-ok-ordinary-down"
            restriction == RestrictionHint.Unknown && sample == RestrictionSample.Ignore ->
                "stale-or-unknown"
            else -> incoming.restrictionReason
        },
    )
}

internal fun ProbeResult.withWhitelistEvidence(
    evidence: ReachabilityEvidence,
    cellular: Boolean,
    nowElapsedMs: Long = evidence.measuredAtElapsedMs,
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
    val freshStrong = evidence.hasFreshStrong(nowElapsedMs, evidence.networkKey, evidence.profileId)
    val likely = RestrictionScore.mayEnterBypassForWhitelist(
        evidence.whitelistScorePercent,
        freshStrong,
    )
    if (!likely) {
        val dropWhitelistBypass = preselectedPath == VpnPath.Bypass && routeReason == "whitelist"
        return copy(
            whitelistScorePercent = evidence.whitelistScorePercent,
            restriction = evidence.restriction,
            preselectedPath = if (dropWhitelistBypass) VpnPath.Direct else preselectedPath,
            routeReason = if (dropWhitelistBypass) "direct" else routeReason,
        )
    }
    return copy(
        whitelistScorePercent = evidence.whitelistScorePercent,
        restriction = evidence.restriction,
        preselectedPath = VpnPath.Bypass,
        networkClass = NetworkClass.NeedBypass,
        routeReason = "whitelist",
        restrictionReason = evidence.restrictionReason,
        message = "Признаки белого списка подтверждены проверками. Подключаемся через обход",
    )
}
