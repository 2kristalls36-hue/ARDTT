package com.nonamevpn.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.ui.PendingUiAction
import com.nonamevpn.app.ui.qsTileOpensCallHashSettings

/**
 * Quick Settings tile — qWDTT-parity toggle wired to [ConnectionManager].
 * Without a call hash the tile stays inactive and opens «Код звонка».
 */
class QuickToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            val app = applicationContext
            val conn = ConnectionManager.get(app)
            val hasHash = conn.ui.value.hasCallHash
            if (qsTileOpensCallHashSettings(hasHash, isRunning())) {
                openCallHashSettings()
                updateTile()
                return
            }
            if (isRunning()) {
                conn.disconnect()
                updateTile()
                return
            }
            val prep = runCatching { VpnService.prepare(this) }.getOrNull()
            if (prep != null) {
                Toast.makeText(
                    this,
                    "Разрешите ARDTT создать туннель",
                    Toast.LENGTH_LONG,
                ).show()
                openVpnPermissionActivity()
            } else {
                conn.connect()
                updateTile()
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

    private fun updateTile() {
        val tile = qsTile ?: return
        val hasHash = ConnectionManager.getOrNull()?.ui?.value?.hasCallHash == true
        val running = isRunning()
        tile.label = "ARDTT"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_custom)
        when {
            running -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Подключено"
            }
            !hasHash -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Код звонка"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Отключено"
            }
        }
        tile.updateTile()
    }

    private fun openCallHashSettings() {
        PendingUiAction.requestCallHashSettings()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CALL_HASH
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        openActivity(intent, 102)
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
