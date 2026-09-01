package com.nonamevpn.app

import android.app.Application
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.telemetry.TelemetryBootstrap
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperCache
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperSession
import com.nonamevpn.app.ui.tunnel.loadNextTunnelWallpaperScene
import com.nonamevpn.app.update.AppUpdateController
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
        AppUpdateController.get(this).checkInBackground()
        AppShortcuts.refreshAsync(this)
        appScope.launch {
            runCatching { AppSettingsRepository(this@ArdttApp).clearLegacyWallpaperPreference() }
        }
        // Undo 0.5.113, which wrongly disabled the system Quick Settings tile.
        applyQuickSettingsTileHidden(this, hidden = false)
    }
}
