package com.nonamevpn.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.nonamevpn.app.MainActivity
import com.nonamevpn.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VpnService-заглушка: поднимает tun + keepalive-сессию без реального AWG/RAW I/O.
 * Настоящие драйверы подключатся в следующих итерациях.
 */
class VpnTunnelService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var sessionJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ConnectionManager.getOrNull()?.onServiceStopped()
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startSession(intent)
        }
        return START_STICKY
    }

    private fun startSession(intent: Intent?) {
        val path = runCatching {
            VpnPath.valueOf(intent?.getStringExtra(EXTRA_PATH) ?: VpnPath.Direct.name)
        }.getOrDefault(VpnPath.Direct)
        val address = intent?.getStringExtra(EXTRA_TUN_ADDRESS)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?: "10.8.0.2"

        startForeground(NOTIF_ID, buildNotification(path))
        ConnectionManager.getOrNull()?.onServiceStarted(path)

        if (tun == null) {
            tun = Builder()
                .setSession("nonameVPN")
                .setMtu(1280)
                .addAddress(address, 32)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .establish()
        }

        sessionJob?.cancel()
        sessionJob = scope.launch {
            // Keepalive stub until AWG/RAW engines are wired.
            while (isActive) {
                delay(30_000)
            }
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
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
        const val ACTION_START = "com.nonamevpn.app.action.START"
        const val ACTION_STOP = "com.nonamevpn.app.action.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_HIDE_IP = "hide_ip"
        const val EXTRA_TUN_ADDRESS = "tun_address"
        private const val NOTIF_ID = 42
    }
}
