package com.ardtt.app.core

data class UserConnectionIntent(
    val wantsConnected: Boolean = false,
    val mode: ConnPathMode = ConnPathMode.Auto,
    val profileId: String? = null,
    val hasCallHash: Boolean = false,
    val silentRecreate: Boolean = false,
    /** Fingerprint of Direct/Bypass transport parameters; independent of VK CallSession. */
    val directConfigRevision: String = "",
)

enum class CallValidity {
    Valid,
    UnknownDueToNetwork,
    CredentialsExpired,
    NeedsAuth,
    ConfirmedDead,
}

/** In-flight creation of a replacement VK call. Distinct from [CallValidity] of the old session. */
enum class CallCreateOp {
    None,
    InFlight,
    WaitingNetwork,
    Backoff,
    NeedsUser,
    Applied,
}

data class CallSessionState(
    val hashPresent: Boolean = false,
    val validity: CallValidity = CallValidity.Valid,
    val profileId: String? = null,
    val createdThisGeneration: Boolean = false,
    val identityToken: String = "",
    val callEpoch: Long = 0L,
    val createOp: CallCreateOp = CallCreateOp.None,
    val createGeneration: Long = 0L,
    val createHold: Boolean = false,
    val createNetworkAttempts: Int = 0,
    val createUserAction: UserActionKind? = null,
    val createRequestId: Long? = null,
) {
    val canReuse: Boolean
        get() = hashPresent &&
            validity != CallValidity.ConfirmedDead &&
            validity != CallValidity.NeedsAuth
}

enum class TransportLifecycle {
    Stopped,
    Starting,
    Running,
    Paused,
    Parked,
    Failed,
}

/** How far the current transport attempt has been proven. */
enum class PathReadiness {
    None,
    BackendRunning,
    ProtocolReady,
    PathConfirmed,
    Unsupported,
}

data class DirectNegativeEvidence(
    val key: NetworkKey,
    val profileId: String? = null,
    val reason: String = "",
    val failedAtElapsedMs: Long = 0L,
    val retryAfterElapsedMs: Long = 0L,
) {
    fun stillBlocks(elapsedMs: Long, key: NetworkKey?, profileId: String?): Boolean {
        if (key == null) return false
        if (this.key.isCellular && key.isCellular) {
            if (!this.key.matchesCellularUnderlay(key)) return false
        } else if (!this.key.samePhysicalNetwork(key)) {
            return false
        }
        // A new operator may route AWG UDP where the previous one dropped it.
        if (!this.key.sameCarrier(key)) return false
        if (this.profileId != null && profileId != null && this.profileId != profileId) return false
        return elapsedMs < retryAfterElapsedMs
    }
}

data class RecoveryPermit(
    val sessionEpoch: Long = 0L,
    val networkEpoch: Long = 0L,
    val transportEpoch: Long = 0L,
    val callEpoch: Long = 0L,
    val netOpsAllowed: Boolean = false,
    val recoveryInFlight: Boolean = false,
    val callOpInFlight: Boolean = false,
    val userStop: Boolean = true,
) {
        fun accepts(
            sessionEpoch: Long,
            networkEpoch: Long? = null,
            transportEpoch: Long? = null,
            callEpoch: Long? = null,
        ): Boolean {
            if (userStop) return false
            if (sessionEpoch != this.sessionEpoch) return false
            if (networkEpoch != null && networkEpoch != this.networkEpoch) return false
            if (transportEpoch != null && transportEpoch != this.transportEpoch) return false
            if (callEpoch != null && callEpoch != 0L && this.callEpoch != 0L && callEpoch != this.callEpoch) {
                return false
            }
            return true
        }

        fun acceptsProbe(
            sessionEpoch: Long,
            networkEpoch: Long?,
            inheritedProbeSessionEpoch: Long?,
        ): Boolean {
            if (userStop) return false
            if (networkEpoch != null && networkEpoch != this.networkEpoch) return false
            if (sessionEpoch == this.sessionEpoch) return true
            // Only the in-flight initial probe named by UserConnect is inherited.
            // Epoch 0 is a real idle epoch, not a wildcard for any old round.
            return inheritedProbeSessionEpoch != null &&
                sessionEpoch == inheritedProbeSessionEpoch
        }

    val allowsWatchdogRestart: Boolean
        get() = !userStop && netOpsAllowed && !recoveryInFlight
}

enum class RecoveryPhase {
    Idle,
    WaitingForNetwork,
    NetworkSuspended,
    CaptivePortal,
    Probing,
    ConnectingDirect,
    ConnectingBypass,
    SwitchingToWifi,
    ReturningToMobile,
    Backoff,
    NeedsUserAction,
    Connected,
}

/**
 * What the single recovery timer is counting down to. A periodic Direct
 * re-check must not be mistaken for a failure backoff: a transport that dies
 * while the re-check is armed has to retry on its own budget.
 */
enum class PendingTimer {
    None,
    Backoff,
    Reeval,
}

data class RecoveryState(
    val phase: RecoveryPhase = RecoveryPhase.Idle,
    val failureIndex: Int = 0,
    val nextRetryAtElapsedMs: Long? = null,
    val pendingTimer: PendingTimer = PendingTimer.None,
    val inFlight: Boolean = false,
    val callOpInFlight: Boolean = false,
    val permit: RecoveryPermit = RecoveryPermit(),
    /**
     * In-flight initial probe started on this session epoch. UserConnect
     * bumps [sessionEpoch]; ProbeFinished for that named probe may still be
     * accepted. Null means no inherit — including when the previous epoch
     * was 0.
     */
    val inheritedProbeSessionEpoch: Long? = null,
) {
    /** Deadline of a failure backoff; a pending re-check does not gate retries. */
    val backoffDueAtElapsedMs: Long?
        get() = nextRetryAtElapsedMs?.takeIf { pendingTimer == PendingTimer.Backoff }
}

data class ReachabilityEvidence(
    val networkKey: NetworkKey? = null,
    val profileId: String? = null,
    val measuredAtElapsedMs: Long = 0L,
    /** Last completed round, including Ignore / cancelled. */
    val observedAtElapsedMs: Long = 0L,
    /** Last Positive / WeakPositive / Open that may change the score. */
    val usableAtElapsedMs: Long = 0L,
    /** Last Positive (strong whitelist confirmation). Weak rounds must not refresh this. */
    val strongAtElapsedMs: Long = 0L,
    /** Last Open sample with ordinary-provider success. */
    val ordinaryOpenAtElapsedMs: Long = 0L,
    val yandex: CheckOutcome = CheckOutcome.NotRun,
    val bigtech: CheckOutcome = CheckOutcome.NotRun,
    val google: CheckOutcome = CheckOutcome.NotRun,
    /** Russian control service (vk.com) — also tells whether Bypass could work at all. */
    val ruService: CheckOutcome = CheckOutcome.NotRun,
    val provision: CheckOutcome = CheckOutcome.NotRun,
    val restriction: RestrictionHint = RestrictionHint.Unknown,
    /** 0–100 operator-whitelist confidence; persists across a flaky probe. */
    val whitelistScorePercent: Int = 0,
    val captive: Boolean = false,
    val ttlUntilElapsedMs: Long = 0L,
    /** Strong confirmation expiry; independent of a later weak/Ignore round. */
    val strongUntilElapsedMs: Long = 0L,
    val seriesCount: Int = 1,
    /** Completed diagnostic rounds for this network/profile (schedule, not БС confidence). */
    val completedSeries: Int = 0,
    /** Consecutive Ignore / incomplete rounds in this restriction scope. */
    val unknownStreak: Int = 0,
    /** Last accepted sample in this scope; drives the diagnostic schedule. */
    val lastSample: RestrictionSample = RestrictionSample.Ignore,
    val bindHandle: Long? = null,
    val routeReason: String = "unverified",
    val restrictionReason: String? = null,
    val seriesId: String = "",
    /** Immutable radio the sample was taken on. Rebind must not overwrite this. */
    val originNetworkKey: NetworkKey? = null,
) {
    fun measurementOrigin(): NetworkKey? = originNetworkKey ?: networkKey

    fun originMatches(key: NetworkKey?, profileId: String?): Boolean {
        if (this.profileId != null && profileId != null && this.profileId != profileId) {
            return false
        }
        if (key == null) return true
        val bound = networkKey
        if (bound != null && bound.samePhysicalNetwork(key) && bound.sameCarrier(key)) {
            return true
        }
        return whitelistOriginAllowsBind(measurementOrigin(), key)
    }

    fun usableUntilElapsedMs(): Long =
        if (ttlUntilElapsedMs > 0L) ttlUntilElapsedMs
        else if (usableAtElapsedMs > 0L) {
            usableAtElapsedMs + RecoverySettings.PROBE_RESTRICTION_TTL_MS
        } else {
            0L
        }

    fun strongUntil(): Long =
        if (strongUntilElapsedMs > 0L) strongUntilElapsedMs
        else if (strongAtElapsedMs > 0L) {
            strongAtElapsedMs + RecoverySettings.PROBE_RESTRICTION_TTL_MS
        } else {
            0L
        }

    fun usableAt(elapsedMs: Long, key: NetworkKey?, profileId: String?): Boolean {
        if (!originMatches(key, profileId)) return false
        return !RecoverySettings.evidenceExpired(elapsedMs, usableUntilElapsedMs())
    }

    fun hasFreshStrong(elapsedMs: Long, key: NetworkKey?, profileId: String?): Boolean {
        if (key == null) return false
        if (!originMatches(key, profileId)) return false
        if (!originAllowsStrongConfirmation(key)) return false
        return !RecoverySettings.evidenceExpired(elapsedMs, strongUntil())
    }

    /**
     * Unknown-origin measurements must not confirm a whitelist on another
     * physical network. A failed live carrier read on the same radio still
     * keeps a known measurement.
     */
    fun originAllowsStrongConfirmation(key: NetworkKey?): Boolean =
        whitelistOriginAllowsBind(measurementOrigin(), key)

    fun restrictionAt(elapsedMs: Long, key: NetworkKey?, profileId: String?): RestrictionHint {
        if (hasFreshStrong(elapsedMs, key, profileId) &&
            whitelistScorePercent >= RecoverySettings.WHITELIST_ENTER_PERCENT
        ) {
            return RestrictionHint.Confirmed
        }
        if (usableAt(elapsedMs, key, profileId)) return restriction
        return RestrictionHint.Unknown
    }
}

data class ConnectionSnapshot(
    val intent: UserConnectionIntent = UserConnectionIntent(),
    val underlay: UnderlaySnapshot = UnderlaySnapshot(),
    val evidence: ReachabilityEvidence? = null,
    /** Last cellular БС probe, kept while Wi‑Fi is the default route. */
    val cellularEvidence: ReachabilityEvidence? = null,
    val call: CallSessionState = CallSessionState(),
    val activePath: VpnPath? = null,
    val transport: TransportLifecycle = TransportLifecycle.Stopped,
    val parkedRawAlive: Boolean = false,
    val recovery: RecoveryState = RecoveryState(),
    val sessionEpoch: Long = 0L,
    val networkEpoch: Long = 0L,
    val transportEpoch: Long = 0L,
    val wifiFailStreak: Int = 0,
    val wifiStableHits: Int = 0,
    /** When the current Wi‑Fi/Ethernet underlay became usable; 0 when unknown. */
    val wifiUsableSinceMs: Long = 0L,
    /** Direct re-checks that failed on the current underlay; widens the next gap. */
    val directReevalFailures: Int = 0,
    /** The Direct attempt in flight displaced a live Bypass, parked or not. */
    val directRecheckFromBypass: Boolean = false,
    val directNegative: DirectNegativeEvidence? = null,
    val lastConfirmedPath: VpnPath? = null,
    /** Underlay Direct was last PathConfirmed on; Wi‑Fi proof is not LTE proof. */
    val lastConfirmedNetworkKey: NetworkKey? = null,
    val pathReadiness: PathReadiness = PathReadiness.None,
    val ui: ConnectionUiModel = ConnectionUiModel(),
    /** Bounded memory of probe seriesIds in this restriction scope (newest last). */
    val seenProbeSeriesIds: List<String> = emptyList(),
    /** seriesIds that already started a transport so a duplicate/final cannot start again. */
    val startedProbeSeriesIds: List<String> = emptyList(),
) {
    val directFailedOnNetwork: NetworkKey? get() = directNegative?.key
}

fun ConnectionSnapshot.holdingSession(): Boolean =
    intent.wantsConnected && !recovery.permit.userStop
