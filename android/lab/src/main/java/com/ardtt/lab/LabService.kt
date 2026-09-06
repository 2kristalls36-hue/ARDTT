package com.ardtt.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LabUiState(
    val host: String = LabProtocol.DEFAULT_SSH_HOST,
    val sshPort: String = LabProtocol.DEFAULT_SSH_PORT.toString(),
    val user: String = LabProtocol.DEFAULT_SSH_USER,
    val password: String = "",
    val remotePort: String = LabProtocol.DEFAULT_REMOTE_PORT.toString(),
    val wanted: Boolean = false,
    val connecting: Boolean = false,
    val online: Boolean = false,
    val agentSeen: Boolean = false,
    val lastError: String? = null,
    val log: List<String> = emptyList(),
    val wifiOn: Boolean = false,
    val cellular: Boolean = false,
    val operator: String = "",
    val vpn: Boolean = false,
    val ardttInstalled: Boolean = false,
    val screenGranted: Boolean = false,
)

class LabService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: LabSettings
    private lateinit var binderNet: CellularBinder
    private lateinit var commands: LabCommandServer
    private lateinit var ssh: SshBridge
    private lateinit var capture: ScreenCapture
    private var loop: Job? = null
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = LabSettings(this)
        binderNet = CellularBinder(this)
        capture = ScreenCapture(this)
        commands = LabCommandServer(
            context = this,
            binder = binderNet,
            onLog = { append(it) },
            onAgent = { _state.update { it.copy(agentSeen = true) } },
            screenshot = { capture.capturePng() },
        )
        ssh = SshBridge(binderNet, onLog = { append(it) })
        val saved = settings.load()
        _state.update {
            it.copy(
                host = saved.host,
                sshPort = saved.sshPort.toString(),
                user = saved.user,
                password = saved.password,
                remotePort = saved.remotePort.toString(),
                screenGranted = ScreenCaptureStore.granted(),
            )
        }
        refreshSnapshot()
        startForegroundNotification(getString(R.string.notify_connecting))
        binderNet.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(targetFrom(intent) ?: currentTarget())
            ACTION_DISCONNECT -> disconnect()
            ACTION_REFRESH -> refreshSnapshot()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        binderNet.stop()
        commands.stop()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect(target: LabTarget) {
        settings.save(target)
        _state.update {
            it.copy(
                host = target.host,
                sshPort = target.sshPort.toString(),
                user = target.user,
                password = target.password,
                remotePort = target.remotePort.toString(),
                wanted = true,
                connecting = true,
                lastError = null,
            )
        }
        loop?.cancel()
        loop = scope.launch { maintain(target) }
    }

    private fun disconnect() {
        loop?.cancel()
        loop = null
        watch?.cancel()
        watch = null
        runCatching { ssh.disconnect() }
        _state.update {
            it.copy(wanted = false, connecting = false, online = false, agentSeen = false)
        }
        runCatching { wake?.release() }
        wake = null
        notifyText(getString(R.string.notify_connecting))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun maintain(target: LabTarget) {
        acquireWake()
        while (scope.isActive && _state.value.wanted) {
            try {
                _state.update { it.copy(connecting = true, lastError = null) }
                binderNet.await(5_000)
                if (commands.localPort == 0) commands.start()
                ssh.connect(target, commands.localPort)
                _state.update { it.copy(connecting = false, online = true) }
                notifyText(getString(R.string.notify_online))
                append("готово: ssh на сервер, затем ardtt-lab attach")
                startWatch()
                while (scope.isActive && _state.value.wanted && ssh.connected()) {
                    delay(3_000)
                    refreshSnapshot()
                }
                if (_state.value.wanted) append("SSH оборвался, переподключение…")
            } catch (err: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
                        online = false,
                        lastError = err.message ?: err.javaClass.simpleName,
                    )
                }
                append("ошибка: ${err.message ?: err.javaClass.simpleName}")
            }
            runCatching { ssh.disconnect() }
            if (_state.value.wanted) delay(4_000)
        }
    }

    private fun startWatch() {
        watch?.cancel()
        watch = scope.launch {
            while (isActive && _state.value.online) {
                delay(3_000)
                val snap = DeviceSnapshot.capture(this@LabService, binderNet)
                commands.broadcast(LabProtocol.event("status", snap.toMap()))
            }
        }
    }

    private fun refreshSnapshot() {
        val snap = DeviceSnapshot.capture(this, binderNet)
        _state.update {
            it.copy(
                wifiOn = snap.wifiOn,
                cellular = snap.cellular,
                operator = snap.operator,
                vpn = snap.vpn,
                ardttInstalled = snap.ardttInstalled,
                screenGranted = ScreenCaptureStore.granted(),
            )
        }
    }

    private fun currentTarget(): LabTarget {
        val s = _state.value
        return LabTarget(
            host = s.host.trim(),
            sshPort = s.sshPort.toIntOrNull() ?: 22,
            user = s.user.trim(),
            password = s.password,
            remotePort = s.remotePort.toIntOrNull() ?: LabProtocol.DEFAULT_REMOTE_PORT,
        )
    }

    private fun targetFrom(intent: Intent): LabTarget? {
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        if (host.isEmpty()) return null
        return LabTarget(
            host = host,
            sshPort = intent.getIntExtra(EXTRA_SSH_PORT, 22),
            user = intent.getStringExtra(EXTRA_USER).orEmpty(),
            password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
            remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, LabProtocol.DEFAULT_REMOTE_PORT),
        )
    }

    private fun append(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update { cur ->
            val next = ArrayDeque(cur.log)
            next.addLast("$stamp  $line")
            while (next.size > 80) next.removeFirst()
            cur.copy(log = next.toList())
        }
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ardtt-lab:ssh").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notify_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun notifyText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.ardtt.lab.CONNECT"
        const val ACTION_DISCONNECT = "com.ardtt.lab.DISCONNECT"
        const val ACTION_REFRESH = "com.ardtt.lab.REFRESH"
        const val EXTRA_HOST = "host"
        const val EXTRA_SSH_PORT = "ssh_port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_REMOTE_PORT = "remote_port"
        private const val CHANNEL = "ardtt_lab"
        private const val NOTIFY_ID = 7422

        private val _state = MutableStateFlow(LabUiState())
        val state: StateFlow<LabUiState> = _state.asStateFlow()

        @Volatile
        private var instance: LabService? = null

        fun startConnect(context: Context, target: LabTarget) {
            val intent = Intent(context, LabService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, target.host)
                putExtra(EXTRA_SSH_PORT, target.sshPort)
                putExtra(EXTRA_USER, target.user)
                putExtra(EXTRA_PASSWORD, target.password)
                putExtra(EXTRA_REMOTE_PORT, target.remotePort)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LabService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(intent)
        }
    }
}
