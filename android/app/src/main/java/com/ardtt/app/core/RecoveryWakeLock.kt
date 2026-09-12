package com.ardtt.app.core

import android.content.Context
import android.os.PowerManager

/** Slack so the hold outlives the timer it protects, plus a hard ceiling. */
const val RECOVERY_WAKELOCK_SLACK_MS = 10_000L
const val RECOVERY_WAKELOCK_MAX_MS = 120_000L

/**
 * @return null when the hold would have to be truncated: a Direct re-check gap
 * runs up to 10 minutes, and a capped hold there only burns two minutes of CPU
 * and is long gone by the time the timer is due.
 */
fun recoveryWakeLockTimeoutMs(
    requestedMs: Long,
    slackMs: Long = RECOVERY_WAKELOCK_SLACK_MS,
    maxMs: Long = RECOVERY_WAKELOCK_MAX_MS,
): Long? {
    val timeoutMs = requestedMs.coerceAtLeast(0L) + slackMs
    return timeoutMs.takeIf { it <= maxMs }
}

/**
 * Ownership bookkeeping for a bounded CPU hold.
 *
 * Recovery budgets are measured in `elapsedRealtime`, but the timers that spend
 * them are `delay()` calls on clocks that stop while the CPU is suspended. With
 * the screen off a scheduled retry can therefore sit unfired for the whole Doze
 * window. qWDTT holds a PARTIAL_WAKE_LOCK for the entire session; here the hold
 * covers only the windows where a reconnect is actually pending.
 *
 * A timer that re-arms from inside its own callback cancels the job it is
 * running in, so the successor takes the lock before the predecessor's `finally`
 * runs. Tokens keep that `finally` from releasing the successor's hold.
 */
class RecoveryWakeLockGate(
    private val onAcquire: (Long) -> Unit,
    private val onRelease: () -> Unit,
) {
    private var token: Long = 0L
    private var held: Boolean = false

    val isHeld: Boolean @Synchronized get() = held

    @Synchronized
    fun acquire(timeoutMs: Long): Long {
        token += 1L
        val holdMs = recoveryWakeLockTimeoutMs(timeoutMs)
        if (holdMs == null) {
            // Too long to cover: take no hold at all and let the owner's
            // release() be a no-op. A predecessor hold is still dropped —
            // its timer was cancelled to make room for this one.
            if (held) {
                held = false
                onRelease()
            }
            return token
        }
        held = true
        onAcquire(holdMs)
        return token
    }

    @Synchronized
    fun release(ownedToken: Long) {
        if (!held || ownedToken != token) return
        held = false
        onRelease()
    }

    @Synchronized
    fun releaseNow() {
        if (!held) return
        held = false
        onRelease()
    }
}

/** [RecoveryWakeLockGate] backed by a real non-reference-counted partial wake lock. */
class RecoveryWakeLock(context: Context, tag: String) {
    private val appContext = context.applicationContext
    private val lock: PowerManager.WakeLock? by lazy {
        runCatching {
            appContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                ?.apply { setReferenceCounted(false) }
        }.getOrElse {
            AppLog.w("RecoveryWakeLock", "$tag unavailable: ${it.message}")
            null
        }
    }

    private val gate = RecoveryWakeLockGate(
        onAcquire = { timeoutMs -> runCatching { lock?.acquire(timeoutMs) } },
        onRelease = { runCatching { lock?.takeIf { it.isHeld }?.release() } },
    )

    fun acquire(timeoutMs: Long): Long = gate.acquire(timeoutMs)

    fun release(ownedToken: Long) = gate.release(ownedToken)

    fun releaseNow() = gate.releaseNow()
}
