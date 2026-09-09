package com.ardtt.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

fun vpnTunnelNetwork(cm: ConnectivityManager): Network? = runCatching {
    cm.allNetworks.firstOrNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}.getOrNull()

/**
 * Expected reply through the VPN tun — not the physical underlay and not a
 * leftover handshake counter. Failure must not be treated as success.
 */
fun confirmRouteThroughVpn(
    context: Context,
    host: String?,
    port: Int,
    timeoutMs: Int = 4_000,
): Boolean {
    if (host.isNullOrBlank() || port <= 0) return false
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val vpn = vpnTunnelNetwork(cm) ?: return false
    return runCatching {
        Socket().use { socket ->
            vpn.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.isConnected
        }
    }.getOrDefault(false)
}
