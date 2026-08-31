package com.nonamevpn.app

import android.app.Application
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.telemetry.TelemetryBootstrap
import com.nonamevpn.app.ui.tunnel.TunnelWallpaperSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NonameApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        TunnelWallpaperSession.initForProcess()
        AppLog.i("TunnelWallpaper", "scene=${TunnelWallpaperSession.scene}")
        appScope.launch {
            runCatching { AppSettingsRepository(this@NonameApp).clearLegacyWallpaperPreference() }
        }
        TelemetryBootstrap.install(this)
        ConnectionManager.get(this)
        AppShortcuts.refreshAsync(this)
    }
}
