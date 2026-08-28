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

/**
 * After Wi‑Fi↔LTE settle, Auto mode may switch Direct↔Bypass when underlay
 * probe disagrees with the current path. Forced Direct/Bypass only soft-restarts.
 */
sealed class NetworkHandoverDecision {
    data object SoftRestartSamePath : NetworkHandoverDecision()
    data class SwitchPath(val path: VpnPath) : NetworkHandoverDecision()
}

fun decideNetworkHandoverAction(
    pathMode: ConnPathMode,
    currentPath: VpnPath,
    probedPath: VpnPath?,
    bypassAllowed: Boolean,
): NetworkHandoverDecision {
    if (pathMode != ConnPathMode.Auto) {
        return NetworkHandoverDecision.SoftRestartSamePath
    }
    val desired = probedPath ?: return NetworkHandoverDecision.SoftRestartSamePath
    if (desired == currentPath) {
        return NetworkHandoverDecision.SoftRestartSamePath
    }
    if (desired == VpnPath.Bypass && !bypassAllowed) {
        return NetworkHandoverDecision.SoftRestartSamePath
    }
    return NetworkHandoverDecision.SwitchPath(desired)
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
