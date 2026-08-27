package com.nonamevpn.app.deploy

import android.content.Context

/** Stack / provision deploy version shipped with the APK and written to the VPS on install. */
object DeployBundle {
    const val ASSET_VERSION_FILE = "deploy/DEPLOY_VERSION"

    /** Fallback when the asset is missing (should match assets/deploy/DEPLOY_VERSION). */
    const val FALLBACK_VERSION = "0.5.44"

    fun expectedVersion(context: Context): String =
        runCatching {
            context.assets.open(ASSET_VERSION_FILE).bufferedReader().use { it.readText() }.trim()
        }.getOrElse { FALLBACK_VERSION }.ifBlank { FALLBACK_VERSION }

    fun isCurrent(installed: String?, expected: String): Boolean {
        val a = installed?.trim().orEmpty()
        val b = expected.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return a.equals(b, ignoreCase = true)
    }
}
