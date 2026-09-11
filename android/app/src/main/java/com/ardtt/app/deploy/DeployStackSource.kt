package com.ardtt.app.deploy

import com.ardtt.app.BuildConfig
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the phone gets the VPS payload: one GitHub Release asset
 * `ardtt-server-<DEPLOY_VERSION>-linux-<arch>.tar.gz` (gzipped image layers (or legacy docker save)).
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
        // Prefer SHA256SUMS-server.txt: Android build's SHA256SUMS.txt is APK-only.
        val preferred = listOf("SHA256SUMS-server.txt", "SHA256SUMS.txt", "SHA256SUMS")
        for (want in preferred) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").equals(want, ignoreCase = true)) {
                    return asset.optString("browser_download_url").trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    fun siblingSha256AssetUrl(releaseJson: String, fileName: String): String? {
        val json = runCatching { JSONObject(releaseJson) }.getOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val want = "$fileName.sha256"
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").equals(want, ignoreCase = true)) {
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

    fun parseSha256Text(text: String, fileName: String): String? {
        sha256FromSums(text, fileName)?.let { return it }
        val token = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase(Locale.US)
            ?.removePrefix("sha256:")
        return token?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
    }

    fun firstReleaseJsonWithServerAsset(
        releasesListJson: String,
        expectedVersion: String,
        arch: String,
    ): String? {
        val releases = runCatching { JSONArray(releasesListJson) }.getOrNull() ?: return null
        for (i in 0 until releases.length()) {
            val json = releases.optJSONObject(i) ?: continue
            if (json.optBoolean("draft")) continue
            val text = json.toString()
            if (pickServerAsset(text, expectedVersion, arch) != null) return text
        }
        return null
    }

    /**
     * Highest published stack version across non-draft releases; used by
     * [DeployVersionCatalog] on app launch. Recognises the full archive and the
     * partial-deploy index (`.index.json`) and ignores hostfiles / per-layer /
     * Engine / Compose assets. One release may carry two stack versions (a newer
     * stack attached to an existing tag), so this is a semver max, not "first match".
     */
    fun latestServerVersion(releasesListJson: String): String? {
        val releases = runCatching { JSONArray(releasesListJson) }.getOrNull() ?: return null
        val pattern = Regex(
            """^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.(tar\.gz|index\.json)$""",
            RegexOption.IGNORE_CASE,
        )
        var best = ""
        for (i in 0 until releases.length()) {
            val json = releases.optJSONObject(i) ?: continue
            if (json.optBoolean("draft")) continue
            val assets = json.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val name = assets.optJSONObject(j)?.optString("name").orEmpty()
                val match = pattern.matchEntire(name) ?: continue
                best = DeployBundle.maxVersion(best, match.groupValues[1])
            }
        }
        return best.ifBlank { null }
    }

    /**
     * Use the app version tag when that release already has the server archive.
     * Otherwise search published releases so a new APK tag without the archive
     * can still install from an older tag that carries `ardtt-server-*.tar.gz`.
     * Never falls back to git/main/raw sources.
     */
    fun preferredReleaseJson(
        tagJson: String?,
        releasesListJson: String?,
        expectedVersion: String,
        arch: String,
    ): String? {
        if (tagJson != null && pickServerAsset(tagJson, expectedVersion, arch) != null) {
            return tagJson
        }
        if (!releasesListJson.isNullOrBlank()) {
            firstReleaseJsonWithServerAsset(releasesListJson, expectedVersion, arch)?.let { return it }
        }
        return null
    }
}
