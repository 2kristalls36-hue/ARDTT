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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parallel lightweight probes at app start / before Connect / on network handover.
 * Does NOT bring up VpnService.
 *
 * Classification (fail-fast, no DNS on the internet/БС checks):
 * - **77.88.8.8** (Yandex DNS) — reaches even on operator whitelist (БС).
 * - **1.1.1.1** (Cloudflare) — reaches on open internet, typically blocked on БС.
 * - **VPS provision TCP** — if the user's server is reachable, Auto picks Direct
 *   even on БС (no RAW/call bypass needed).
 *
 * Path is decided as soon as the VPS result is known; 1.1.1.1 is only a БС label
 * and never blocks Direct.
 */
object NetworkProbe {

    const val YANDEX_DNS_IP = "77.88.8.8"
    const val CLOUDFLARE_IP = "1.1.1.1"

    suspend fun probe(
        context: Context,
        provisionBaseUrl: String?,
        directEndpoint: String? = null,
        bindNetwork: Network? = null,
        /** Shorter timeouts (handover / Connect re-probe). */
        quick: Boolean = false,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val tcpMs = if (quick) 450 else 700
        val captiveMs = if (quick) 400 else 600
        val healthMs = if (quick) 600 else 900
        var result: ProbeResult
        val elapsed = measureTimeMillis {
            result = coroutineScope {
                val systemOnline = isSystemOnline(context, bindNetwork)
                val yandexDef = async { ipReachable(YANDEX_DNS_IP, tcpMs, bindNetwork) }
                val cloudflareDef = async { ipReachable(CLOUDFLARE_IP, tcpMs, bindNetwork) }
                val provisionDef = async { provisionReachable(provisionBaseUrl, healthMs, bindNetwork) }

                var yandexOk: Boolean? = null
                var cloudflareOk: Boolean? = null
                var provisionOk: Boolean? = null
                var captiveChecked = false
                var captive = false

                fun snapshot(): ProbeResult = NetworkProbePolicy.classify(
                    systemOnline = systemOnline,
                    yandexOk = yandexOk == true,
                    bigtechOk = cloudflareOk == true,
                    captive = captive,
                    provisionOk = provisionOk == true,
                )

                while (true) {
                    val hint = NetworkProbePolicy.decideProbePath(
                        provisionOk = provisionOk,
                        yandexOk = yandexOk,
                        cloudflareOk = cloudflareOk,
                        captive = if (captiveChecked) captive else null,
                    )
                    when (hint) {
                        ProbePathHint.Wait -> Unit
                        ProbePathHint.NoNetwork -> {
                            if (!captiveChecked && systemOnline) {
                                captive = detectCaptive(bindNetwork, captiveMs)
                                captiveChecked = true
                                continue
                            }
                            yandexDef.cancel()
                            cloudflareDef.cancel()
                            provisionDef.cancel()
                            return@coroutineScope snapshot()
                        }
                        ProbePathHint.Captive,
                        ProbePathHint.Direct,
                        ProbePathHint.Bypass,
                        -> {
                            // Tiny drain so UI can show 1.1.1.1 / 77.88.8.8 without
                            // waiting for a black-holed Cloudflare on БС.
                            withTimeoutOrNull(80) {
                                if (yandexOk == null) yandexOk = yandexDef.await()
                                if (cloudflareOk == null) cloudflareOk = cloudflareDef.await()
                            }
                            yandexDef.cancel()
                            cloudflareDef.cancel()
                            provisionDef.cancel()
                            if (hint == ProbePathHint.Captive) captive = true
                            return@coroutineScope snapshot()
                        }
                    }

                    val waitingProvision = provisionOk == null
                    val waitingYandex = yandexOk == null
                    val waitingCf = cloudflareOk == null
                    if (!waitingProvision && !waitingYandex && !waitingCf) {
                        return@coroutineScope snapshot()
                    }
                    select {
                        if (waitingProvision) {
                            provisionDef.onAwait { provisionOk = it }
                        }
                        if (waitingYandex) {
                            yandexDef.onAwait { yandexOk = it }
                        }
                        if (waitingCf) {
                            cloudflareDef.onAwait { cloudflareOk = it }
                        }
                    }
                }
                error("probe loop exited")
            }
        }
        result.copy(elapsedMs = elapsed)
    }

    internal fun decideProbePath(
        provisionOk: Boolean?,
        yandexOk: Boolean?,
        cloudflareOk: Boolean?,
        captive: Boolean?,
    ): ProbePathHint = NetworkProbePolicy.decideProbePath(
        provisionOk = provisionOk,
        yandexOk = yandexOk,
        cloudflareOk = cloudflareOk,
        captive = captive,
    )

    internal fun classify(
        systemOnline: Boolean,
        yandexOk: Boolean,
        bigtechOk: Boolean,
        captive: Boolean,
        awgUdpOk: Boolean,
        provisionOk: Boolean,
    ): ProbeResult = NetworkProbePolicy.classify(
        systemOnline = systemOnline,
        yandexOk = yandexOk,
        bigtechOk = bigtechOk,
        captive = captive,
        provisionOk = provisionOk,
    )

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

    /** TCP 443 and 53 in parallel — first success wins. Literal IPs, no DNS. */
    internal suspend fun ipReachable(
        ip: String,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean = coroutineScope {
        val https = async { tcpReachable(ip, 443, timeoutMs, bindNetwork) }
        val dns = async { tcpReachable(ip, 53, timeoutMs, bindNetwork) }
        try {
            select {
                https.onAwait { ok -> if (ok) true else dns.await() }
                dns.onAwait { ok -> if (ok) true else https.await() }
            }
        } finally {
            https.cancel()
            dns.cancel()
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
                socket.tcpNoDelay = true
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

    /** TCP to provision host:port — faster than HTTP GET, enough to know the VPS IP is reachable. */
    internal fun provisionReachable(
        baseUrl: String?,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean {
        val endpoint = parseProvisionEndpoint(baseUrl) ?: return false
        return tcpReachable(endpoint.first, endpoint.second, timeoutMs, bindNetwork)
    }

    internal fun parseProvisionEndpoint(baseUrl: String?): Pair<String, Int>? =
        NetworkProbePolicy.parseProvisionEndpoint(baseUrl)

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



