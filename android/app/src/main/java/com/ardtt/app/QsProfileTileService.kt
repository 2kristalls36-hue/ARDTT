package com.ardtt.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.ardtt.app.profile.ProfileCatalog
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.QsProfileClickAction
import com.ardtt.app.ui.qsProfileTileLabel
import com.ardtt.app.ui.qsProfileTileSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that cycles the active VPN profile.
 * Long-press opens the app via QS_TILE_PREFERENCES → [MainActivity].
 */
class QsProfileTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            scope.launch {
                runCatching { handleClick() }
                    .onFailure { e -> Log.e(TAG, "profile tile click failed", e) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleClick() {
        val catalog = QsProfileSwitch.snapshot(applicationContext)
        when (QsProfileSwitch.clickAction(catalog)) {
            QsProfileClickAction.Locked -> QsProfileSwitch.toastLocked(this)
            QsProfileClickAction.OpenProfiles -> openProfiles()
            QsProfileClickAction.Cycle -> {
                val picked = QsProfileSwitch.cycleToNext(applicationContext, catalog) ?: return
                QsProfileSwitch.toastPicked(this, picked.profile.name)
                applyCatalogToTile(
                    catalog = catalog.copy(activeId = picked.id),
                    locked = false,
                )
            }
        }
    }

    private fun refreshTile() {
        scope.launch {
            val catalog = runCatching { QsProfileSwitch.snapshot(applicationContext) }.getOrElse {
                ProfileCatalog()
            }
            applyCatalogToTile(catalog, QsProfileSwitch.sessionLocked())
        }
    }

    private fun applyCatalogToTile(catalog: ProfileCatalog, locked: Boolean) {
        val tile = qsTile ?: return
        val activeName = catalog.items.firstOrNull { it.id == catalog.activeId }?.profile?.name
            ?: catalog.active?.name
        tile.label = qsProfileTileLabel(activeName)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_profile)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = qsProfileTileSubtitle(catalog.items.size, locked)
        }
        tile.state = if (catalog.items.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    private fun openProfiles() {
        PendingUiAction.requestOpenProfiles()
        openActivityAndCollapse(QsProfileSwitch.openProfilesIntent(this), REQ_PROFILES)
    }

    private fun openActivityAndCollapse(intent: Intent, requestCode: Int) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = android.app.PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to open profiles", e)
        }
    }

    companion object {
        private const val TAG = "QsProfileTile"
        private const val REQ_PROFILES = 201

        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT < 24) return
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, QsProfileTileService::class.java),
                )
            }.onFailure { e ->
                Log.w(TAG, "requestListeningState failed", e)
            }
        }
    }
}
