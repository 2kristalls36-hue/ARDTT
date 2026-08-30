package com.nonamevpn.app.deploy

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
import androidx.core.app.ServiceCompat
import com.nonamevpn.app.MainActivity
import com.nonamevpn.app.R
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.telemetry.TelemetryBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Keeps SSH deploy alive while the screen is off or the app is in the background,
 * and shows an ongoing shade notification with a progress bar.
 */
class DeployService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var deployJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fgReady = false
    @Volatile private var finished = false
    private lateinit var engine: DeployEngine

    override fun onCreate() {
        super.onCreate()
        engine = DeployEngine.get(this)
        ensureChannel()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.also { lock ->
            lock.setReferenceCounted(false)
            runCatching { lock.acquire(4 * 60 * 60 * 1000L) }
        }
        scope.launch {
            combine(engine.progress, engine.step, engine.isUpdate) { _, _, _ -> }
                .collect {
                    if (fgReady && !finished) publishOngoing()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                TelemetryBridge.deploy("deploy_fgs_cancel", engine.activeHostValue)
                engine.cancel()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                fgReady = true
                if (deployJob?.isActive != true) {
                    deployJob = scope.launch(Dispatchers.IO) { runDeploy() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runDeploy() {
        val host = engine.pendingHostLabel()
        TelemetryBridge.deploy(
            "deploy_fgs_started",
            host,
            JSONObject().put("is_update", engine.isUpdate.value),
        )
        val result = engine.runFromService()
        val success = result.isSuccess
        val message = result.fold(
            onSuccess = { it },
            onFailure = { t ->
                val cancelled = engine.log.value.any { line -> line.contains("Отменено") }
                if (cancelled) "Отменено" else (t.message?.take(240) ?: "Ошибка")
            },
        )
        finished = true
        publishFinished(success, message)
        TelemetryBridge.deploy(
            if (success) "deploy_fgs_finished" else "deploy_fgs_failed",
            host,
            JSONObject().put("message", message.take(240)),
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = buildOngoing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun publishOngoing() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildOngoing())
    }

    private fun publishFinished(success: Boolean, message: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildFinished(success, message))
    }

    private fun buildOngoing(): Notification {
        val isUpdate = engine.isUpdate.value
        val percent = DeployShade.progressPercent(engine.progress.value)
        val step = DeployShade.contentText(engine.step.value, engine.pendingHostLabel())
        val open = openAppIntent()
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DeployService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(DeployShade.title(isUpdate))
            .setContentText(step)
            .setSubText("$percent%")
            .setStyle(NotificationCompat.BigTextStyle().bigText(step))
            .setProgress(DeployShade.PROGRESS_MAX, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_deploy_cancel), cancel)
            .build()
    }

    private fun buildFinished(success: Boolean, message: String): Notification {
        val isUpdate = engine.isUpdate.value
        val text = DeployShade.finishedText(success, message)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(DeployShade.title(isUpdate))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            val id = engine.activeTargetId.value
            if (!id.isNullOrBlank()) putExtra(EXTRA_SERVER_ID, id)
        }
        return PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_deploy),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                description = "Прогресс установки и обновления стека на VPS"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    companion object {
        private const val TAG = "DeployService"
        private const val CHANNEL_ID = "ardtt_deploy_v1"
        private const val NOTIF_ID = 4201
        private const val WAKELOCK_TAG = "ardtt:deploy"
        const val ACTION_START = "com.nonamevpn.app.deploy.START"
        const val ACTION_CANCEL = "com.nonamevpn.app.deploy.CANCEL"
        const val ACTION_OPEN = "com.nonamevpn.app.OPEN_DEPLOY"
        const val EXTRA_SERVER_ID = "server_id"

        fun start(context: Context) {
            val intent = Intent(context, DeployService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            AppLog.i(TAG, "startForegroundService")
        }
    }
}
