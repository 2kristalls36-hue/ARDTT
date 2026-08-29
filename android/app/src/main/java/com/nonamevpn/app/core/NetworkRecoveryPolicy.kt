package com.nonamevpn.app.core

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
    /** Wait after a network event before attempting soft reconnect. */
    val networkSettleDelayMs: Long,
    /** Minimum spacing between soft reconnects. */
    val reconnectMinIntervalMs: Long,
    /** Delay between killProcess and start during soft restart. */
    val processRestartDelayMs: Long,
)

fun transportRecoveryPolicy(): TransportRecoveryPolicy =
    TransportRecoveryPolicy(
        // Fast enough for Wi‑Fi↔LTE; still lets DHCP/VALIDATED settle.
        networkSettleDelayMs = 1_000L,
        reconnectMinIntervalMs = 12_000L,
        processRestartDelayMs = 400L,
    )

fun classifyValidatedNetworkTransition(
    previousNetworkId: Long?,
    currentNetworkId: Long,
    previousNetworkWasLost: Boolean,
): ValidatedNetworkTransition = when {
    previousNetworkWasLost -> ValidatedNetworkTransition.HANDOVER
    previousNetworkId == null -> ValidatedNetworkTransition.INITIAL
    previousNetworkId == currentNetworkId -> ValidatedNetworkTransition.UNCHANGED
    else -> ValidatedNetworkTransition.HANDOVER
}

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
    graceAfterStartMs: Long = 5_000L,
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
): Long =
    (minIntervalMs + softRestartCount.coerceAtMost(4) * 8_000L).coerceAtMost(90_000L)

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
const val ZERO_WORKERS_GRACE_MS = 20_000L

/** Soft-restart if backend process/job is dead this long. */
const val PROCESS_DEAD_GRACE_MS = 20_000L

/**
 * Path B: workers > 0 but traffic counter flat this long after a handoff
 * (zombie TCP sockets until broken-pipe).
 */
const val TRAFFIC_STALL_AFTER_HANDOFF_MS = 18_000L

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
 */
sealed class NetworkHandoverDecision {
    /** Spurious underlay event (VPN bind / grace) — do not restart. */
    data object NoAction : NetworkHandoverDecision()
    data object SoftRestartSamePath : NetworkHandoverDecision()
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
 * Bypass → Direct only after this many consecutive “VPS IP up” probes.
 * One 163 ms `/health` blip must not yank a working Bypass.
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

fun updateProbeStreak(previous: ProbeStreak, probedPath: VpnPath?): ProbeStreak {
    if (probedPath == null) return ProbeStreak()
    if (probedPath == previous.path) return ProbeStreak(probedPath, previous.count + 1)
    return ProbeStreak(probedPath, 1)
}

/**
 * Auto handover from 77.88.8.8 + VPS IP:
 * - Direct → Bypass when Yandex DNS is up and the VPS IP is not (whitelist),
 *   only after [HANDOVER_DIRECT_TO_BYPASS_STREAK] consecutive hits. The first
 *   miss rebinds Direct instead of switching.
 * - Bypass → Direct only after [HANDOVER_BYPASS_TO_DIRECT_STREAK] consecutive
 *   VPS-IP successes (open Wi‑Fi), not a single flaky TCP.
 * - A confirmed underlay change (lost network / new id) always rebinds the
 *   current path when we are not switching — sockets stay glued to the old
 *   Wi‑Fi otherwise.
 * - VPN-bind ghosts during [HANDOVER_IGNORE_GRACE_MS] stay [NoAction] unless
 *   [underlayChanged] is true.
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
): NetworkHandoverDecision {
    val inGrace = sessionAgeMs in 0 until HANDOVER_IGNORE_GRACE_MS
    if (inGrace && !underlayChanged) {
        return NetworkHandoverDecision.NoAction
    }
    if (pathMode != ConnPathMode.Auto) {
        return NetworkHandoverDecision.SoftRestartSamePath
    }
    val vpsUp = underlayVpsReachable || probedPath == VpnPath.Direct
    if (currentPath == VpnPath.Direct) {
        if (probedPath == VpnPath.Bypass && bypassAllowed && !vpsUp &&
            sameProbeStreak >= HANDOVER_DIRECT_TO_BYPASS_STREAK
        ) {
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
    if (vpsUp && sameProbeStreak >= HANDOVER_BYPASS_TO_DIRECT_STREAK) {
        return NetworkHandoverDecision.SwitchPath(VpnPath.Direct)
    }
    if (underlayChanged) {
        return NetworkHandoverDecision.SoftRestartSamePath
    }
    if (vpsUp) {
        return NetworkHandoverDecision.NoAction
    }
    return NetworkHandoverDecision.SoftRestartSamePath
}

fun shouldReconnectTunnelAfterWake(
    activeWorkers: Int,
    hasFreshStatsSinceWake: Boolean,
    bypassPath: Boolean,
    backendAlive: Boolean,
): Boolean {
    if (!bypassPath) return !backendAlive
    if (hasFreshStatsSinceWake) return false
    return activeWorkers <= 0 || !backendAlive
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
