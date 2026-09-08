package com.ardtt.app.deploy

import com.ardtt.app.BuildConfig
import java.util.Locale
import org.json.JSONObject

/**
 * Where the phone gets the VPS payload: one GitHub Release asset
 * `ardtt-server-<DEPLOY_VERSION>-linux-<arch>.tar.gz` (docker save image).
 * No source tarball, main, or raw install.sh fallback.
 */
object DeployStackSource {
    fun gitRef(): String = "v${BuildConfig.VERSION_NAME}"

    fun gitRepoHttps(): String =
        "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}.git"

    fun linuxArch(unameMachine: String): String =
        when (unameMachine.trim().lowercase(Locale.US)) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> unameMachine.trim().lowercase(Locale.US)
        }

    fun serverAssetName(deployVersion: String, arch: String): String =
        "ardtt-server-${deployVersion.trim()}-linux-${linuxArch(arch)}.tar.gz"

    fun releaseAssetUrl(tag: String, deployVersion: String, arch: String): String =
        "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}" +
            "/releases/download/$tag/${serverAssetName(deployVersion, arch)}"

    data class ReleaseAsset(
        val name: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    fun pickServerAsset(releaseJson: String, expectedVersion: String, arch: String): ReleaseAsset? {
        val json = runCatching { JSONObject(releaseJson) }.getOrNull() ?: return null
        if (json.optBoolean("draft")) return null
        val want = serverAssetName(expectedVersion, arch)
        val assets = json.optJSONArray("assets") ?: return null
        var found: ReleaseAsset? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.equals(want, ignoreCase = true)) {
                val url = asset.optString("browser_download_url").trim()
                if (url.isEmpty()) continue
                val digest = asset.optString("digest").removePrefix("sha256:").lowercase(Locale.US)
                found = ReleaseAsset(
                    name = name,
                    url = url,
                    sha256 = digest,
                    sizeBytes = asset.optLong("size", 0L),
                )
            }
        }
        val asset = found ?: return null
        return asset
    }

    fun sha256sumsUrl(releaseJson: String): String? {
        val json = runCatching { JSONObject(releaseJson) }.getOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.equals("SHA256SUMS.txt", ignoreCase = true) ||
                name.equals("SHA256SUMS", ignoreCase = true)
            ) {
                return asset.optString("browser_download_url").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun sha256FromSums(sums: String, fileName: String): String? {
        sums.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val name = parts.last().substringAfterLast('/')
            if (name.equals(fileName, ignoreCase = true)) {
                return parts[0].lowercase(Locale.US).removePrefix("sha256:")
            }
        }
        return null
    }
}
