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

data class CallSessionState(
    val hashPresent: Boolean = false,
    val validity: CallValidity = CallValidity.Valid,
    val profileId: String? = null,
    val createdThisGeneration: Boolean = false,
    val identityToken: String = "",
    val callEpoch: Long = 0L,
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
        if (key == null || !this.key.samePhysicalNetwork(key)) return false
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
) {
    /** Deadline of a failure backoff; a pending re-check does not gate retries. */
    val backoffDueAtElapsedMs: Long?
        get() = nextRetryAtElapsedMs?.takeIf { pendingTimer == PendingTimer.Backoff }
}

data class ReachabilityEvidence(
    val networkKey: NetworkKey? = null,
    val profileId: String? = null,
    val measuredAtElapsedMs: Long = 0L,
    val yandex: CheckOutcome = CheckOutcome.NotRun,
    val bigtech: CheckOutcome = CheckOutcome.NotRun,
    val google: CheckOutcome = CheckOutcome.NotRun,
    val provision: CheckOutcome = CheckOutcome.NotRun,
    val restriction: RestrictionHint = RestrictionHint.Unknown,
    /** 0–100 operator-whitelist confidence; persists across a flaky probe. */
    val whitelistScorePercent: Int = 0,
    val captive: Boolean = false,
    val ttlUntilElapsedMs: Long = 0L,
    val seriesCount: Int = 1,
    /** Completed diagnostic rounds for this network/profile (schedule, not БС confidence). */
    val completedSeries: Int = 0,
    val bindHandle: Long? = null,
    val routeReason: String = "unverified",
    val restrictionReason: String? = null,
    val seriesId: String = "",
) {
    fun usableAt(elapsedMs: Long, key: NetworkKey?, profileId: String?): Boolean {
        if (ttlUntilElapsedMs > 0L && elapsedMs > ttlUntilElapsedMs) return false
        if (networkKey != null && key != null && !networkKey.samePhysicalNetwork(key)) return false
        if (networkKey != null && key != null && !networkKey.sameCarrier(key)) return false
        if (this.profileId != null && profileId != null && this.profileId != profileId) return false
        return true
    }

    fun restrictionAt(elapsedMs: Long, key: NetworkKey?, profileId: String?): RestrictionHint {
        if (!usableAt(elapsedMs, key, profileId)) return RestrictionHint.Unknown
        return restriction
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
    /** Direct re-checks that failed on the current underlay; widens the next gap. */
    val directReevalFailures: Int = 0,
    val directNegative: DirectNegativeEvidence? = null,
    val lastConfirmedPath: VpnPath? = null,
    /** Underlay Direct was last PathConfirmed on; Wi‑Fi proof is not LTE proof. */
    val lastConfirmedNetworkKey: NetworkKey? = null,
    val pathReadiness: PathReadiness = PathReadiness.None,
    val ui: ConnectionUiModel = ConnectionUiModel(),
) {
    val directFailedOnNetwork: NetworkKey? get() = directNegative?.key
}

fun ConnectionSnapshot.holdingSession(): Boolean =
    intent.wantsConnected && !recovery.permit.userStop
