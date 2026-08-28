package com.nonamevpn.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Parallel lightweight probes at app start / before Connect / on network handover.
 * Does NOT bring up VpnService.
 *
 * Direct vs Bypass cannot be proven by one packet:
 * - AWG often **drops** junk UDP (no reply ≠ blocked).
 * - TCP `/health` can work on a whitelist while AWG UDP does not.
 *
 * Connect policy: if the VPS answers TCP or UDP, **try Direct**. If Direct
 * then fails, Auto falls back to Bypass. Bypass-first only when the VPS is
 * unreachable but Yandex/bigtech still work.
 *
 * When [bindNetwork] is set, sockets bind to that underlay (not the tunnel).
 */
object NetworkProbe {

    private val bigtechHosts = listOf(
        "google.com",
        "amazon.com",
        "apple.com",
        "microsoft.com",
    )

    suspend fun probe(
        context: Context,
        provisionBaseUrl: String?,
        directEndpoint: String? = null,
        bindNetwork: Network? = null,
        /** Shorter timeouts (handover / Connect re-probe). */
        quick: Boolean = false,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val tcpMs = if (quick) 1_500 else 2_000
        val captiveMs = if (quick) 1_000 else 1_500
        val healthMs = if (quick) 1_500 else 2_000
        var result: ProbeResult
        val elapsed = measureTimeMillis {
            result = coroutineScope {
                val systemOnline = isSystemOnline(context, bindNetwork)

                val yandexDef = async { tcpReachable("yandex.ru", 443, tcpMs, bindNetwork) }
                val bigtechDef = async { anyBigtechReachable(tcpMs, bindNetwork) }
                val captiveDef = async { detectCaptive(bindNetwork, captiveMs) }
                val provisionDef = async { provisionHealth(provisionBaseUrl, healthMs, bindNetwork) }

                val provisionOk = provisionDef.await()
                val yandexOk = yandexDef.await()
                val bigtechOk = bigtechDef.await()
                val captive = captiveDef.await()
                // AWG UDP-lite is not used: this stack has Jc/H1 obfuscation and
                // silently drops junk initiations even on an open network (measured
                // from 159.194.225.162:51820). Waiting for a reply only delayed
                // Connect/handover by 1–2s. Direct is selected via TCP /health;
                // if AWG then fails, Auto falls back to Bypass.

                classify(
                    systemOnline = systemOnline,
                    yandexOk = yandexOk,
                    bigtechOk = bigtechOk,
                    captive = captive,
                    awgUdpOk = false,
                    provisionOk = provisionOk,
                )
            }
        }
        result.copy(elapsedMs = elapsed)
    }

    /** Bigtech hosts in parallel — wall time ≈ one TCP timeout, not 4×. */
    private suspend fun anyBigtechReachable(timeoutMs: Int, bindNetwork: Network?): Boolean =
        coroutineScope {
            bigtechHosts
                .map { host -> async { tcpReachable(host, 443, timeoutMs, bindNetwork) } }
                .awaitAll()
                .any { it }
        }

    internal fun classify(
        systemOnline: Boolean,
        yandexOk: Boolean,
        bigtechOk: Boolean,
        captive: Boolean,
        awgUdpOk: Boolean,
        provisionOk: Boolean,
    ): ProbeResult {
        if (captive) {
            return ProbeResult(
                networkClass = NetworkClass.Captive,
                preselectedPath = null,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = true,
                awgUdpOk = awgUdpOk,
                provisionOk = provisionOk,
                message = "Войдите в сеть (captive portal)",
                elapsedMs = 0,
            )
        }
        if (!systemOnline && !yandexOk && !bigtechOk) {
            return ProbeResult(
                networkClass = NetworkClass.NoNetwork,
                preselectedPath = null,
                systemOnline = false,
                yandexOk = false,
                bigtechOk = false,
                captive = false,
                awgUdpOk = awgUdpOk,
                provisionOk = provisionOk,
                message = "Нет сети",
                elapsedMs = 0,
            )
        }
        if (awgUdpOk || provisionOk) {
            return ProbeResult(
                networkClass = NetworkClass.DirectOk,
                preselectedPath = VpnPath.Direct,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = false,
                awgUdpOk = awgUdpOk,
                provisionOk = provisionOk,
                message = "Готово: прямое",
                elapsedMs = 0,
            )
        }
        if (yandexOk || bigtechOk) {
            val open = bigtechOk
            return ProbeResult(
                networkClass = if (open) NetworkClass.OpenNeedBypass else NetworkClass.NeedBypass,
                preselectedPath = VpnPath.Bypass,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = false,
                awgUdpOk = false,
                provisionOk = false,
                message = if (open) {
                    "Готово: обход (VPS недоступен)"
                } else {
                    "Готово: обход"
                },
                elapsedMs = 0,
            )
        }
        return ProbeResult(
            networkClass = NetworkClass.NoNetwork,
            preselectedPath = null,
            systemOnline = systemOnline,
            yandexOk = yandexOk,
            bigtechOk = bigtechOk,
            captive = false,
            awgUdpOk = awgUdpOk,
            provisionOk = provisionOk,
            message = "Нет сети",
            elapsedMs = 0,
        )
    }

    private fun isSystemOnline(context: Context, bindNetwork: Network?): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = bindNetwork ?: cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun tcpReachable(
        host: String,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        return try {
            Socket().use { socket ->
                bindNetwork?.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Send a WireGuard handshake-initiation (type 1) and wait for any UDP reply.
     * AWG may add junk, but servers still answer invalid initiations with cookie/response.
     */
    internal fun awgUdpReachable(
        endpoint: String?,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        val (host, port) = parseEndpoint(endpoint) ?: return false
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            bindNetwork?.bindSocket(socket)
            socket.soTimeout = timeoutMs
            val initiation = ByteArray(148).also { it[0] = 1 }
            val addr = InetAddress.getByName(host)
            socket.send(DatagramPacket(initiation, initiation.size, addr, port))
            val buf = ByteArray(256)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            reply.length > 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun detectCaptive(bindNetwork: Network?, timeoutMs: Int = 1500): Boolean {
        return try {
            val url = URL("http://connectivitycheck.gstatic.com/generate_204")
            val conn = openHttp(url, bindNetwork).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            // 204 = OK online; 200/302/other often captive
            code != 204 && code != -1
        } catch (_: Exception) {
            false
        }
    }

    private fun provisionHealth(
        baseUrl: String?,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        if (baseUrl.isNullOrBlank()) return false
        return try {
            val url = URL(baseUrl.trimEnd('/') + "/health")
            val conn = openHttp(url, bindNetwork).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun openHttp(url: URL, bindNetwork: Network?): HttpURLConnection {
        val raw = if (bindNetwork != null) {
            bindNetwork.openConnection(url)
        } else {
            url.openConnection()
        }
        return raw as HttpURLConnection
    }

    fun parseEndpoint(endpoint: String?): Pair<String, Int>? {
        if (endpoint.isNullOrBlank()) return null
        val parts = endpoint.trim().split(':')
        if (parts.size != 2) return null
        val port = parts[1].toIntOrNull() ?: return null
        return parts[0] to port
    }

    fun hostFromEndpoint(endpoint: String?): String? = parseEndpoint(endpoint)?.first
}
