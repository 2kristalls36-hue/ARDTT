package com.ardtt.app.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Close sockets when the coroutine is cancelled, while blocking I/O is still
 * running. [kotlinx.coroutines.Job.invokeOnCompletion] without a cancellation
 * callback runs too late; [suspendCancellableCoroutine.invokeOnCancellation]
 * closes the resource as soon as cancel is requested.
 */
internal suspend fun <T> closeOnCancel(close: () -> Unit, block: () -> T): T {
    val closed = AtomicBoolean(false)
    fun closeOnce() {
        if (closed.compareAndSet(false, true)) {
            runCatching { close() }
        }
    }
    return try {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { closeOnce() }
            try {
                val value = block()
                if (cont.isActive) {
                    cont.resume(value)
                }
            } catch (t: CancellationException) {
                closeOnce()
                throw t
            } catch (t: Throwable) {
                closeOnce()
                if (cont.isCancelled) {
                    throw CancellationException("closed on cancel").apply { initCause(t) }
                }
                cont.resumeWithException(t)
            }
        }
    } finally {
        closeOnce()
    }
}

internal fun remainingTimeoutMs(deadlineElapsedMs: Long, nowElapsedMs: Long, capMs: Int): Int {
    if (deadlineElapsedMs <= 0L) return capMs.coerceAtLeast(1)
    val left = (deadlineElapsedMs - nowElapsedMs).toInt()
    if (left <= 0) return 0
    return minOf(capMs, left)
}
