package com.nonamevpn.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Single VpnService for Path A (AWG) and Path B (RAW/WRAP). Backends are mutually exclusive.
 */
class VpnTunnelService : VpnService(), TunEstablisher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var backend: TunnelBackend? = null
    private var sessionJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                ConnectionManager.getOrNull()?.onServiceStopped()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        val config = TunnelSessionHolder.config
        val path = config?.path ?: VpnPath.Direct

        startForeground(NOTIF_ID, buildNotification(path))
        ConnectionManager.getOrNull()?.onServiceStarted(path)
        AppLog.i(TAG, "session start path=$path")

        backend?.stop()
        val chosen: TunnelBackend = when (path) {
            VpnPath.Direct -> DirectBackend()
            VpnPath.Bypass -> BypassBackend()
        }
        backend = chosen

        // Path A: TUN now. Path B: go_client dials first, then establishTun() after RAWCONF.
        val fd: ParcelFileDescriptor? = when (path) {
            VpnPath.Direct -> {
                val address = config?.tunAddress?.substringBefore('/')?.takeIf { it.isNotBlank() }
                    ?: config?.profile?.direct?.address?.substringBefore('/')
                    ?: "10.8.0.2"
                val mtu = config?.profile?.direct?.mtu ?: 1280
                val dns = config?.profile?.direct?.dns?.firstOrNull() ?: "1.1.1.1"
                establishTun(address, dns, mtu).also { created ->
                    if (created == null) {
                        AppLog.e(TAG, "TUN establish failed")
                        ConnectionManager.getOrNull()?.onTunnelFailed("Не удалось создать TUN (отклонён VPN?)")
                        stopSelf()
                    } else {
                        AppLog.i(TAG, "TUN ok ip=$address mtu=$mtu")
                    }
                }
            }
            VpnPath.Bypass -> null
        }
        if (path == VpnPath.Direct && fd == null) return

        sessionJob?.cancel()
        sessionJob = scope.launch {
            try {
                chosen.start(this@VpnTunnelService, fd, config ?: return@launch) { state ->
                    Log.i(TAG, "backend state=$state")
                    when (state) {
                        is TunnelBackendState.Running -> {
                            AppLog.i(TAG, "Running path=$path")
                            ConnectionManager.getOrNull()?.onTunnelRunning(path)
                        }
                        is TunnelBackendState.Failed -> {
                            AppLog.e(TAG, "Failed: ${state.message}")
                            ConnectionManager.getOrNull()?.onTunnelFailed(state.message)
                            stopSelf()
                        }
                        is TunnelBackendState.Stopped -> Unit
                        is TunnelBackendState.Starting -> AppLog.i(TAG, "Starting…")
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "backend crash: ${t.message}")
                ConnectionManager.getOrNull()?.onTunnelFailed(t.message ?: "tunnel crash")
                stopSelf()
            }
        }
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
        sessionJob?.cancel()
        sessionJob = null
        backend?.stop()
        backend = null
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopSession()
        scope.cancel()
        ConnectionManager.getOrNull()?.onServiceStopped()
        super.onDestroy()
    }

    private fun buildNotification(path: VpnPath): Notification {
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
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VpnTunnel"
        const val ACTION_START = "com.nonamevpn.app.action.START"
        const val ACTION_STOP = "com.nonamevpn.app.action.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_HIDE_IP = "hide_ip"
        const val EXTRA_TUN_ADDRESS = "tun_address"
        private const val NOTIF_ID = 42
    }
}
