package com.nonamevpn.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nonamevpn.app.MainActivity
import com.nonamevpn.app.R
import com.nonamevpn.app.tunnel.BypassBackend
import com.nonamevpn.app.tunnel.DirectBackend
import com.nonamevpn.app.tunnel.TunEstablisher
import com.nonamevpn.app.tunnel.TunnelBackend
import com.nonamevpn.app.tunnel.TunnelBackendState
import com.nonamevpn.app.tunnel.TunnelSessionHolder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single VpnService for Path A (AWG) and Path B (RAW/WRAP). Backends are mutually exclusive.
 *
 * Soft reconnect (WDTT-Plus style): on underlying Wi‑Fi/LTE handover, restart the
 * transport backend without requiring the user to tap Stop/Connect. The VPN
 * service stays foreground; only Direct/Bypass backends are recycled.
 */
class VpnTunnelService : VpnService(), TunEstablisher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var backend: TunnelBackend? = null
    private var sessionJob: Job? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val activeNetworks = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var lastValidatedNetworkId: Long? = null
    @Volatile private var stableNetworkWasLost = false
    @Volatile private var handoverPreviousNetworkId: Long? = null
    @Volatile private var stableNetworkReconnectPending = false
    @Volatile private var networkChangeJob: Job? = null
    @Volatile private var userStopRequested = false
    @Volatile private var softRestartInProgress = false
    @Volatile private var backendEpoch: Int = 0
    @Volatile private var tunnelSessionActive = false
    @Volatile private var lastSoftRestartAtMs = 0L
    @Volatile private var softRestartCount = 0
    private val recoveryPolicy = transportRecoveryPolicy()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userStopRequested = true
                cancelNetworkReconnect()
                stopSession()
                ConnectionManager.getOrNull()?.onServiceStopped()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_TRANSPORT -> {
                if (tunnelSessionActive && !userStopRequested) {
                    requestSoftRestart(
                        reason = intent.getStringExtra(EXTRA_RESTART_REASON)
                            ?: "Запрошено переподключение транспорта",
                        force = true,
                    )
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                userStopRequested = false
                startSession()
            }
        }
        return START_STICKY
    }

    private fun startSession() {
        val config = TunnelSessionHolder.config
        val path = config?.path ?: VpnPath.Direct

        startForeground(NOTIF_ID, buildNotification(path, getString(R.string.notif_running)))
        ConnectionManager.getOrNull()?.onServiceStarted(path)
        AppLog.i(TAG, "session start path=$path")

        tunnelSessionActive = true
        softRestartInProgress = false
        setupNetworkCallback()
        launchBackend(path, softRestart = false)
    }

    private fun launchBackend(path: VpnPath, softRestart: Boolean) {
        val config = TunnelSessionHolder.config
        if (config == null) {
            AppLog.e(TAG, "No session config")
            softRestartInProgress = false
            ConnectionManager.getOrNull()?.onTunnelFailed("Нет конфигурации сессии")
            stopSelf()
            return
        }

        val epoch = ++backendEpoch
        sessionJob?.cancel()
        backend?.stop()
        backend = null
        runCatching { tun?.close() }
        tun = null

        val chosen: TunnelBackend = when (path) {
            VpnPath.Direct -> DirectBackend()
            VpnPath.Bypass -> BypassBackend()
        }
        backend = chosen

        val fd: ParcelFileDescriptor? = when (path) {
            VpnPath.Direct -> {
                val address = config.tunAddress.substringBefore('/').takeIf { it.isNotBlank() }
                    ?: config.profile?.direct?.address?.substringBefore('/')
                    ?: "10.8.0.2"
                val mtu = config.profile?.direct?.mtu ?: 1280
                val dns = config.profile?.direct?.dns?.firstOrNull() ?: "1.1.1.1"
                establishTun(address, dns, mtu).also { created ->
                    if (created == null) {
                        AppLog.e(TAG, "TUN establish failed")
                        softRestartInProgress = false
                        ConnectionManager.getOrNull()?.onTunnelFailed("Не удалось создать TUN (отклонён VPN?)")
                        if (!softRestart) stopSelf()
                    } else {
                        AppLog.i(TAG, "TUN ok ip=$address mtu=$mtu soft=$softRestart")
                    }
                }
            }
            VpnPath.Bypass -> null
        }
        if (path == VpnPath.Direct && fd == null) return

        sessionJob = scope.launch {
            try {
                chosen.start(this@VpnTunnelService, fd, config) { state ->
                    if (epoch != backendEpoch) {
                        AppLog.w(TAG, "stale backend callback epoch=$epoch current=$backendEpoch state=$state")
                        return@start
                    }
                    Log.i(TAG, "backend state=$state soft=$softRestartInProgress")
                    when (state) {
                        is TunnelBackendState.Running -> {
                            AppLog.i(TAG, "Running path=$path")
                            softRestartInProgress = false
                            ConnectionManager.getOrNull()?.onTunnelRunning(path)
                            updateNotification(path, getString(R.string.notif_running))
                        }
                        is TunnelBackendState.Failed -> {
                            if (userStopRequested) {
                                AppLog.w(TAG, "Ignoring failure after user stop: ${state.message}")
                                return@start
                            }
                            AppLog.e(TAG, "Failed: ${state.message}")
                            softRestartInProgress = false
                            ConnectionManager.getOrNull()?.onTunnelFailed(state.message)
                            stopSelf()
                        }
                        is TunnelBackendState.Stopped -> Unit
                        is TunnelBackendState.Starting -> {
                            AppLog.i(TAG, if (softRestart) "Soft restart starting…" else "Starting…")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    AppLog.i(TAG, "session cancelled (normal stop/restart)")
                    throw t
                }
                if (epoch != backendEpoch || userStopRequested) {
                    AppLog.w(TAG, "Ignoring crash from stale/stop backend: ${t.message}")
                    return@launch
                }
                AppLog.e(TAG, "backend crash: ${t.message}")
                softRestartInProgress = false
                ConnectionManager.getOrNull()?.onTunnelFailed(t.message ?: "tunnel crash")
                stopSelf()
            }
        }
    }

    /**
     * Soft transport restart — keep VpnService / foreground, recycle backend.
     * Mirrors WDTT-Plus [TunnelManager.restartTransport].
     */
    private fun requestSoftRestart(reason: String, force: Boolean = false) {
        if (!tunnelSessionActive || userStopRequested) return
        val now = System.currentTimeMillis()
        if (
            !shouldAttemptSoftRestartNow(
                nowMs = now,
                lastSoftRestartAtMs = lastSoftRestartAtMs,
                minIntervalMs = recoveryPolicy.reconnectMinIntervalMs,
                softRestartCount = softRestartCount,
                force = force,
            )
        ) {
            AppLog.i(TAG, "soft restart deferred (cooldown): $reason")
            return
        }
        lastSoftRestartAtMs = now
        softRestartCount++
        softRestartInProgress = true
        val path = TunnelSessionHolder.config?.path ?: return
        AppLog.i(TAG, "soft restart #$softRestartCount: $reason")
        ConnectionManager.getOrNull()?.onTransportRestarting(reason)
        updateNotification(path, "Переподключение транспорта…")

        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            delay(recoveryPolicy.processRestartDelayMs)
            if (!tunnelSessionActive || userStopRequested) {
                softRestartInProgress = false
                return@launch
            }
            launchBackend(path, softRestart = true)
        }
    }

    private fun setupNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        activeNetworks.clear()
        lastValidatedNetworkId = null
        stableNetworkWasLost = false

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworks.add(network)
                if (
                    shouldScheduleAvailableNetworkHandover(
                        previousNetworkWasLost = stableNetworkWasLost,
                        availableRealNetworkCount = activeNetworks.size,
                    )
                ) {
                    scheduleUnderlyingNetworkReconnect(
                        "Android обнаружил доступную сеть после потери прежней",
                    )
                }
            }

            override fun onLost(network: Network) {
                val networkLostAt = System.currentTimeMillis()
                activeNetworks.remove(network)
                val lostId = network.networkHandle
                val lostCurrentValidated = lastValidatedNetworkId == lostId
                val lostPreviousHandover = handoverPreviousNetworkId == lostId
                if (lostPreviousHandover) handoverPreviousNetworkId = null
                if (lostCurrentValidated) lastValidatedNetworkId = null

                val shouldTrack = shouldTrackUnderlyingNetworkLoss(
                    tunnelRunning = tunnelSessionActive,
                    userStopRequested = userStopRequested,
                )
                if (lostCurrentValidated || lostPreviousHandover) {
                    stableNetworkWasLost = shouldTrack
                }
                if (activeNetworks.isEmpty() && shouldTrack) {
                    stableNetworkWasLost = true
                    AppLog.i(TAG, "underlying network lost — waiting")
                    val path = TunnelSessionHolder.config?.path ?: VpnPath.Direct
                    updateNotification(path, "Ожидание сети…")
                    ConnectionManager.getOrNull()?.onUnderlyingNetworkLost()
                } else if (
                    (lostCurrentValidated || lostPreviousHandover) &&
                    shouldScheduleAvailableNetworkHandover(
                        previousNetworkWasLost = stableNetworkWasLost,
                        availableRealNetworkCount = activeNetworks.size,
                    )
                ) {
                    scheduleUnderlyingNetworkReconnect(
                        reason = "Android переключает VPN на другую доступную сеть",
                        evidenceSinceMs = networkLostAt,
                    )
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val usable =
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!usable) return
                activeNetworks.add(network)
                val id = network.networkHandle
                val previous = lastValidatedNetworkId
                val transition = classifyValidatedNetworkTransition(
                    previousNetworkId = previous,
                    currentNetworkId = id,
                    previousNetworkWasLost = stableNetworkWasLost,
                )
                lastValidatedNetworkId = id
                when (transition) {
                    ValidatedNetworkTransition.HANDOVER ->
                        scheduleUnderlyingNetworkReconnect(
                            reason = "Android подтвердил новую рабочую сеть",
                            previousNetworkId = previous,
                        )
                    ValidatedNetworkTransition.INITIAL ->
                        AppLog.i(TAG, "validated underlying network id=$id")
                    ValidatedNetworkTransition.UNCHANGED -> Unit
                }
            }
        }

        // Only real (non-VPN) internet networks — same filter as WDTT-Plus.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, networkCallback!!) }
            .onFailure { AppLog.e(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun scheduleUnderlyingNetworkReconnect(
        reason: String,
        previousNetworkId: Long? = null,
        evidenceSinceMs: Long = System.currentTimeMillis(),
    ) {
        if (
            !shouldTrackUnderlyingNetworkLoss(
                tunnelRunning = tunnelSessionActive,
                userStopRequested = userStopRequested,
            )
        ) {
            stableNetworkWasLost = false
            return
        }
        if (
            !shouldStartUnderlyingNetworkCheck(
                checkPending = stableNetworkReconnectPending,
                currentJobActive = networkChangeJob?.isActive == true,
            )
        ) {
            if (handoverPreviousNetworkId == null && previousNetworkId != null) {
                handoverPreviousNetworkId = previousNetworkId
            }
            AppLog.i(TAG, "$reason; handover check already pending")
            return
        }
        stableNetworkWasLost = false
        stableNetworkReconnectPending = true
        handoverPreviousNetworkId = previousNetworkId
        AppLog.i(TAG, "$reason — settle ${recoveryPolicy.networkSettleDelayMs}ms")

        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            try {
                delay(recoveryPolicy.networkSettleDelayMs)
                if (
                    !shouldRunUnderlyingNetworkReconnect(
                        tunnelRunning = tunnelSessionActive,
                        userStopRequested = userStopRequested,
                        softRestartInProgress = softRestartInProgress,
                        realNetworkAvailable = activeNetworks.isNotEmpty(),
                    )
                ) {
                    AppLog.i(TAG, "skip reconnect: session state changed")
                    return@launch
                }
                requestSoftRestart(reason = "[СЕТЬ] $reason", force = false)
            } finally {
                stableNetworkReconnectPending = false
                handoverPreviousNetworkId = null
            }
        }
    }

    private fun cancelNetworkReconnect() {
        networkChangeJob?.cancel()
        networkChangeJob = null
        stableNetworkReconnectPending = false
        softRestartInProgress = false
        teardownNetworkCallback()
    }

    private fun teardownNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        networkCallback = null
        activeNetworks.clear()
        lastValidatedNetworkId = null
    }

    override fun establishTun(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor? {
        runCatching { tun?.close() }
        tun = null
        val builder = Builder()
            .setSession("nonameVPN")
            .setMtu(mtu.coerceIn(576, 1500))
            .addAddress(ip.substringBefore('/'), 32)
            .addRoute("0.0.0.0", 0)
        dnsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { d ->
            runCatching { builder.addDnsServer(d) }
        }
        // Keep our process (incl. libclient.so) off the VPN so TURN/TCP dial works.
        runCatching { builder.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setBlocking(true)
        val pfd = builder.establish()
        tun = pfd
        Log.i(TAG, "TUN established ip=$ip mtu=$mtu fd=${pfd?.fd}")
        return pfd
    }

    private fun stopSession() {
        tunnelSessionActive = false
        cancelNetworkReconnect()
        sessionJob?.cancel()
        sessionJob = null
        backend?.stop()
        backend = null
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        userStopRequested = true
        stopSession()
        scope.cancel()
        ConnectionManager.getOrNull()?.onServiceStopped()
        super.onDestroy()
    }

    private fun updateNotification(path: VpnPath, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(path, text))
    }

    private fun buildNotification(path: VpnPath, text: String): Notification {
        val channelId = "nvpn_tunnel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel_tunnel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (path) {
            VpnPath.Direct -> getString(R.string.notif_direct)
            VpnPath.Bypass -> getString(R.string.notif_bypass)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VpnTunnel"
        const val ACTION_START = "com.nonamevpn.app.action.START"
        const val ACTION_STOP = "com.nonamevpn.app.action.STOP"
        const val ACTION_RESTART_TRANSPORT = "com.nonamevpn.app.action.RESTART_TRANSPORT"
        const val EXTRA_PATH = "path"
        const val EXTRA_HIDE_IP = "hide_ip"
        const val EXTRA_TUN_ADDRESS = "tun_address"
        const val EXTRA_RESTART_REASON = "restart_reason"
        private const val NOTIF_ID = 42
    }
}
