package com.ardtt.app.bypass

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Passes TUN fd to go_client (libclient.so subprocess) via abstract unix socket + SCM_RIGHTS.
 * Android is the client; go_client listens (see tun_fd.go recvTunFD).
 */
object TunFdBridge {
    private const val TAG = "TunFdBridge"
    private const val CONNECT_RETRY_DELAY_MS = 200L
    private const val MAX_ATTEMPTS = 40

    fun newSocketName(): String =
        "ardtt_tun_fd_${android.os.Process.myPid()}_${System.nanoTime()}"

    /** Path for go_client -tun-fd-sock (abstract namespace). */
    fun goSockPath(name: String): String = "@$name"

    /** Single abstract-socket connect. Caller retries until go_client listens. */
    fun tryConnectOnce(name: String, pfd: ParcelFileDescriptor): Boolean {
        var client: LocalSocket? = null
        return try {
            client = LocalSocket()
            client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
            client.outputStream.write(1)
            client.outputStream.flush()
            Log.i(TAG, "TUN fd sent via $name")
            true
        } catch (e: Exception) {
            Log.d(TAG, "connect failed: ${e.message}")
            false
        } finally {
            runCatching { client?.close() }
        }
    }

    suspend fun sendOnce(name: String, pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            Log.d(TAG, "connect attempt=$attempt name=$name")
            if (tryConnectOnce(name, pfd)) return@withContext
            lastError = IllegalStateException("attempt $attempt failed")
            delay(CONNECT_RETRY_DELAY_MS)
        }
        throw IllegalStateException(
            "Не удалось передать TUN в go_client ($MAX_ATTEMPTS попыток)",
            lastError,
        )
    }
}
