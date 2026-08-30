package com.nonamevpn.app.core

import com.nonamevpn.app.profile.VpnProfile
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight reachability RTT for profile cards (TCP connect).
 * Prefers bypass peer, then direct endpoint, then provision :9100.
 */
object ProfilePingHelper {
    suspend fun measureMs(profile: VpnProfile, timeoutMs: Int = 2_500): Long =
        withContext(Dispatchers.IO) {
            val targets = buildList {
                NetworkProbe.parseEndpoint(profile.bypass.peer)?.let { add(it) }
                NetworkProbe.parseEndpoint(profile.direct.endpoint)?.let { add(it) }
                val host = NetworkProbe.hostFromEndpoint(profile.bypass.peer)
                    ?: NetworkProbe.hostFromEndpoint(profile.direct.endpoint)
                if (!host.isNullOrBlank()) add(host to 9100)
            }.distinct()
            if (targets.isEmpty()) return@withContext -1L
            for ((host, port) in targets) {
                val ms = tcpConnectMs(host, port, timeoutMs)
                if (ms >= 0L) return@withContext ms
            }
            -1L
        }

    fun tcpConnectMs(host: String, port: Int, timeoutMs: Int): Long {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: Exception) {
            -1L
        }
    }
}
