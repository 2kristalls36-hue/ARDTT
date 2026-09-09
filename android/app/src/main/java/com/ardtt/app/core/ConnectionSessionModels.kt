package com.ardtt.app.core

data class UserConnectionIntent(
    val wantsConnected: Boolean = false,
    val mode: ConnPathMode = ConnPathMode.Auto,
    val profileId: String? = null,
    val hasCallHash: Boolean = false,
    val silentRecreate: Boolean = false,
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

data class DirectNegativeEvidence(
    val key: NetworkKey,
    val profileId: String? = null,
    val reason: String = "",
    val failedAtElapsedMs: Long = 0L,
    val retryAfterElapsedMs: Long = 0L,
) {
    fun stillBlocks(elapsedMs: Long, key: NetworkKey?, profileId: String?): Boolean {
        if (key == null || key != this.key) return false
        if (this.profileId != null && profileId != null && this.profileId != profileId) return false
        return elapsedMs < retryAfterElapsedMs
    }
}

data class RecoveryPermit(
    val sessionEpoch: Long = 0L,
    val networkEpoch: Long = 0L,
    val transportEpoch: Long = 0L,
    val netOpsAllowed: Boolean = false,
    val recoveryInFlight: Boolean = false,
    val callOpInFlight: Boolean = false,
    val userStop: Boolean = true,
) {
    fun accepts(sessionEpoch: Long, networkEpoch: Long? = null, transportEpoch: Long? = null): Boolean {
        if (userStop) return false
        if (sessionEpoch != this.sessionEpoch) return false
        if (networkEpoch != null && networkEpoch != this.networkEpoch) return false
        if (transportEpoch != null && transportEpoch != this.transportEpoch) return false
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

data class RecoveryState(
    val phase: RecoveryPhase = RecoveryPhase.Idle,
    val failureIndex: Int = 0,
    val nextRetryAtElapsedMs: Long? = null,
    val inFlight: Boolean = false,
    val callOpInFlight: Boolean = false,
    val permit: RecoveryPermit = RecoveryPermit(),
)

data class ReachabilityEvidence(
    val networkKey: NetworkKey? = null,
    val profileId: String? = null,
    val measuredAtElapsedMs: Long = 0L,
    val yandex: CheckOutcome = CheckOutcome.NotRun,
    val bigtech: CheckOutcome = CheckOutcome.NotRun,
    val provision: CheckOutcome = CheckOutcome.NotRun,
    val restriction: RestrictionHint = RestrictionHint.Unknown,
    val captive: Boolean = false,
    val ttlUntilElapsedMs: Long = 0L,
    val seriesCount: Int = 1,
    val bindHandle: Long? = null,
) {
    fun usableAt(elapsedMs: Long, key: NetworkKey?, profileId: String?): Boolean {
        if (elapsedMs > ttlUntilElapsedMs) return false
        if (key != networkKey) return false
        if (profileId != this.profileId) return false
        return true
    }
}

data class ConnectionSnapshot(
    val intent: UserConnectionIntent = UserConnectionIntent(),
    val underlay: UnderlaySnapshot = UnderlaySnapshot(),
    val evidence: ReachabilityEvidence? = null,
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
    val directNegative: DirectNegativeEvidence? = null,
    val lastConfirmedPath: VpnPath? = null,
    val ui: ConnectionUiModel = ConnectionUiModel(),
) {
    val directFailedOnNetwork: NetworkKey? get() = directNegative?.key
}

fun ConnectionSnapshot.holdingSession(): Boolean =
    intent.wantsConnected && !recovery.permit.userStop
