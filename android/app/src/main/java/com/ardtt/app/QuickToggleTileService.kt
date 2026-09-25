package com.ardtt.app

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
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.holdsUserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings tile — same click path as qWDTT [QuickToggleTileService]:
 * stop or start in-process so the shade stays open. The shade collapses
 * only for the system VPN consent dialog.
 *
 * Work runs on a process-wide scope. SystemUI unbinds this service right
 * after [onClick]; a service-scoped job would be cancelled before the
 * toggle ran.
 */
class QuickToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            if (isSessionUp()) {
                ConnectionManager.get(applicationContext).disconnect()
                updateTile(running = false)
                return
            }

            val needsVpnPermission = runCatching {
                VpnService.prepare(this@QuickToggleTileService) != null
            }.getOrDefault(true)
            if (!needsVpnPermission) {
                updateTileConnecting()
                tileScope.launch {
                    runCatching { startFromSavedSettings() }
                        .onFailure { e ->
                            Log.e(TAG, "QS tile start failed", e)
                            toast(e.message ?: "Не удалось переключить туннель")
                            updateTileState()
                        }
                }
                return
            }

            toast("Разрешите ARDTT создать VPN-подключение")
            openVpnPermissionActivity()
        }.onFailure { e ->
            Log.e(TAG, "QS tile onClick failed", e)
        }
    }

    private suspend fun startFromSavedSettings() {
        val result = runQuickLaunchToggle(applicationContext) {
            runCatching { VpnService.prepare(this@QuickToggleTileService) }.getOrNull()
        }
        when (result.outcome) {
            QuickLaunchOutcome.MissingProfile -> {
                toast("Нет активного профиля")
                updateTile(running = false)
            }
            QuickLaunchOutcome.NeedVpnConsent -> openVpnPermissionActivity()
            QuickLaunchOutcome.ConnectInPlace,
            QuickLaunchOutcome.Disconnect -> updateTileState()
        }
    }

    private fun isSessionUp(): Boolean {
        val state = ConnectionManager.getOrNull()?.ui?.value?.state ?: return false
        return state.holdsUserSession()
    }

    private fun updateTileState() {
        if (isSessionUp()) {
            val state = ConnectionManager.getOrNull()?.ui?.value?.state
            val connecting = state != null && state != com.ardtt.app.core.ConnState.Connected &&
                state != com.ardtt.app.core.ConnState.PausedTrustedWifi
            if (connecting) updateTileConnecting() else updateTile(running = true)
        } else {
            updateTile(running = false)
        }
    }

    private fun updateTileConnecting() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = "ARDTT"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_custom)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = "Подключение…"
        }
        tile.updateTile()
    }

    private fun updateTile(running: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_custom)
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

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
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

        /** Outlives the bound tile service. SystemUI unbinds immediately after a click. */
        private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
