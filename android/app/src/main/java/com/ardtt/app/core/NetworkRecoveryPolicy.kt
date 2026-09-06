package com.ardtt.app.core

/**
 * Pure recovery policy helpers adapted from WDTT-Plus
 * ([TunnelService] / [TunnelStartConfig] AMNEZIA_STYLE_RECOVERY).
 *
 * Soft reconnect = restart transport (backend / libclient) without tearing down
 * the VpnService session intent from the user.
 */

enum class ValidatedNetworkTransition {
    INITIAL,
    UNCHANGED,
    HANDOVER,
}

data class TransportRecoveryPolicy(
    /** Extra wait after VALIDATED before attempting soft reconnect. */
    val networkSettleDelayMs: Long,
    /** Minimum spacing between soft reconnects (Path B: do not spam VK). */
    val reconnectMinIntervalMs: Long,
    /** Delay between killProcess and start during soft restart. */
    val processRestartDelayMs: Long,
)

/**
 * WDTT-Plus waits ~15s / 2min because it has one VK TURN path.
 * We keep RAW + Amnezia Direct: Bypass still must not join VK on a half-up
 * underlay, Direct must rebind AWG sooner than that.
 */
fun transportRecoveryPolicy(path: VpnPath = VpnPath.Bypass): TransportRecoveryPolicy =
    when (path) {
        VpnPath.Bypass -> TransportRecoveryPolicy(
            networkSettleDelayMs = BYPASS_NETWORK_SETTLE_MS,
            reconnectMinIntervalMs = BYPASS_RECONNECT_MIN_INTERVAL_MS,
            processRestartDelayMs = 150L,
        )
        VpnPath.Direct -> TransportRecoveryPolicy(
            networkSettleDelayMs = DIRECT_NETWORK_SETTLE_MS,
            reconnectMinIntervalMs = DIRECT_RECONNECT_MIN_INTERVAL_MS,
            processRestartDelayMs = 150L,
        )
    }

/** Bypass: settle after Android marks the new underlay VALIDATED. */
const val BYPASS_NETWORK_SETTLE_MS = 3_000L

/**
 * After VALIDATED wait timed out, sockets are already broken-pipe (SIM swap).
 * Do not add another 3s — join VK on the replacement underlay immediately.
 */
const val BYPASS_UNVALIDATED_SETTLE_MS = 400L

fun extraNetworkSettleDelayMs(
    path: VpnPath,
    validatedPresent: Boolean,
    skipValidatedWait: Boolean = false,
): Long {
    if (skipValidatedWait || (path == VpnPath.Bypass && !validatedPresent)) {
        return BYPASS_UNVALIDATED_SETTLE_MS
    }
    return transportRecoveryPolicy(path).networkSettleDelayMs
}

/** Bypass: VK join cooldown — Wi‑Fi↔LTE must not enqueue a second anonym chain. */
const val BYPASS_RECONNECT_MIN_INTERVAL_MS = 20_000L

/** Direct: AWG UDP sockets need a quicker rebind than Path B. */
const val DIRECT_NETWORK_SETTLE_MS = 1_200L

const val DIRECT_RECONNECT_MIN_INTERVAL_MS = 6_000L

/** Give LTE/Wi‑Fi this long to become VALIDATED before probing / joining VK. */
const val VALIDATED_WAIT_TIMEOUT_MS = 12_000L

/**
 * When the replacement underlay is already tracked (Wi‑Fi→LTE, SIM swap)
 * do not sit the full [VALIDATED_WAIT_TIMEOUT_MS] — Android often never
 * marks LTE VALIDATED while the VPN is up.
 */
const val VALIDATED_WAIT_WHEN_UNDERLAY_PRESENT_MS = 2_500L

const val VALIDATED_WAIT_POLL_MS = 300L

fun validatedWaitTimeoutMs(
    replacementUnderlayPresent: Boolean,
    skipWait: Boolean = false,
): Long = when {
    skipWait -> 0L
    replacementUnderlayPresent -> VALIDATED_WAIT_WHEN_UNDERLAY_PRESENT_MS
    else -> VALIDATED_WAIT_TIMEOUT_MS
}

/**
 * qWDTT reconnects RAW without a VPS probe. On this phone LTE often never
 * becomes VALIDATED while the VPN is up — waiting 2.5s+ only extends the
 * blackhole. Skip that wait on cellular. Do **not** skip on Wi‑Fi: Bypass
 * still has traffic on LTE while home Wi‑Fi validates, and we need Direct.
 */
fun shouldSkipValidatedWait(
    path: VpnPath,
    underlayKind: UnderlayKind,
): Boolean = underlayKind == UnderlayKind.Cellular

fun classifyValidatedNetworkTransition(
    previousNetworkId: Long?,
    currentNetworkId: Long,
    previousNetworkWasLost: Boolean,
    /** Dual-SIM: default data subscription changed (even if Network handle looks the same). */
    dataSubscriptionChanged: Boolean = false,
): ValidatedNetworkTransition = when {
    dataSubscriptionChanged -> ValidatedNetworkTransition.HANDOVER
    previousNetworkWasLost -> ValidatedNetworkTransition.HANDOVER
    previousNetworkId == null -> ValidatedNetworkTransition.INITIAL
    previousNetworkId == currentNetworkId -> ValidatedNetworkTransition.UNCHANGED
    else -> ValidatedNetworkTransition.HANDOVER
}

/** WIFI→LTE→LTE: a second switch while probe/restart is busy must be queued, not dropped. */
fun shouldDeferHandoverProbe(
    handoverProbeInProgress: Boolean,
    softRestartInProgress: Boolean,
): Boolean = handoverProbeInProgress || softRestartInProgress

/**
 * After Wi‑Fi→LTE the validated id is often cleared and the new underlay never
 * gets a HANDOVER event — only INITIAL. Once the VPN session is past [graceMs],
 * treat that INITIAL as a path re-check trigger.
 */
fun shouldTreatInitialValidatedAsHandover(
    tunnelRunning: Boolean,
    userStopRequested: Boolean,
    softRestartInProgress: Boolean,
    sessionStartedAtMs: Long,
    nowMs: Long,
    graceAfterStartMs: Long = HANDOVER_IGNORE_GRACE_MS,
): Boolean {
    if (!tunnelRunning || userStopRequested || softRestartInProgress) return false
    if (sessionStartedAtMs <= 0L) return false
    return nowMs - sessionStartedAtMs >= graceAfterStartMs
}

fun shouldScheduleAvailableNetworkHandover(
    previousNetworkWasLost: Boolean,
    availableRealNetworkCount: Int,
): Boolean = previousNetworkWasLost && availableRealNetworkCount > 0

fun shouldTrackUnderlyingNetworkLoss(
    tunnelRunning: Boolean,
    userStopRequested: Boolean,
): Boolean = tunnelRunning && !userStopRequested

fun shouldStartUnderlyingNetworkCheck(
    checkPending: Boolean,
    currentJobActive: Boolean,
): Boolean = !checkPending || !currentJobActive

fun shouldRunUnderlyingNetworkReconnect(
    tunnelRunning: Boolean,
    userStopRequested: Boolean,
    softRestartInProgress: Boolean,
    realNetworkAvailable: Boolean,
): Boolean =
    tunnelRunning &&
        !userStopRequested &&
        !softRestartInProgress &&
        realNetworkAvailable

fun softRestartCooldownMs(
    minIntervalMs: Long,
    softRestartCount: Int,
    maxMs: Long = SOFT_RESTART_COOLDOWN_MAX_MS,
): Long =
    (minIntervalMs + softRestartCount.coerceAtMost(3) * 2_000L).coerceAtMost(maxMs)

const val SOFT_RESTART_COOLDOWN_MAX_MS = 45_000L

/**
 * Keep waiting until Android validates the underlay, or [timeoutMs] elapses.
 * Joining VK / rebinding AWG on a not-yet-VALIDATED LTE is what produced
 * stale anonym tokens and blackholed Direct.
 */
fun shouldKeepWaitingForValidated(
    validatedPresent: Boolean,
    waitedMs: Long,
    timeoutMs: Long = VALIDATED_WAIT_TIMEOUT_MS,
): Boolean = !validatedPresent && waitedMs < timeoutMs

/** Latest network-event timestamp wins (traffic must be on the new underlay). */
fun updatedUnderlyingNetworkEvidenceSince(
    currentEvidenceSinceMs: Long,
    networkEventAtMs: Long,
): Long = maxOf(currentEvidenceSinceMs, networkEventAtMs)

/**
 * Plus: skip soft-restart when inbound traffic already flows after the event.
 * That is safe for Path B only after Android VALIDATED the **new** underlay.
 * Leftover TURN counters after Wi‑Fi→LTE / SIM swap are not proof the sockets
 * rebound — they stay glued to the old cell IP until we restart.
 * Path A AWG UDP: never skip.
 */
fun shouldSkipHandoverRestartIfTrafficFresh(
    bypassTrafficFresh: Boolean,
    directTrafficFresh: Boolean,
    path: VpnPath,
    validatedPresent: Boolean = true,
    underlayKind: UnderlayKind = UnderlayKind.Other,
): Boolean = when (path) {
    // Leftover TURN on LTE is not a reason to stay on Bypass after Wi‑Fi is up.
    VpnPath.Bypass ->
        validatedPresent && bypassTrafficFresh && underlayKind != UnderlayKind.Wifi
    VpnPath.Direct -> false
}

fun shouldAttemptSoftRestartNow(
    nowMs: Long,
    lastSoftRestartAtMs: Long,
    minIntervalMs: Long,
    softRestartCount: Int,
    force: Boolean,
): Boolean {
    if (force) return true
    val cooldown = softRestartCooldownMs(minIntervalMs, softRestartCount)
    return nowMs - lastSoftRestartAtMs >= cooldown
}

/** Delay after SCREEN_ON before deciding whether to soft-restart. */
const val WAKE_RESCUE_GRACE_MS = 25_000L

/** Suppress zero-worker watchdog briefly after wake / soft restart. */
const val WAKE_RECOVERY_GRACE_MS = 35_000L

/** Path B: soft-restart if Активных stays 0 this long while screen is on. */
const val ZERO_WORKERS_GRACE_MS = 8_000L

/** Soft-restart if backend process/job is dead this long. */
const val PROCESS_DEAD_GRACE_MS = 20_000L

/**
 * Path B: workers > 0 but traffic counter flat this long after a handoff.
 * Must be well above go_client МБ tick idle (user not browsing) or a live
 * Bypass is torn down and re-probed as Direct.
 */
const val TRAFFIC_STALL_AFTER_HANDOFF_MS = 30_000L

/**
 * Bypass started on a half-up LTE often has 9 TURN workers and only handshake
 * bytes (~0.1 МБ). Rebind sooner than [TRAFFIC_STALL_AFTER_HANDOFF_MS].
 */
const val BYPASS_HANDSHAKE_STALL_MS = 18_000L

/** Stay in handshake-stall mode while total traffic is at most this (KB). */
const val BYPASS_HANDSHAKE_ONLY_MAX_KB = 200L

/** Same stall detection without a recent handoff (slower threshold). */
const val TRAFFIC_STALL_IDLE_MS = 45_000L

/** How long after a network handoff we treat stalls as urgent. */
const val HANDOFF_STALL_WINDOW_MS = 120_000L

const val WATCHDOG_POLL_MS = 3_000L

const val TRUSTED_WIFI_ENTER_DELAY_MS = 2_000L
const val TRUSTED_WIFI_EXIT_DELAY_MS = 5_000L
// SSID wait/retry live in TrustedWifi.kt (TRUSTED_WIFI_SSID_WAIT_MS).

/**
 * After Wi‑Fi↔LTE settle, Auto mode may switch Direct↔Bypass when underlay
 * probe disagrees with the current path. Forced Direct/Bypass only soft-restarts.
 * NoNetwork/Captive → hold: do not restart into a dead SIM gap.
 */
sealed class NetworkHandoverDecision {
    /** Spurious underlay event (VPN bind / grace) — do not restart. */
    data object NoAction : NetworkHandoverDecision()
    data object SoftRestartSamePath : NetworkHandoverDecision()
    /** Probe found no usable underlay — keep the current path until a real network is back. */
    data object HoldWaitForNetwork : NetworkHandoverDecision()
    data class SwitchPath(val path: VpnPath) : NetworkHandoverDecision()
}

/**
 * Ignore Android "network changed" for this long after the tunnel starts.
 * Bringing up VpnService looks like an underlay handover and must not
 * tear down a working Direct session.
 *
 * Real underlay loss (Wi‑Fi→LTE, SIM swap) skips this grace: sockets must
 * rebind even if Connect was a few seconds ago.
 */
const val HANDOVER_IGNORE_GRACE_MS = 12_000L

/**
 * Direct → Bypass only after this many consecutive “VPS IP down, 77.88.8.8 up”
 * probes. A single TCP timeout while LTE attaches is not a whitelist.
 */
const val HANDOVER_DIRECT_TO_BYPASS_STREAK = 2

/**
 * Bypass → Direct used to require this many consecutive “VPS IP up” probes.
 * Stall recovery on a whitelist LTE (TCP :9100 or 1.1.1.1 SYN up, AWG UDP dead) hit this
 * without an underlay change and yanked a working Bypass. Upgrade now only
 * on a confirmed underlay change — the constant remains for tests / docs.
 */
const val HANDOVER_BYPASS_TO_DIRECT_STREAK = 2

/** True when Android lost the previous underlay or reported a new network id. */
fun isConfirmedUnderlayChange(
    previousNetworkWasLost: Boolean,
    previousNetworkId: Long?,
): Boolean = previousNetworkWasLost || previousNetworkId != null

data class ProbeStreak(
    val path: VpnPath? = null,
    val count: Int = 0,
)

/** Real underlay transport. Direct UDP works on home Wi‑Fi; both SIMs need Bypass. */
enum class UnderlayKind {
    Wifi,
    Cellular,
    Other,
}

fun classifyUnderlayKind(wifi: Boolean, cellular: Boolean): UnderlayKind = when {
    wifi -> UnderlayKind.Wifi
    cellular -> UnderlayKind.Cellular
    else -> UnderlayKind.Other
}

fun updateProbeStreak(previous: ProbeStreak, probedPath: VpnPath?): ProbeStreak {
    if (probedPath == null) return ProbeStreak()
    if (probedPath == previous.path) return ProbeStreak(probedPath, previous.count + 1)
    return ProbeStreak(probedPath, 1)
}

/**
 * Auto handover:
 * - Direct → Bypass when the probe says Bypass (whitelist or VPS down) and the
 *   underlay changed, or after [HANDOVER_DIRECT_TO_BYPASS_STREAK] hits.
 *   TCP :9100 is not AmneziaWG UDP — do not keep Direct just because provision
 *   answered. Open LTE with Cloudflare up still stays Direct.
 *   **Wi‑Fi Auto never takes Bypass** (probe kept for cellular).
 * - Bypass → Direct on Wi‑Fi Auto whenever Direct is allowed (no VPS probe).
 *   A cellular underlay change must not yank a working Bypass just because
 *   TCP :9100 answered (AWG UDP may still be dead).
 * - Forced Direct/Bypass only rebind when the underlay actually changed.
 */
fun decideNetworkHandoverAction(
    pathMode: ConnPathMode,
    currentPath: VpnPath,
    probedPath: VpnPath?,
    bypassAllowed: Boolean,
    sessionAgeMs: Long = Long.MAX_VALUE,
    currentPathHealthy: Boolean = false,
    underlayVpsReachable: Boolean = probedPath == VpnPath.Direct,
    sameProbeStreak: Int = 1,
    underlayChanged: Boolean = false,
    allowBypassToDirect: Boolean = true,
    directFailedOnCurrentUnderlay: Boolean = false,
    underlayKind: UnderlayKind = UnderlayKind.Other,
): NetworkHandoverDecision {
    val inGrace = sessionAgeMs in 0 until HANDOVER_IGNORE_GRACE_MS
    if (inGrace && !underlayChanged) {
        return NetworkHandoverDecision.NoAction
    }
    if (probedPath == null) {
        return NetworkHandoverDecision.HoldWaitForNetwork
    }
    if (pathMode != ConnPathMode.Auto) {
        return if (underlayChanged) {
            NetworkHandoverDecision.SoftRestartSamePath
        } else {
            NetworkHandoverDecision.NoAction
        }
    }
    if (underlayKind == UnderlayKind.Wifi) {
        if (currentPath == VpnPath.Direct) {
            return if (underlayChanged) {
                NetworkHandoverDecision.SoftRestartSamePath
            } else {
                NetworkHandoverDecision.NoAction
            }
        }
        val canUpgradeToDirect = allowBypassToDirect && !directFailedOnCurrentUnderlay
        if (canUpgradeToDirect) {
            return NetworkHandoverDecision.SwitchPath(VpnPath.Direct)
        }
        if (underlayChanged) {
            return NetworkHandoverDecision.SoftRestartSamePath
        }
        return NetworkHandoverDecision.NoAction
    }
    val vpsUp = underlayVpsReachable || probedPath == VpnPath.Direct
    if (currentPath == VpnPath.Direct) {
        val needBypass = probedPath == VpnPath.Bypass && bypassAllowed
        if (needBypass && (underlayChanged || sameProbeStreak >= HANDOVER_DIRECT_TO_BYPASS_STREAK)) {
            return NetworkHandoverDecision.SwitchPath(VpnPath.Bypass)
        }
        if (vpsUp) {
            return NetworkHandoverDecision.SoftRestartSamePath
        }
        if (probedPath == VpnPath.Bypass) {
            return NetworkHandoverDecision.SoftRestartSamePath
        }
        return if (underlayChanged || !currentPathHealthy) {
            NetworkHandoverDecision.SoftRestartSamePath
        } else {
            NetworkHandoverDecision.NoAction
        }
    }
    val canUpgradeToDirect = allowBypassToDirect &&
        !directFailedOnCurrentUnderlay &&
        underlayKind == UnderlayKind.Wifi
    // Home Wi‑Fi: switch now. Do not wait for :9100 — leftover LTE Bypass
    // still has TURN traffic and would otherwise keep Path B.
    if (canUpgradeToDirect && underlayChanged) {
        return NetworkHandoverDecision.SwitchPath(VpnPath.Direct)
    }
    if (underlayChanged) {
        return NetworkHandoverDecision.SoftRestartSamePath
    }
    // Stable operator-whitelist Bypass: keep the VK/TURN call. A same-path
    // probe or a ghost DirectOk without an underlay change must not rejoin.
    return NetworkHandoverDecision.NoAction
}

/** Keep libclient/TURN (the VK call) after Bypass→Direct so LTE return can redial the same hash. */
const val WARM_CALL_HOLD_MS = 5 * 60 * 1000L

fun shouldParkBypassCall(from: VpnPath, to: VpnPath): Boolean =
    from == VpnPath.Bypass && to == VpnPath.Direct

/** VALIDATED or actually-connected Wi‑Fi wins over LTE even if SSID APIs are blank. */
fun preferWifiUnderlayKind(
    hasValidatedWifi: Boolean,
    pickBestKind: UnderlayKind,
    wifiConnected: Boolean = false,
): UnderlayKind = if (hasValidatedWifi || wifiConnected) UnderlayKind.Wifi else pickBestKind

fun shouldReconnectTunnelAfterWake(
    activeWorkers: Int,
    hasFreshStatsSinceWake: Boolean,
    bypassPath: Boolean,
    backendAlive: Boolean,
    directEgressOk: Boolean = true,
): Boolean {
    if (!bypassPath) {
        // Direct: AWG process up is not egress. Need rx/handshake after wake.
        return !backendAlive || !directEgressOk
    }
    if (hasFreshStatsSinceWake) return false
    return activeWorkers <= 0 || !backendAlive
}

/**
 * Direct Connected with no TUN rx after grace: Auto+hash on cellular → Bypass.
 * Auto on Wi‑Fi and forced Direct stop so the phone is not a blackhole.
 */
sealed class DeadDirectDecision {
    data object KeepWatching : DeadDirectDecision()
    data object SwitchToBypass : DeadDirectDecision()
    data object FailSession : DeadDirectDecision()
}

/** Ignore Direct “no rx” during AWG handshake after a cold start. */
const val DEAD_DIRECT_START_GRACE_MS = 3_000L

/** Direct has been up this long since a cold start with no inbound bytes. */
const val DEAD_DIRECT_NO_RX_MS = 4_000L

/** After Wi‑Fi→LTE rebind, fail Direct faster — handshake already had a chance. */
const val DEAD_DIRECT_NO_RX_AFTER_HANDOFF_MS = 3_000L

fun shouldTreatDirectAsDeadNoRx(
    nowMs: Long,
    sessionStartedAtMs: Long,
    lastHandoffAtMs: Long,
    hasFreshRxSinceAnchor: Boolean,
    startGraceMs: Long = DEAD_DIRECT_START_GRACE_MS,
    noRxMs: Long = DEAD_DIRECT_NO_RX_MS,
    noRxAfterHandoffMs: Long = DEAD_DIRECT_NO_RX_AFTER_HANDOFF_MS,
): Boolean {
    if (sessionStartedAtMs <= 0L) return false
    val afterHandoff = lastHandoffAtMs > sessionStartedAtMs
    if (!afterHandoff && nowMs - sessionStartedAtMs < startGraceMs) return false
    val anchor = maxOf(sessionStartedAtMs, lastHandoffAtMs)
    val requiredNoRx = if (afterHandoff) noRxAfterHandoffMs else noRxMs
    if (nowMs - anchor < requiredNoRx) return false
    return !hasFreshRxSinceAnchor
}

fun decideDeadDirectAction(
    pathMode: ConnPathMode,
    bypassAllowed: Boolean,
    underlayKind: UnderlayKind = UnderlayKind.Other,
): DeadDirectDecision = when {
    autoUsesDirectOnWifi(pathMode, underlayKind) -> DeadDirectDecision.FailSession
    pathMode == ConnPathMode.Auto && bypassAllowed -> DeadDirectDecision.SwitchToBypass
    else -> DeadDirectDecision.FailSession
}

fun shouldObserveTunnelHealth(
    deviceInteractive: Boolean,
    wakeRecoveryGraceActive: Boolean,
    trustedWifiWaiting: Boolean,
    softRestartInProgress: Boolean,
): Boolean =
    deviceInteractive &&
        !wakeRecoveryGraceActive &&
        !trustedWifiWaiting &&
        !softRestartInProgress

/** Direct blackhole after Wi‑Fi→LTE must be visible even during wake grace. */
fun shouldObserveDirectEgress(
    tunnelRunning: Boolean,
    userStopRequested: Boolean,
    softRestartInProgress: Boolean,
): Boolean = tunnelRunning && !userStopRequested && !softRestartInProgress

fun shouldSoftRestartForZeroWorkers(
    activeWorkers: Int,
    zeroSinceMs: Long,
    nowMs: Long,
    graceMs: Long = ZERO_WORKERS_GRACE_MS,
): Boolean = activeWorkers <= 0 && zeroSinceMs > 0L && nowMs - zeroSinceMs >= graceMs

fun shouldSoftRestartForTrafficStall(
    activeWorkers: Int,
    trafficBytes: Long,
    lastTrafficGrowthAtMs: Long,
    nowMs: Long,
    handoffAtMs: Long,
    afterHandoffGraceMs: Long = TRAFFIC_STALL_AFTER_HANDOFF_MS,
    idleGraceMs: Long = TRAFFIC_STALL_IDLE_MS,
    handoffWindowMs: Long = HANDOFF_STALL_WINDOW_MS,
): Boolean {
    if (activeWorkers <= 0) return false
    if (trafficBytes <= 0L || lastTrafficGrowthAtMs <= 0L) return false
    val stalledFor = nowMs - lastTrafficGrowthAtMs
    val inHandoffWindow = handoffAtMs > 0L && nowMs - handoffAtMs <= handoffWindowMs
    val grace = if (inHandoffWindow) afterHandoffGraceMs else idleGraceMs
    return stalledFor >= grace
}

/** Bypass up, but only TURN handshake — sockets glued to a not-yet-ready LTE. */
fun shouldSoftRestartForHandshakeStall(
    bypassPath: Boolean,
    activeWorkers: Int,
    trafficKb: Long,
    nowMs: Long,
    handoffAtMs: Long,
    graceMs: Long = BYPASS_HANDSHAKE_STALL_MS,
    maxHandshakeKb: Long = BYPASS_HANDSHAKE_ONLY_MAX_KB,
    handoffWindowMs: Long = HANDOFF_STALL_WINDOW_MS,
): Boolean {
    if (!bypassPath) return false
    if (activeWorkers <= 0) return false
    if (handoffAtMs <= 0L) return false
    val sinceHandoff = nowMs - handoffAtMs
    if (sinceHandoff < graceMs || sinceHandoff > handoffWindowMs) return false
    return trafficKb <= maxHandshakeKb
}
