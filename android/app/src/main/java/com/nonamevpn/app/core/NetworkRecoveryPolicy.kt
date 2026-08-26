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
        networkSettleDelayMs = 8_000L,
        reconnectMinIntervalMs = 45_000L,
        processRestartDelayMs = 800L,
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
    (minIntervalMs + softRestartCount.coerceAtMost(4) * 15_000L).coerceAtMost(5 * 60_000L)

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
const val WAKE_RESCUE_GRACE_MS = 60_000L

/** Suppress zero-worker watchdog briefly after wake / soft restart. */
const val WAKE_RECOVERY_GRACE_MS = 90_000L

/** Path B: soft-restart if Активных stays 0 this long while screen is on. */
const val ZERO_WORKERS_GRACE_MS = 5 * 60_000L

/** Soft-restart if backend process/job is dead this long. */
const val PROCESS_DEAD_GRACE_MS = 60_000L

const val WATCHDOG_POLL_MS = 5_000L

const val TRUSTED_WIFI_ENTER_DELAY_MS = 2_000L
const val TRUSTED_WIFI_EXIT_DELAY_MS = 5_000L

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
