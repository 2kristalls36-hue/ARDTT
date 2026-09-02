package com.nonamevpn.app.deploy

import android.content.Context

/**
 * Stack / provision deploy version — independent from the app [versionName].
 * Series starts at **1.0.1** and is bumped only when the VPS install bundle changes.
 * Written to the VPS on deploy and compared via provision `GET /health`.
 */
object DeployBundle {
    const val ASSET_VERSION_FILE = "deploy/DEPLOY_VERSION"

    /** Fallback when the asset is missing (must match assets/deploy/DEPLOY_VERSION). */
    const val FALLBACK_VERSION = "1.0.14"

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
