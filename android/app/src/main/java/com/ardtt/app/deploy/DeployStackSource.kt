package com.ardtt.app.deploy

import com.ardtt.app.BuildConfig
import org.json.JSONObject

/**
 * Where the phone gets VPS stack bytes: GitHub Releases (`ardtt-stack-*.tar.gz`)
 * or a repository source archive for the app tag / `main`.
 */
object DeployStackSource {
    fun gitRef(): String = "v${BuildConfig.VERSION_NAME}"

    fun gitRepoHttps(): String =
        "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}.git"

    fun stackAssetName(deployVersion: String): String =
        "ardtt-stack-${deployVersion.trim()}.tar.gz"

    fun releaseAssetUrl(tag: String, deployVersion: String): String =
        "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}" +
            "/releases/download/$tag/${stackAssetName(deployVersion)}"

    fun rawInstallUrl(ref: String): String =
        "https://raw.githubusercontent.com/${BuildConfig.GITHUB_REPO_OWNER}/" +
            "${BuildConfig.GITHUB_REPO_NAME}/$ref/server/install.sh"

    fun apiTarballUrl(ref: String): String =
        "https://api.github.com/repos/${BuildConfig.GITHUB_REPO_OWNER}/" +
            "${BuildConfig.GITHUB_REPO_NAME}/tarball/$ref"

    fun publicTagArchiveUrl(tag: String): String =
        "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}" +
            "/archive/refs/tags/$tag.tar.gz"

    fun publicHeadArchiveUrl(branch: String): String =
        "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}" +
            "/archive/refs/heads/$branch.tar.gz"

    fun pickStackAssetUrl(releaseJson: String, expectedVersion: String): String? {
        val json = runCatching { JSONObject(releaseJson) }.getOrNull() ?: return null
        if (json.optBoolean("draft")) return null
        val want = stackAssetName(expectedVersion)
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").equals(want, ignoreCase = true)) {
                return asset.optString("browser_download_url").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
