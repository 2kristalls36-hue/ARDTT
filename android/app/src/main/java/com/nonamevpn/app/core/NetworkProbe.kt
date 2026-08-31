package com.nonamevpn.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
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
        provisionOk: Boolean,
    ): ProbeResult = NetworkProbePolicy.classify(
        systemOnline = systemOnline,
        yandexOk = yandexOk,
        bigtechOk = bigtechOk,
        captive = captive,
        provisionOk = provisionOk,
    )

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



