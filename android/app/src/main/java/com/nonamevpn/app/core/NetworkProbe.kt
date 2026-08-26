package com.nonamevpn.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parallel lightweight probes at app start / before Connect.
 * Does NOT bring up VpnService. No TCP probe to RAW UDP port.
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
        directEndpoint: String?,
        provisionBaseUrl: String?,
    ): ProbeResult = withContext(Dispatchers.IO) {
        var result: ProbeResult
        val elapsed = measureTimeMillis {
            result = coroutineScope {
                val systemOnline = isSystemOnline(context)

                val yandexDef = async { tcpReachable("yandex.ru", 443, 4_000) }
                val bigtechDef = async {
                    bigtechHosts.any { tcpReachable(it, 443, 4_000) }
                }
                val captiveDef = async { detectCaptive() }
                val udpDef = async { udpLite(directEndpoint, 2_000) }
                val provisionDef = async { provisionHealth(provisionBaseUrl, 2_500) }

                val yandexOk = yandexDef.await()
                val bigtechOk = bigtechDef.await()
                val captive = captiveDef.await()
                val vpsUdpOk = udpDef.await()
                val provisionOk = provisionDef.await()

                // VPS "ok" for Direct: UDP response preferred; provision health is host-alive hint only
                val vpsOk = vpsUdpOk

                classify(
                    systemOnline = systemOnline,
                    yandexOk = yandexOk,
                    bigtechOk = bigtechOk,
                    captive = captive,
                    vpsUdpOk = vpsOk,
                    provisionOk = provisionOk,
                )
            }
        }
        result.copy(elapsedMs = elapsed)
    }

    private fun classify(
        systemOnline: Boolean,
        yandexOk: Boolean,
        bigtechOk: Boolean,
        captive: Boolean,
        vpsUdpOk: Boolean,
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
                vpsUdpOk = vpsUdpOk,
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
                vpsUdpOk = vpsUdpOk,
                provisionOk = provisionOk,
                message = "Нет сети",
                elapsedMs = 0,
            )
        }
        if (vpsUdpOk) {
            return ProbeResult(
                networkClass = NetworkClass.DirectOk,
                preselectedPath = VpnPath.Direct,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = false,
                vpsUdpOk = true,
                provisionOk = provisionOk,
                message = "Готово: прямое",
                elapsedMs = 0,
            )
        }
        if (yandexOk || bigtechOk || provisionOk) {
            val open = bigtechOk
            return ProbeResult(
                networkClass = if (open) NetworkClass.OpenNeedBypass else NetworkClass.NeedBypass,
                preselectedPath = VpnPath.Bypass,
                systemOnline = systemOnline,
                yandexOk = yandexOk,
                bigtechOk = bigtechOk,
                captive = false,
                vpsUdpOk = false,
                provisionOk = provisionOk,
                message = if (open) {
                    "Готово: обход (открытая сеть, VPS по UDP не ответил)"
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
            vpsUdpOk = vpsUdpOk,
            provisionOk = provisionOk,
            message = "Нет сети",
            elapsedMs = 0,
        )
    }

    private fun isSystemOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun detectCaptive(): Boolean {
        return try {
            val url = URL("http://connectivitycheck.gstatic.com/generate_204")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 2500
                readTimeout = 2500
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

    private fun provisionHealth(baseUrl: String?, timeoutMs: Int): Boolean {
        if (baseUrl.isNullOrBlank()) return false
        return try {
            val url = URL(baseUrl.trimEnd('/') + "/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
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

    /**
     * Send a small UDP datagram to AWG endpoint and wait for any reply.
     * Valid Noise handshake needs keys; this is a LOS hint only.
     */
    private suspend fun udpLite(endpoint: String?, timeoutMs: Int): Boolean {
        val parsed = parseEndpoint(endpoint) ?: return false
        return withTimeoutOrNull(timeoutMs.toLong() + 200L) {
            withContext(Dispatchers.IO) {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val payload = ByteArray(64) { 0x01 }
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        InetSocketAddress(parsed.first, parsed.second),
                    )
                    socket.send(packet)
                    val buf = ByteArray(256)
                    val resp = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(resp)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        } ?: false
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
