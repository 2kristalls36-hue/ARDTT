package com.ardtt.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Enable or disable the system Quick Settings tiles. */
internal fun applyQuickSettingsTileHidden(context: Context, hidden: Boolean) {
    val pm = context.applicationContext.packageManager
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    val app = context.applicationContext
    listOf(
        ComponentName(app, QuickToggleTileService::class.java),
        ComponentName(app, QsProfileTileService::class.java),
    ).forEach { component ->
        runCatching {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}

internal fun requestQuickSettingsTilesUpdate(context: Context) {
    QuickToggleTileService.requestListening(context)
    QsProfileTileService.requestTileUpdate(context)
}
