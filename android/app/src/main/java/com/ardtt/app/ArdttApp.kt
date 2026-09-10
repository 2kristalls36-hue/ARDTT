package com.ardtt.app

import android.app.Application
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.telemetry.TelemetryBootstrap
import com.ardtt.app.ui.tunnel.TunnelWallpaperCache
import com.ardtt.app.ui.tunnel.TunnelWallpaperSession
import com.ardtt.app.ui.tunnel.loadNextTunnelWallpaperScene
import com.ardtt.app.update.AppUpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArdttApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val scene = loadNextTunnelWallpaperScene(this)
        TunnelWallpaperSession.initForProcess(scene)
        TunnelWallpaperCache.preload(this, scene)
        AppLog.i("TunnelWallpaper", "scene=$scene")
        TelemetryBootstrap.install(this)
        ConnectionManager.get(this)
        TunnelWidgetProvider.pushFromConnection(this)
        AppUpdateController.get(this).checkInBackground()
        AppShortcuts.refreshAsync(this)
        appScope.launch {
            runCatching { com.ardtt.app.deploy.DeployVersionCatalog.refresh(this@ArdttApp) }
        }
        appScope.launch {
            runCatching { AppSettingsRepository(this@ArdttApp).clearLegacyWallpaperPreference() }
        }
        // Undo 0.5.113, which wrongly disabled the system Quick Settings tile.
        applyQuickSettingsTileHidden(this, hidden = false)
    }
}
