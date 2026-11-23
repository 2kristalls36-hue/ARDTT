package com.ardtt.app.deploy

import java.util.Locale
import org.json.JSONArray

/**
 * Deploy v2 release assets. The VPS downloads one file itself:
 * `ardtt-server-<DEPLOY_VERSION>-linux-<arch>.image.tar.gz`. The phone only
 * needs to know which stack versions are published.
 */
object DeployStackSource {
    private val IMAGE_ASSET = Regex(
        """^ardtt-server-(\d+\.\d+\.\d+)-linux-(amd64|arm64)\.image\.tar\.gz$""",
        RegexOption.IGNORE_CASE,
    )

    fun linuxArch(unameMachine: String): String =
        when (unameMachine.trim().lowercase(Locale.US)) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> unameMachine.trim().lowercase(Locale.US)
        }

    fun imageAssetName(deployVersion: String, arch: String): String =
        "ardtt-server-${deployVersion.trim()}-linux-${linuxArch(arch)}.image.tar.gz"

    /**
     * Highest stack version with a deploy v2 image in a non-draft release.
     * 1.0.x archives (`.tar.gz`, `.index.json`) are not installable by this
     * app and are ignored. Semver max: one release may carry two stacks.
     */
    fun latestServerVersion(releasesListJson: String): String? {
        val releases = runCatching { JSONArray(releasesListJson) }.getOrNull() ?: return null
        var best = ""
        for (i in 0 until releases.length()) {
            val json = releases.optJSONObject(i) ?: continue
            if (json.optBoolean("draft")) continue
            val assets = json.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val name = assets.optJSONObject(j)?.optString("name").orEmpty()
                val match = IMAGE_ASSET.matchEntire(name) ?: continue
                best = DeployBundle.maxVersion(best, match.groupValues[1])
            }
        }
        return best.ifBlank { null }
    }
}
