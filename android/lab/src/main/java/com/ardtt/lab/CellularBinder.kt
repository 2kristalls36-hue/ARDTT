package com.ardtt.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps LTE up and exposes that [Network] so SSH/probes go out the SIM,
 * not Wi‑Fi and not an ARDTT VPN. ARDTT itself is not changed.
 */
class CellularBinder(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val held = AtomicReference<Network?>(null)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                held.set(network)
            }

            override fun onLost(network: Network) {
                held.compareAndSet(network, null)
            }
        }
        callback = cb
        runCatching { cm.requestNetwork(request, cb) }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        held.set(null)
    }

    fun network(): Network? = held.get() ?: findCellular()

    fun await(timeoutMs: Long = 5_000): Network? {
        val already = network()
        if (already != null) return already
        val latch = CountDownLatch(1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = network()
            if (n != null) return n
            latch.await(200, TimeUnit.MILLISECONDS)
        }
        return network()
    }

    fun bindSocket(socket: Socket) {
        network()?.bindSocket(socket)
    }

    fun tcp(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                bindSocket(socket)
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findCellular(): Network? {
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }
}
