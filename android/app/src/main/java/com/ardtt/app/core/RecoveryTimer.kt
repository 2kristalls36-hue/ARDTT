package com.ardtt.app.core

/**
 * One owner for delayed recovery / Direct reeval. Arming replaces the
 * previous task. Tests drive [arm] / [cancel] without posting Clock by hand.
 */
class RecoveryTimer(
    private val arm: (delayMs: Long, fire: () -> Unit) -> Unit,
    private val cancel: () -> Unit,
) {
    @Volatile
    var pendingDelayMs: Long? = null
        private set

    fun schedule(delayMs: Long, fire: () -> Unit) {
        cancel()
        pendingDelayMs = delayMs.coerceAtLeast(0L)
        arm(pendingDelayMs!!) {
            pendingDelayMs = null
            fire()
        }
    }

    fun clear() {
        pendingDelayMs = null
        cancel()
    }
}

fun bypassReevalDelayMs(retryAfterElapsedMs: Long?, elapsedMs: Long): Long {
    val due = retryAfterElapsedMs
    return when {
        due == null -> RecoverySettings.DIRECT_REEVAL_WHILE_BYPASS_MS
        due > elapsedMs -> due - elapsedMs
        else -> RecoverySettings.NETWORK_RETURN_COALESCE_MS
    }
}
