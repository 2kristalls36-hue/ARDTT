package com.ardtt.app.core

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
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parallel lightweight probes at app start / before Connect / on network handover.
 * Does NOT bring up VpnService.
 *
 * Classification (fail-fast, no DNS on the internet/БС checks):
 * - **77.88.8.8** (Yandex DNS) — control group; TCP :443/:53 even on operator whitelist (БС).
 * - **1.1.1.1** (Cloudflare) — one ordinary provider (TLS or UDP :53).
 * - **8.8.8.8** (Google) — independent ordinary provider.
 * - **VPS /health** — HTTP, not TCP :9100 and not AmneziaWG.
 *
 * Auto still tries Direct first. Restriction needs control + two ordinary
 * failures on cellular; one Cloudflare miss is not two providers.
 */
object NetworkProbe {

    const val YANDEX_DNS_IP = "77.88.8.8"
    const val CLOUDFLARE_IP = "1.1.1.1"
    const val GOOGLE_DNS_IP = "8.8.8.8"
    const val DEFAULT_VPS_PROBE_PORT = 9100

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
        val tlsMs = if (quick) 800 else 1200
        val udpMs = if (quick) 500 else 700
        var result: ProbeResult
        val elapsed = measureTimeMillis {
            result = coroutineScope {
                val systemOnline = isSystemOnline(context, bindNetwork)
                val underlayKind = probeUnderlayKind(context, bindNetwork)
                val yandexDef = async { ipReachableOutcome(YANDEX_DNS_IP, tcpMs, bindNetwork) }
                val cloudflareDef = async { cloudflareOpenOutcome(tlsMs, udpMs, bindNetwork) }
                val googleDef = async { ipReachableOutcome(GOOGLE_DNS_IP, tcpMs, bindNetwork) }
                val provisionDef = async { provisionReachableOutcome(provisionBaseUrl, healthMs, bindNetwork) }

                var yandex: CheckOutcome? = null
                var cloudflare: CheckOutcome? = null
                var google: CheckOutcome? = null
                var provision: CheckOutcome? = null
                var captiveChecked = false
                var captive = false

                fun snapshot(): ProbeResult = NetworkProbePolicy.classify(
                    systemOnline = systemOnline,
                    yandexOk = yandex?.isSuccess == true,
                    bigtechOk = cloudflare?.isSuccess == true,
                    captive = captive,
                    provisionOk = provision?.isSuccess == true,
                    underlayKind = underlayKind,
                    yandexOutcome = yandex ?: CheckOutcome.NotRun,
                    bigtechOutcome = cloudflare ?: CheckOutcome.NotRun,
                    provisionOutcome = provision ?: CheckOutcome.NotRun,
                    googleOk = google?.isSuccess == true,
                    googleOutcome = google ?: CheckOutcome.NotRun,
                )

                while (true) {
                    val hint = NetworkProbePolicy.decideProbePath(
                        provisionOk = provision?.toProbeFlag(),
                        yandexOk = yandex?.toProbeFlag(),
                        cloudflareOk = cloudflare?.toProbeFlag(),
                        captive = if (captiveChecked) captive else null,
                        googleOk = google?.toProbeFlag(),
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
                            googleDef.cancel()
                            provisionDef.cancel()
                            return@coroutineScope snapshot()
                        }
                        ProbePathHint.Captive,
                        ProbePathHint.Direct,
                        ProbePathHint.Bypass,
                        -> {
                            withTimeoutOrNull(80) {
                                if (yandex == null) yandex = yandexDef.await()
                                if (cloudflare == null) cloudflare = cloudflareDef.await()
                                if (google == null) google = googleDef.await()
                            }
                            yandexDef.cancel()
                            cloudflareDef.cancel()
                            googleDef.cancel()
                            provisionDef.cancel()
                            if (hint == ProbePathHint.Captive) captive = true
                            return@coroutineScope snapshot()
                        }
                    }

                    val waitingProvision = provision == null
                    val waitingYandex = yandex == null
                    val waitingCf = cloudflare == null
                    val waitingGoogle = google == null
                    if (!waitingProvision && !waitingYandex && !waitingCf && !waitingGoogle) {
                        return@coroutineScope snapshot()
                    }
                    select {
                        if (waitingProvision) {
                            provisionDef.onAwait { provision = it }
                        }
                        if (waitingYandex) {
                            yandexDef.onAwait { yandex = it }
                        }
                        if (waitingCf) {
                            cloudflareDef.onAwait { cloudflare = it }
                        }
                        if (waitingGoogle) {
                            googleDef.onAwait { google = it }
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
        googleOk: Boolean? = null,
    ): ProbePathHint = NetworkProbePolicy.decideProbePath(
        provisionOk = provisionOk,
        yandexOk = yandexOk,
        cloudflareOk = cloudflareOk,
        captive = captive,
        googleOk = googleOk,
    )

    internal fun classify(
        systemOnline: Boolean,
        yandexOk: Boolean,
        bigtechOk: Boolean,
        captive: Boolean,
        provisionOk: Boolean,
        awgUdpOk: Boolean = false,
        underlayKind: UnderlayKind = UnderlayKind.Other,
        seriesCount: Int = 1,
        googleOk: Boolean = false,
        googleOutcome: CheckOutcome? = null,
        yandexOutcome: CheckOutcome? = null,
        bigtechOutcome: CheckOutcome? = null,
        provisionOutcome: CheckOutcome? = null,
    ): ProbeResult = NetworkProbePolicy.classify(
        systemOnline = systemOnline,
        yandexOk = yandexOk,
        bigtechOk = bigtechOk,
        captive = captive,
        provisionOk = provisionOk,
        underlayKind = underlayKind,
        seriesCount = seriesCount,
        googleOk = googleOk,
        googleOutcome = googleOutcome,
        yandexOutcome = yandexOutcome,
        bigtechOutcome = bigtechOutcome,
        provisionOutcome = provisionOutcome,
    ).copy(awgUdpOk = awgUdpOk)

    private fun probeUnderlayKind(context: Context, bindNetwork: Network?): UnderlayKind {
        val network = bindNetwork ?: pickBestUnderlayNetwork(context) ?: return UnderlayKind.Other
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return UnderlayKind.Other
        val caps = cm.getNetworkCapabilities(network) ?: return UnderlayKind.Other
        return classifyUnderlayKind(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
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

    /**
     * Open-internet check for 1.1.1.1. TLS or UDP DNS — not TCP connect.
     * Cloudflare TLS and Cloudflare DNS are one provider.
     */
    internal suspend fun cloudflareOpen(
        tlsMs: Int,
        udpMs: Int,
        bindNetwork: Network?,
    ): Boolean = cloudflareOpenOutcome(tlsMs, udpMs, bindNetwork).isSuccess

    internal suspend fun cloudflareOpenOutcome(
        tlsMs: Int,
        udpMs: Int,
        bindNetwork: Network?,
    ): CheckOutcome = coroutineScope {
        val tls = async { tlsReachableOutcome(CLOUDFLARE_IP, 443, tlsMs, bindNetwork) }
        val udp = async { udpDnsReachableOutcome(CLOUDFLARE_IP, udpMs, bindNetwork) }
        try {
            select {
                tls.onAwait { ok -> if (ok.isSuccess) ok else udp.await() }
                udp.onAwait { ok -> if (ok.isSuccess) ok else tls.await() }
            }
        } finally {
            tls.cancel()
            udp.cancel()
        }
    }

    internal suspend fun tlsReachable(
        host: String,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean = tlsReachableOutcome(host, port, timeoutMs, bindNetwork).isSuccess

    internal suspend fun tlsReachableOutcome(
        host: String,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): CheckOutcome {
        val raw = Socket()
        var ssl: SSLSocket? = null
        val closeAll = {
            runCatching { ssl?.close() }
            runCatching { raw.close() }
        }
        val cancelHook = coroutineContext.job.invokeOnCompletion { closeAll() }
        return try {
            val addr = numericIpv4(host)
            raw.tcpNoDelay = true
            if (bindNetwork != null) {
                try {
                    bindNetwork.bindSocket(raw)
                } catch (_: Exception) {
                    return CheckOutcome.BindFailure
                }
            }
            if (addr != null) {
                raw.connect(InetSocketAddress(addr, port), timeoutMs)
            } else {
                raw.connect(InetSocketAddress(host, port), timeoutMs)
            }
            raw.soTimeout = timeoutMs
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            applyHttpsEndpointIdentification(ssl)
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            if (ssl.session != null && ssl.session.isValid) CheckOutcome.Success else CheckOutcome.TlsFailure
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            classifyCheckFailure(t)
        } finally {
            cancelHook.dispose()
            closeAll()
        }
    }

    internal fun applyHttpsEndpointIdentification(ssl: SSLSocket) {
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
    }

    internal suspend fun udpDnsReachable(
        ip: String,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean = udpDnsReachableOutcome(ip, timeoutMs, bindNetwork).isSuccess

    internal suspend fun udpDnsReachableOutcome(
        ip: String,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): CheckOutcome {
        val socket = DatagramSocket()
        val cancelHook = coroutineContext.job.invokeOnCompletion {
            runCatching { socket.close() }
        }
        return try {
            if (bindNetwork != null) {
                try {
                    bindNetwork.bindSocket(socket)
                } catch (_: Exception) {
                    return CheckOutcome.BindFailure
                }
            }
            socket.soTimeout = timeoutMs
            val query = buildDnsQuery()
            val addr = numericIpv4(ip) ?: InetAddress.getByName(ip)
            socket.send(DatagramPacket(query, query.size, addr, 53))
            val buf = ByteArray(512)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            if (dnsReplyLooksValid(query, buf, reply.length)) {
                CheckOutcome.Success
            } else {
                CheckOutcome.TransportFailure
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            classifyCheckFailure(t)
        } finally {
            cancelHook.dispose()
            runCatching { socket.close() }
        }
    }

    /** TCP 443 and 53 in parallel — first success wins. Literal IPs, no DNS. */
    internal suspend fun ipReachable(
        ip: String,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean = ipReachableOutcome(ip, timeoutMs, bindNetwork).isSuccess

    internal suspend fun ipReachableOutcome(
        ip: String,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): CheckOutcome = coroutineScope {
        val https = async { tcpReachableOutcome(ip, 443, timeoutMs, bindNetwork) }
        val dns = async { tcpReachableOutcome(ip, 53, timeoutMs, bindNetwork) }
        try {
            select {
                https.onAwait { ok -> if (ok.isSuccess) ok else dns.await() }
                dns.onAwait { ok -> if (ok.isSuccess) ok else https.await() }
            }
        } finally {
            https.cancel()
            dns.cancel()
        }
    }

    private fun tcpReachableOutcome(
        host: String,
        port: Int,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): CheckOutcome {
        val addr = numericIpv4(host)
        val socket = Socket()
        val closeAll = { runCatching { socket.close() } }
        return try {
            socket.tcpNoDelay = true
            if (bindNetwork != null) {
                try {
                    bindNetwork.bindSocket(socket)
                } catch (_: Exception) {
                    return CheckOutcome.BindFailure
                }
            }
            if (addr != null) {
                socket.connect(InetSocketAddress(addr, port), timeoutMs)
            } else {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            CheckOutcome.Success
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            classifyCheckFailure(t)
        } finally {
            closeAll()
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

    internal fun dnsReplyLooksValid(query: ByteArray, reply: ByteArray, replyLen: Int): Boolean {
        if (query.size < 12 || replyLen < 12 || reply.size < 12) return false
        if (reply[0] != query[0] || reply[1] != query[1]) return false
        val qr = (reply[2].toInt() and 0x80) != 0
        if (!qr) return false
        val rcode = reply[3].toInt() and 0x0f
        if (rcode != 0) return false
        val questions = ((reply[4].toInt() and 0xff) shl 8) or (reply[5].toInt() and 0xff)
        if (questions != 1) return false
        return true
    }

    internal fun classifyCheckFailure(t: Throwable): CheckOutcome = when (t) {
        is java.net.SocketTimeoutException -> CheckOutcome.Timeout
        is java.net.UnknownHostException -> CheckOutcome.DnsFailure
        is java.net.ConnectException -> {
            val msg = t.message.orEmpty()
            if (msg.contains("refused", ignoreCase = true)) CheckOutcome.Refused else CheckOutcome.TransportFailure
        }
        is java.net.NoRouteToHostException -> CheckOutcome.NetworkLost
        is javax.net.ssl.SSLHandshakeException,
        is javax.net.ssl.SSLPeerUnverifiedException,
        is javax.net.ssl.SSLException,
        -> CheckOutcome.TlsFailure
        is java.net.SocketException -> {
            val msg = t.message.orEmpty()
            when {
                msg.contains("unreachable", ignoreCase = true) -> CheckOutcome.NetworkLost
                msg.contains("Permission denied", ignoreCase = true) -> CheckOutcome.BindFailure
                else -> CheckOutcome.TransportFailure
            }
        }
        else -> CheckOutcome.TransportFailure
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

    internal fun provisionHealthUrl(baseUrl: String?): String? {
        val base = baseUrl?.trim()?.trimEnd('/') ?: return null
        if (base.isEmpty()) return null
        return if (base.endsWith("/health")) base else "$base/health"
    }

    /** HTTP GET /health — TCP :9100 is not a working Direct path. */
    internal fun provisionReachable(
        baseUrl: String?,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): Boolean = provisionReachableOutcome(baseUrl, timeoutMs, bindNetwork).isSuccess

    internal fun provisionReachableOutcome(
        baseUrl: String?,
        timeoutMs: Int,
        bindNetwork: Network?,
    ): CheckOutcome {
        val healthUrl = provisionHealthUrl(baseUrl) ?: return CheckOutcome.NotRun
        return try {
            val conn = openHttp(URL(healthUrl), bindNetwork).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            if (provisionHealthAccepted(code)) CheckOutcome.Success else CheckOutcome.TransportFailure
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            classifyCheckFailure(t)
        }
    }

    /** Only the VPS /health 200. Redirects and 3xx/204 look like a portal. */
    internal fun provisionHealthAccepted(code: Int): Boolean = code == 200

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



