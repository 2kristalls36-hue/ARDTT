package com.ardtt.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.qsTileOpensCallHashSettings
import com.ardtt.app.ui.qsToggleTileSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile — qWDTT-parity toggle wired to [ConnectionManager].
 * Without a call hash the tile stays inactive and opens «Код звонка».
 */
class QuickToggleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            val running = isRunning()
            // Only trust in-process UI for the call-hash gate. After a process death
            // ConnectionManager has no profile yet — trampoline like the widget.
            val hasHash = ConnectionManager.getOrNull()?.ui?.value?.hasCallHash
            if (hasHash == false && qsTileOpensCallHashSettings(false, running)) {
                openCallHashSettings()
                updateTile()
                return
            }
            // WidgetToggleActivity loads the active profile and VPN consent on cold start.
            openActivity(
                Intent(this, WidgetToggleActivity::class.java).apply {
                    flags = widgetToggleLaunchFlags()
                },
                103,
            )
        }.onFailure { e ->
            Log.e(TAG, "QS tile onClick failed", e)
        }
    }

    private fun isRunning(): Boolean {
        val state = ConnectionManager.getOrNull()?.ui?.value?.state ?: return false
        return state.holdsUserSession()
    }

    private fun updateTile() {
        val hasHash = ConnectionManager.getOrNull()?.ui?.value?.hasCallHash == true
        val running = isRunning()
        val tile = qsTile ?: return
        tile.label = "ARDTT"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_custom)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = qsToggleTileSubtitle(hasHash, running, profileName = null)
        }
        tile.updateTile()
        scope.launch {
            val profileName = runCatching {
                QsProfileSwitch.snapshot(applicationContext).active?.name
            }.getOrNull()
            val latest = qsTile ?: return@launch
            latest.label = "ARDTT"
            latest.icon = Icon.createWithResource(this@QuickToggleTileService, R.drawable.ic_tile_custom)
            latest.state = if (isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            val hash = ConnectionManager.getOrNull()?.ui?.value?.hasCallHash == true
            if (Build.VERSION.SDK_INT >= 29) {
                latest.subtitle = qsToggleTileSubtitle(hash, isRunning(), profileName)
            }
            latest.updateTile()
        }
    }

    private fun openCallHashSettings() {
        PendingUiAction.requestCallHashSettings()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CALL_HASH
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        openActivity(intent, 102)
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

        fun requestListening(context: Context) {
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

        fun requestTileUpdate(context: Context) {
            requestQuickSettingsTilesUpdate(context)
        }
    }
}
