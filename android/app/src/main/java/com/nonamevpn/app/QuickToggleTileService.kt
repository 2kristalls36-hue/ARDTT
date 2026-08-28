package com.nonamevpn.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager

/**
 * Quick Settings tile — qWDTT-parity toggle wired to [ConnectionManager].
 */
class QuickToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(isRunning())
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            val app = applicationContext
            val conn = ConnectionManager.get(app)
            if (isRunning()) {
                conn.disconnect()
                updateTile(false)
                return
            }
            val prep = runCatching { VpnService.prepare(this) }.getOrNull()
            if (prep != null) {
                Toast.makeText(
                    this,
                    "Разрешите ARDTT создать VPN-подключение",
                    Toast.LENGTH_LONG,
                ).show()
                openVpnPermissionActivity()
            } else {
                conn.connect()
                updateTile(true)
            }
        }.onFailure { e ->
            Log.e(TAG, "QS tile onClick failed", e)
        }
    }

    private fun isRunning(): Boolean {
        val state = ConnectionManager.getOrNull()?.ui?.value?.state ?: return false
        return state == ConnState.Connected ||
            state == ConnState.Connecting ||
            state == ConnState.PausedTrustedWifi
    }

    private fun updateTile(running: Boolean) {
        val tile = qsTile ?: return
        if (running) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "ARDTT: Вкл"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Активен"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "ARDTT: Выкл"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Отключен"
            }
        }
        tile.updateTile()
    }

    private fun openVpnPermissionActivity() {
        openActivity(Intent(this, VpnPermissionActivity::class.java), 101)
    }

    private fun openActivity(intent: Intent, requestCode: Int) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to open activity", e)
        }
    }

    companion object {
        private const val TAG = "QuickToggleTile"

        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    requestListeningState(
                        context,
                        ComponentName(context, QuickToggleTileService::class.java),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestListeningState failed", e)
                }
            }
        }
    }
}
