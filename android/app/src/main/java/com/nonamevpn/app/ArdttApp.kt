package com.nonamevpn.app

import android.app.Application
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.telemetry.TelemetryBootstrap
import com.nonamevpn.app.update.AppUpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArdttApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        TelemetryBootstrap.install(this)
        ConnectionManager.get(this)
        AppUpdateController.get(this).checkInBackground()
        AppShortcuts.refreshAsync(this)
        appScope.launch {
            AppSettingsRepository(this@ArdttApp).rotateTunnelWallpaperVariantOnAppStart(
                variantCount = 2,
            )
        }
        // Undo 0.5.113, which wrongly disabled the system Quick Settings tile.
        applyQuickSettingsTileHidden(this, hidden = false)
    }
}
