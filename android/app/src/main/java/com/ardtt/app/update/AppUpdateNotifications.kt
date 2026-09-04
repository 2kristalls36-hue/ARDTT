package com.ardtt.app.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ardtt.app.MainActivity
import com.ardtt.app.R

internal class AppUpdateNotifications(
    private val context: Context,
) {
    fun render(ui: AppUpdateController.Ui) {
        if (!canPostNotifications()) return
        ensureChannel()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = when {
            ui.downloading -> buildDownloading(ui)
            ui.downloadedFile != null -> buildDownloaded(ui)
            ui.available?.isNewer == true -> buildAvailable(ui.available)
            else -> null
        }
        if (notification == null) {
            nm.cancel(NOTIF_ID)
        } else {
            nm.notify(NOTIF_ID, notification)
        }
    }

    private fun buildAvailable(info: AppUpdateInfo): Notification {
        val title = "Доступно обновление ${info.versionName}"
        val text = "Нажмите, чтобы открыть настройки. Или начните скачивание сразу."
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSettingsIntent())
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Скачать", actionIntent(AppUpdateNotificationReceiver.ACTION_DOWNLOAD))
            .build()
    }

    private fun buildDownloading(ui: AppUpdateController.Ui): Notification {
        val pct = (ui.progress.coerceIn(0f, 1f) * 100f).toInt()
        val text = if (pct > 0) "Скачивание APK: $pct%" else "Подготовка скачивания…"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle("Загрузка обновления")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSettingsIntent())
            .setProgress(100, pct, pct <= 0)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Отмена", actionIntent(AppUpdateNotificationReceiver.ACTION_CANCEL_DOWNLOAD))
            .build()
    }

    private fun buildDownloaded(ui: AppUpdateController.Ui): Notification {
        val version = ui.available?.versionName?.takeIf { it.isNotBlank() }
        val title = if (version != null) {
            "Обновление $version загружено"
        } else {
            "Обновление загружено"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(title)
            .setContentText("Нажмите для открытия настроек и установки.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Нажмите для открытия настроек и установки."))
            .setContentIntent(openSettingsIntent())
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Установить", actionIntent(AppUpdateNotificationReceiver.ACTION_INSTALL_UPDATE))
            .build()
    }

    private fun openSettingsIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_UPDATE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQ_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(context, AppUpdateNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Обновления приложения",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "ardtt_app_updates_v1"
        private const val NOTIF_ID = 4407
        private const val REQ_OPEN = 44070
    }
}
