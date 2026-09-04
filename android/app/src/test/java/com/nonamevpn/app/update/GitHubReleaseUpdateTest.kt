package com.nonamevpn.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUpdateTest {
    @Test
    fun githubApiUrlsUseArdttRepo() {
        assertEquals(
            "https://api.github.com/repos/2kristalls36-hue/ARDTT/releases/latest",
            GitHubReleaseUpdate.latestReleaseApiUrl(),
        )
        assertEquals(
            "https://api.github.com/repos/2kristalls36-hue/ARDTT/releases/tags/v0.5.213",
            GitHubReleaseUpdate.releaseByTagApiUrl("v0.5.213"),
        )
        assertEquals(
            "https://api.github.com/repos/2kristalls36-hue/ARDTT/releases?per_page=30",
            GitHubReleaseUpdate.releasesListApiUrl(),
        )
    }

    @Test
    fun parsesReleaseWithManifestAsset() {
        val info = GitHubReleaseUpdate.parseRelease(
            """
            {
              "tag_name": "v0.5.194-github",
              "draft": false,
              "body": "test release",
              "assets": [
                {
                  "name": "ardtt-update.json",
                  "browser_download_url": "https://github.com/example/releases/download/v0.5.194-github/ardtt-update.json"
                },
                {
                  "name": "ardtt-0.5.194-github.apk",
                  "browser_download_url": "https://github.com/example/releases/download/v0.5.194-github/ardtt-0.5.194-github.apk",
                  "size": 100
                }
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(info)
        assertEquals(AppUpdateManifestKind.GitHubJsonAsset, info.manifestKind)
        assertTrue(info.apkUrl.contains("ardtt-update.json"))
    }

    @Test
    fun parsesReleaseWithApkAssetAndVersionCodeInBody() {
        val info = GitHubReleaseUpdate.parseRelease(
            """
            {
              "tag_name": "v0.5.194-github",
              "draft": false,
              "body": "versionCode: 212",
              "assets": [
                {
                  "name": "ardtt-0.5.194-github.apk",
                  "browser_download_url": "https://github.com/example/releases/download/v0.5.194-github/ardtt-0.5.194-github.apk",
                  "size": 88000000
                }
              ]
            }
            """.trimIndent(),
        )

        requireNotNull(info)
        assertEquals(212, info.versionCode)
        assertEquals("0.5.194-github", info.versionName)
        assertTrue(info.apkUrl.endsWith(".apk"))
    }

    @Test
    fun pickApkPrefersArm64() {
        val url = GitHubReleaseUpdate.pickApkAssetUrl(
            org.json.JSONArray(
                """
                [
                  {"name":"ardtt-0.5.1-x86_64.apk","browser_download_url":"https://example/x86.apk"},
                  {"name":"ardtt-0.5.1-universal.apk","browser_download_url":"https://example/universal.apk"},
                  {"name":"ardtt-0.5.1-arm64-v8a.apk","browser_download_url":"https://example/arm64.apk"}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals("https://example/arm64.apk", url)
    }

    @Test
    fun resolveApkUrlPrefersDeviceAbi() {
        val url = GitHubReleaseUpdate.resolveApkUrlForDevice(
            "https://github.com/example/releases/download/v0.5.1/ardtt-0.5.1-arm64-v8a.apk",
            arrayOf("armeabi-v7a"),
        )
        assertEquals(
            "https://github.com/example/releases/download/v0.5.1/ardtt-0.5.1-armeabi-v7a.apk",
            url,
        )
    }
}
