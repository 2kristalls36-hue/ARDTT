package com.nonamevpn.app

import android.app.Application
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.telemetry.TelemetryBootstrap

class ArdttApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TelemetryBootstrap.install(this)
        ConnectionManager.get(this)
        AppShortcuts.refreshAsync(this)
    }
}
