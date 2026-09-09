package com.ardtt.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

fun vpnTunnelNetwork(cm: ConnectivityManager): Network? = runCatching {
    cm.allNetworks.firstOrNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}.getOrNull()

/**
 * App UID is excluded from the VPN (split tunnel). Binding the app process
 * to the first TRANSPORT_VPN Network and connecting to provision :9100 does
 * not prove the current TUN/AWG path. Treat that helper as Unsupported.
 */
fun provisionTcpIsNotPathProof(): PathConfirmResult =
    PathConfirmResult.Unsupported("provision-tcp-not-awg")

/**
 * Optional cancellable TCP helper. Production Direct confirm uses AWG
 * handshake / useful TUN RX in the VPN process — not this socket.
 */
suspend fun confirmRouteThroughVpn(
    context: Context,
    host: String?,
    port: Int,
    @Suppress("UNUSED_PARAMETER") timeoutMs: Int = 4_000,
): PathConfirmResult = withContext(Dispatchers.IO) {
    if (host.isNullOrBlank() || port <= 0) {
        return@withContext PathConfirmResult.Unsupported("no-provision-target")
    }
    val cm = context.getSystemService(ConnectivityManager::class.java)
        ?: return@withContext PathConfirmResult.InternalError("no-connectivity-manager")
    vpnTunnelNetwork(cm) ?: return@withContext PathConfirmResult.Unsupported("no-vpn-network")
    // Own UID is disallowed in the VPN. A successful bind/connect here would
    // still be the underlay or an unrelated VPN, not this attempt's TUN.
    provisionTcpIsNotPathProof()
}

internal suspend fun connectBoundSocketCancellable(
    bindNetwork: Network?,
    host: String,
    port: Int,
    timeoutMs: Int,
): PathConfirmResult = withContext(Dispatchers.IO) {
    val socket = Socket()
    val closeAll = { runCatching { socket.close() } }
    val cancelHook = coroutineContext.job.invokeOnCompletion { closeAll() }
    try {
        if (bindNetwork != null) {
            runCatching { bindNetwork.bindSocket(socket) }
                .onFailure { return@withContext PathConfirmResult.BindFailure }
        }
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        if (socket.isConnected) {
            PathConfirmResult.Unsupported("tcp-connect-is-not-path-proof")
        } else {
            PathConfirmResult.Timeout("connect")
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        classifySocketFailure(t)
    } finally {
        cancelHook.dispose()
        closeAll()
    }
}

internal fun classifySocketFailure(t: Throwable): PathConfirmResult = when (t) {
    is java.net.SocketTimeoutException -> PathConfirmResult.Timeout("connect")
    is java.net.UnknownHostException -> PathConfirmResult.DnsFailure("connect")
    is java.net.ConnectException -> PathConfirmResult.PeerRefused("connect")
    is java.net.NoRouteToHostException,
    is java.net.SocketException,
    -> PathConfirmResult.NetworkLost
    is javax.net.ssl.SSLException -> PathConfirmResult.TlsFailure("handshake")
    else -> PathConfirmResult.InternalError(t.javaClass.simpleName)
}
