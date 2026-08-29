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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Parallel lightweight probes at app start / before Connect / on network handover.
 * Does NOT bring up VpnService.
 *
 * Network type is two literal IPs (no DNS, no SNI):
 * - **77.88.8.8** (Yandex DNS) — reachable on operator whitelist (БС) and open nets.
 * - **VPS IP** (provision :9100) — reachable only when the underlay can talk to
 *   our server, i.e. open internet. Replaces a 1.1.1.1 “foreign DNS” check so
 *   Direct is chosen only when the actual Direct target is reachable.
 *
 * Connect: VPS IP ok → Direct (Auto falls back to Bypass if AWG then fails).
 * Yandex DNS ok but VPS unreachable → Bypass. Neither → NoNetwork.
 *
 * When [bindNetwork] is set, sockets bind to that underlay (not the tunnel).
 */
object NetworkProbe {

    /** Yandex DNS — typically allowed on RU operator whitelists. */
    val YANDEX_DNS: InetAddress = InetAddress.getByAddress(byteArrayOf(77, 88, 8, 8))

    const val YANDEX_DNS_PORT = 53
    const val DEFAULT_VPS_PROBE_PORT = 9100

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
        val udpMs = if (quick) 1_000 else 1_500
        var result: ProbeResult
        val elapsed = measureTimeMillis {
            result = coroutineScope {
                val systemOnline = isSystemOnline(context, bindNetwork)
                val vpsTarget = vpsProbeTarget(provisionBaseUrl, directEndpoint)

                val yandexDef = async { yandexDnsReachable(udpMs, tcpMs, bindNetwork) }
                val vpsDef = async {
                    vpsTarget?.let { (host, port) ->
                        tcpReachable(host, port, tcpMs, bindNetwork)
                    } ?: false
                }
                val captiveDef = async { detectCaptive(bindNetwork, captiveMs) }

                val yandexOk = yandexDef.await()
                val vpsOk = vpsDef.await()
                val captive = captiveDef.await()

                classify(
                    systemOnline = systemOnline,
                    yandexOk = yandexOk,
                    bigtechOk = false,
                    captive = captive,
                    awgUdpOk = false,
                    provisionOk = vpsOk,
                )
            }
        }
        result.copy(elapsedMs = elapsed)
    }

    /**
     * UDP DNS query to 77.88.8.8:53, then TCP :53. Numeric address — no resolver.
     */
    internal fun yandexDnsReachable(
        udpTimeoutMs: Int,
        tcpTimeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean =
        udpDnsReachable(YANDEX_DNS, YANDEX_DNS_PORT, udpTimeoutMs, bindNetwork) ||
            tcpReachableAddr(YANDEX_DNS, YANDEX_DNS_PORT, tcpTimeoutMs, bindNetwork)

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
                message = "Требуется авторизация в сети",
                elapsedMs = 0,
            )
        }
        if (!systemOnline && !yandexOk && !provisionOk) {
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
        // VPS IP reachable → open enough for Direct (AWG). Auto still falls
        // back to Bypass if the tunnel then fails.
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
        // 77.88.8.8 lives on whitelist; VPS IP does not → Bypass.
        if (yandexOk) {
            return ProbeResult(
                networkClass = NetworkClass.NeedBypass,
                preselectedPath = VpnPath.Bypass,
                systemOnline = systemOnline,
                yandexOk = true,
                bigtechOk = bigtechOk,
                captive = false,
                awgUdpOk = false,
                provisionOk = false,
                message = "Готово: обход",
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

    /**
     * Host:port to probe for “can we reach our VPS IP”.
     * Prefer provision URL (already an IP in real profiles); else Direct endpoint host :9100.
     */
    internal fun vpsProbeTarget(
        provisionBaseUrl: String?,
        directEndpoint: String?,
    ): Pair<String, Int>? {
        if (!provisionBaseUrl.isNullOrBlank()) {
            val url = runCatching { URL(provisionBaseUrl) }.getOrNull()
            val host = url?.host?.trim().orEmpty()
            if (host.isNotEmpty()) {
                val port = if (url != null && url.port > 0) url.port else DEFAULT_VPS_PROBE_PORT
                return host to port
            }
        }
        val host = parseEndpoint(directEndpoint)?.first ?: return null
        return host to DEFAULT_VPS_PROBE_PORT
    }

    internal fun buildDnsQuery(name: String = "ya.ru"): ByteArray {
        val encoded = encodeDnsName(name)
        val packet = ByteArray(12 + encoded.size + 4)
        packet[0] = 0x12
        packet[1] = 0x34
        packet[2] = 0x01 // recursion desired
        packet[5] = 0x01 // 1 question
        encoded.copyInto(packet, 12)
        val q = 12 + encoded.size
        packet[q + 1] = 1 // A
        packet[q + 3] = 1 // IN
        return packet
    }

    internal fun encodeDnsName(host: String): ByteArray {
        val labels = host.split('.').filter { it.isNotEmpty() }
        val size = 1 + labels.sumOf { 1 + it.length }
        val out = ByteArray(size)
        var i = 0
        for (label in labels) {
            out[i++] = label.length.toByte()
            val bytes = label.encodeToByteArray()
            bytes.copyInto(out, i)
            i += bytes.size
        }
        out[i] = 0
        return out
    }

    private fun isSystemOnline(context: Context, bindNetwork: Network?): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = bindNetwork ?: cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun udpDnsReachable(
        addr: InetAddress,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            bindNetwork?.bindSocket(socket)
            socket.soTimeout = timeoutMs
            val query = buildDnsQuery()
            socket.send(DatagramPacket(query, query.size, addr, port))
            val buf = ByteArray(512)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            reply.length > 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun tcpReachable(
        host: String,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        val addr = numericIpv4(host) ?: return tcpReachableHost(host, port, timeoutMs, bindNetwork)
        return tcpReachableAddr(addr, port, timeoutMs, bindNetwork)
    }

    private fun tcpReachableHost(
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

    private fun tcpReachableAddr(
        addr: InetAddress,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        return try {
            Socket().use { socket ->
                bindNetwork?.bindSocket(socket)
                socket.connect(InetSocketAddress(addr, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun numericIpv4(host: String): InetAddress? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            bytes[i] = n.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    /**
     * Send a WireGuard handshake-initiation (type 1) and wait for any UDP reply.
     * Unused in live probe: this stack's Jc/H1 drops junk initiations.
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
