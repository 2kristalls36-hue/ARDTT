package com.nonamevpn.app

import android.app.Application
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.telemetry.TelemetryBootstrap
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperCache
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperSession
import com.nonamevpn.app.ui.tunnel.loadNextTunnelWallpaperScene
import com.nonamevpn.app.update.AppUpdateController

class ArdttApp : Application() {
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
        // Undo 0.5.113, which wrongly disabled the system Quick Settings tile.
        applyQuickSettingsTileHidden(this, hidden = false)
    }
}
