package com.nonamevpn.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Enable or disable the Quick Settings tile component. */
internal fun applyQuickSettingsTileHidden(context: Context, hidden: Boolean) {
    val pm = context.applicationContext.packageManager
    val component = ComponentName(context.applicationContext, QuickToggleTileService::class.java)
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    runCatching {
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
