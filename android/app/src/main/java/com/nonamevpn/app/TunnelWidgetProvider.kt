package com.nonamevpn.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import android.widget.Toast
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.VpnLiveStats

class TunnelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val running = isRunning(context)
        val stats = if (running) widgetStatsText() else null
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, running, stats))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val app = context.applicationContext
        val conn = ConnectionManager.get(app)
        if (isRunning(app)) {
            conn.disconnect()
            updateWidgetState(app, running = false, statsText = null)
            QuickToggleTileService.requestTileUpdate(app)
        } else {
            val prep = runCatching { VpnService.prepare(app) }.getOrNull()
            if (prep != null) {
                Toast.makeText(
                    app,
                    "Разрешите ARDTT создать VPN-подключение",
                    Toast.LENGTH_LONG,
                ).show()
                openVpnPermission(app)
            } else {
                conn.connect()
                QuickToggleTileService.requestTileUpdate(app)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.nonamevpn.app.ACTION_TOGGLE"

        fun updateWidgetState(context: Context, running: Boolean, statsText: String?) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TunnelWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, running, statsText)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        fun pushFromConnection(context: Context) {
            val running = isRunning(context)
            val stats = if (running) widgetStatsText() else null
            updateWidgetState(context, running, stats)
            QuickToggleTileService.requestTileUpdate(context)
        }

        private fun isRunning(context: Context): Boolean {
            val state = ConnectionManager.getOrNull()?.ui?.value?.state
                ?: return false
            return state == ConnState.Connected ||
                state == ConnState.Connecting ||
                state == ConnState.PausedTrustedWifi
        }

        private fun widgetStatsText(): String {
            val rates = VpnLiveStats.formatRateLine(VpnLiveStats.downBps, VpnLiveStats.upBps)
            val path = ConnectionManager.getOrNull()?.ui?.value?.activePath?.name
            return when (path) {
                "Direct" -> "$rates · прямое"
                "Bypass" -> "$rates · обход"
                else -> rates.ifBlank { "Туннель запущен" }
            }
        }

        private fun buildViews(context: Context, running: Boolean, statsText: String?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.tunnel_widget)
            if (running) {
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_green)
                views.setTextViewText(R.id.widget_status_title, context.getString(R.string.widget_connected))
                views.setTextViewText(
                    R.id.widget_stats_text,
                    statsText ?: "Туннель запущен",
                )
                views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_pause)
                views.setInt(
                    R.id.widget_toggle_button,
                    "setColorFilter",
                    android.graphics.Color.parseColor("#FF5252"),
                )
            } else {
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_gray)
                views.setTextViewText(R.id.widget_status_title, context.getString(R.string.widget_disconnected))
                views.setTextViewText(
                    R.id.widget_stats_text,
                    context.getString(R.string.widget_tap_to_connect),
                )
                views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_play)
                views.setInt(
                    R.id.widget_toggle_button,
                    "setColorFilter",
                    android.graphics.Color.parseColor("#3DDC84"),
                )
            }
            val intent = Intent(context, TunnelWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_toggle_button, pi)
            return views
        }

        private fun openVpnPermission(context: Context) {
            val intent = Intent(context, VpnPermissionActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            )
            runCatching {
                PendingIntent.getActivity(
                    context,
                    201,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ).send()
            }
        }
    }
}
