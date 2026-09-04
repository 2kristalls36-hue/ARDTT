package com.ardtt.app.update

import com.ardtt.app.BuildConfig
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Fetches update metadata from GitHub Releases (qWDTT-style). */
object GitHubReleaseUpdate {
    const val API_VERSION = "2022-11-28"

    fun latestReleaseApiUrl(): String =
        "https://api.github.com/repos/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}/releases/latest"

    fun releaseByTagApiUrl(tag: String): String =
        "https://api.github.com/repos/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}/releases/tags/$tag"

    fun releasesListApiUrl(): String =
        "https://api.github.com/repos/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}/releases?per_page=30"

    fun userAgent(): String = "ARDTTAndroid/${BuildConfig.VERSION_NAME}"

    fun parseRelease(raw: String): AppUpdateInfo? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optBoolean("draft")) return null

        assetUrl(json, "ardtt-update.json")?.let { manifestUrl ->
            return AppUpdateInfo(
                versionCode = -1,
                versionName = json.optString("tag_name").removePrefix("v"),
                apkUrl = manifestUrl,
                sha256 = "",
                sizeBytes = 0L,
                notes = "",
                manifestKind = AppUpdateManifestKind.GitHubJsonAsset,
            )
        }

        val apkUrl = pickApkAssetUrl(json.optJSONArray("assets")) ?: return null
        val tagName = json.optString("tag_name").removePrefix("v")
        val versionCode = parseVersionCode(json) ?: return null
        val sha256 = assetSha256(json.optJSONArray("assets"), apkUrl)
        val sizeBytes = assetSize(json.optJSONArray("assets"), apkUrl)
        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = tagName,
            apkUrl = apkUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            notes = json.optString("body").trim(),
            manifestKind = AppUpdateManifestKind.DirectApk,
        )
    }

    fun parseManifest(raw: String, supportedAbis: Array<String>): AppUpdateInfo {
        val parsed = AppUpdateInfo.parse(raw)
        return parsed.copy(
            apkUrl = resolveApkUrlForDevice(parsed.apkUrl, supportedAbis),
            manifestKind = AppUpdateManifestKind.DirectApk,
        )
    }

    /** Map manifest APK URL (often arm64) to the best ABI available on this device. */
    fun resolveApkUrlForDevice(apkUrl: String, supportedAbis: Array<String>): String {
        val match = Regex("-(arm64-v8a|armeabi-v7a|x86_64|universal)(?=\\.apk$)", RegexOption.IGNORE_CASE)
            .find(apkUrl)
            ?: return apkUrl
        val preferred = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "universal")
        val deviceAbi = preferred.firstOrNull { candidate ->
            supportedAbis.any { it.equals(candidate, ignoreCase = true) }
        } ?: "universal"
        return apkUrl.replace(match.value, "-$deviceAbi")
    }

    private fun assetUrl(json: JSONObject, fileName: String): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").equals(fileName, ignoreCase = true)) {
                return asset.optString("browser_download_url").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun assetSha256(assets: JSONArray?, apkUrl: String): String {
        if (assets == null) return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("browser_download_url") == apkUrl) {
                return asset.optString("digest").removePrefix("sha256:").lowercase(Locale.US)
            }
        }
        return ""
    }

    private fun assetSize(assets: JSONArray?, apkUrl: String): Long {
        if (assets == null) return 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("browser_download_url") == apkUrl) {
                return asset.optLong("size", 0L)
            }
        }
        return 0L
    }

    private fun parseVersionCode(json: JSONObject): Int? {
        val body = json.optString("body")
        val marker = Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        marker.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return json.optString("tag_name")
            .removePrefix("v")
            .substringAfterLast('-', "")
            .toIntOrNull()
    }

    fun pickApkAssetUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        data class ApkAsset(val name: String, val url: String)
        val apks = buildList {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url").trim()
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                    add(ApkAsset(name, url))
                }
            }
        }
        if (apks.isEmpty()) return null
        return apks.firstOrNull { it.name.contains("arm64", ignoreCase = true) }?.url
            ?: apks.firstOrNull { it.name.contains("armeabi", ignoreCase = true) }?.url
            ?: apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.url
            ?: apks.firstOrNull { it.name.contains("x86_64", ignoreCase = true) }?.url
            ?: apks.firstOrNull { it.name.startsWith("ardtt-", ignoreCase = true) }?.url
            ?: apks.first().url
    }
}

enum class AppUpdateManifestKind {
    LegacyVps,
    GitHubJsonAsset,
    DirectApk,
}
