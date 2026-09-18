package com.ardtt.app.deploy

import android.content.Context

/**
 * Stack / provision deploy version — independent from the app [versionName].
 * Series starts at **1.0.1** and is bumped only when the VPS install bundle changes.
 * Written to the VPS on deploy and compared via provision `GET /health`.
 *
 * GitHub Releases remain the download source for image layers, Engine and
 * Compose. Installer hostfiles can ship in the APK overlay so a git bump
 * does not wait for a Release package. [FALLBACK_VERSION] (git
 * `server/DEPLOY_VERSION`) is compared with the catalog: if it is newer,
 * the UI offers an update and deploy overlays hostfiles onto published layers.
 */
object DeployBundle {
    const val ASSET_VERSION_FILE = "deploy/DEPLOY_VERSION"

    /** Bundled git deploy version (must match server/DEPLOY_VERSION). */
    const val FALLBACK_VERSION = "1.0.54"

    fun expectedVersion(context: Context): String =
        DeployVersionCatalog.expectedVersion(context)

    fun offlineFallback(context: Context): String =
        runCatching {
            context.assets.open(ASSET_VERSION_FILE).bufferedReader().use { it.readText() }.trim()
        }.getOrElse { FALLBACK_VERSION }.ifBlank { FALLBACK_VERSION }

    fun isCurrent(installed: String?, expected: String): Boolean {
        val a = installed?.trim().orEmpty()
        val b = expected.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return a.equals(b, ignoreCase = true)
    }

    /** Compare dotted versions (`1.0.9` vs `1.0.51`). Non-numeric → lexical. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.trim().removePrefix("v").removePrefix("V")
        val right = b.trim().removePrefix("v").removePrefix("V")
        if (left.isEmpty() && right.isEmpty()) return 0
        if (left.isEmpty()) return -1
        if (right.isEmpty()) return 1
        val pa = left.split('.')
        val pb = right.split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val sa = pa.getOrNull(i).orEmpty()
            val sb = pb.getOrNull(i).orEmpty()
            val na = sa.toIntOrNull()
            val nb = sb.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> 1
                nb != null -> -1
                else -> sa.compareTo(sb, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun maxVersion(a: String, b: String): String {
        val left = a.trim()
        val right = b.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        return if (compareVersions(left, right) >= 0) left else right
    }
}
