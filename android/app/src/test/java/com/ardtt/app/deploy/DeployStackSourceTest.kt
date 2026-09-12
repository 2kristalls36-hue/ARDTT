package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployStackSourceTest {
    @Test
    fun serverAssetNameMatchesReleaseConvention() {
        assertEquals(
            "ardtt-server-1.0.45-linux-amd64.tar.gz",
            DeployStackSource.serverAssetName("1.0.45", "amd64"),
        )
        assertEquals(
            "ardtt-server-1.0.45-linux-arm64.tar.gz",
            DeployStackSource.serverAssetName(" 1.0.45 ", "aarch64"),
        )
        assertEquals(
            "ardtt-server-1.0.45-linux-amd64.tar.gz",
            DeployStackSource.serverAssetName("1.0.45", "x86_64"),
        )
    }

    @Test
    fun linuxArchNormalizesUname() {
        assertEquals("amd64", DeployStackSource.linuxArch("x86_64"))
        assertEquals("amd64", DeployStackSource.linuxArch("AMD64"))
        assertEquals("arm64", DeployStackSource.linuxArch("aarch64"))
        assertEquals("arm64", DeployStackSource.linuxArch("arm64"))
    }

    @Test
    fun gitRefIsVersionTag() {
        assertTrue(DeployStackSource.gitRef().startsWith("v"))
        assertTrue(DeployStackSource.gitRepoHttps().endsWith("/ARDTT.git"))
        assertEquals(
            "https://github.com/2kristalls36-hue/ARDTT/releases/download/v0.5.256/ardtt-server-1.0.45-linux-amd64.tar.gz",
            DeployStackSource.releaseAssetUrl("v0.5.256", "1.0.45", "amd64"),
        )
    }

    @Test
    fun pickServerAssetIgnoresApkAndWrongVersion() {
        val json = """
            {
              "tag_name": "v0.5.256",
              "draft": false,
              "assets": [
                {
                  "name": "ardtt-0.5.256-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/example/ardtt.apk"
                },
                {
                  "name": "ardtt-stack-1.0.44.tar.gz",
                  "browser_download_url": "https://github.com/example/old-stack.tar.gz"
                },
                {
                  "name": "ardtt-server-1.0.45-linux-amd64.tar.gz",
                  "browser_download_url": "https://github.com/example/ardtt-server-1.0.45-linux-amd64.tar.gz",
                  "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "size": 123
                }
              ]
            }
        """.trimIndent()
        val asset = DeployStackSource.pickServerAsset(json, "1.0.45", "amd64")
        assertEquals(
            "https://github.com/example/ardtt-server-1.0.45-linux-amd64.tar.gz",
            asset?.url,
        )
        assertEquals("a".repeat(64), asset?.sha256)
        assertNull(DeployStackSource.pickServerAsset(json, "9.9.9", "amd64"))
        assertNull(DeployStackSource.pickServerAsset(json, "1.0.45", "arm64"))
    }

    @Test
    fun pickServerAssetSkipsDrafts() {
        val json = """
            {
              "draft": true,
              "assets": [
                {
                  "name": "ardtt-server-1.0.45-linux-amd64.tar.gz",
                  "browser_download_url": "https://github.com/example/pkg.tar.gz"
                }
              ]
            }
        """.trimIndent()
        assertNull(DeployStackSource.pickServerAsset(json, "1.0.45", "amd64"))
    }

    @Test
    fun sha256FromSumsPicksNamedAsset() {
        val sums = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  ardtt-server-1.0.45-linux-amd64.tar.gz
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  ardtt-server-1.0.45-linux-arm64.tar.gz
        """.trimIndent()
        assertEquals(
            "b".repeat(64),
            DeployStackSource.sha256FromSums(sums, "ardtt-server-1.0.45-linux-arm64.tar.gz"),
        )
        assertNull(DeployStackSource.sha256FromSums(sums, "missing.tar.gz"))
    }

    @Test
    fun sha256sumsUrlPrefersServerFileOverApkSums() {
        val json = """
            {
              "assets": [
                { "name": "SHA256SUMS.txt", "browser_download_url": "https://example/apk-sums" },
                { "name": "SHA256SUMS-server.txt", "browser_download_url": "https://example/server-sums" }
              ]
            }
        """.trimIndent()
        assertEquals("https://example/server-sums", DeployStackSource.sha256sumsUrl(json))
        assertEquals(
            "https://example/pkg.tar.gz.sha256",
            DeployStackSource.siblingSha256AssetUrl(
                """{"assets":[{"name":"ardtt-server-1.0.45-linux-amd64.tar.gz.sha256","browser_download_url":"https://example/pkg.tar.gz.sha256"}]}""",
                "ardtt-server-1.0.45-linux-amd64.tar.gz",
            ),
        )
        assertEquals(
            "c".repeat(64),
            DeployStackSource.parseSha256Text("${"c".repeat(64)}\n", "ignored.tar.gz"),
        )
    }

    @Test
    fun preferredReleaseJsonSkipsVersionTagWithoutServerAsset() {
        val tagOnlyApk = """
            {
              "tag_name": "v0.5.257",
              "draft": false,
              "assets": [
                { "name": "ardtt-0.5.257-arm64-v8a.apk", "browser_download_url": "https://example/app.apk" }
              ]
            }
        """.trimIndent()
        val list = """
            [
              $tagOnlyApk,
              {
                "tag_name": "v0.5.256",
                "draft": false,
                "assets": [
                  {
                    "name": "ardtt-server-1.0.45-linux-amd64.tar.gz",
                    "browser_download_url": "https://example/ardtt-server-1.0.45-linux-amd64.tar.gz",
                    "digest": "sha256:${"d".repeat(64)}",
                    "size": 99
                  }
                ]
              }
            ]
        """.trimIndent()
        val chosen = DeployStackSource.preferredReleaseJson(tagOnlyApk, list, "1.0.45", "amd64")
        assertEquals(
            "https://example/ardtt-server-1.0.45-linux-amd64.tar.gz",
            DeployStackSource.pickServerAsset(chosen!!, "1.0.45", "amd64")?.url,
        )
        assertNull(DeployStackSource.preferredReleaseJson(tagOnlyApk, "[]", "1.0.45", "amd64"))
    }

    @Test
    fun latestServerVersionPicksNewestPublishedAsset() {
        val json = """
            [
              {
                "tag_name": "v0.5.259",
                "draft": false,
                "assets": [
                  {"name": "ardtt-0.5.259.apk", "browser_download_url": "https://x/a.apk"}
                ]
              },
              {
                "tag_name": "v0.5.258",
                "draft": false,
                "assets": [
                  {
                    "name": "ardtt-server-1.0.46-linux-amd64.tar.gz",
                    "browser_download_url": "https://x/ardtt-server-1.0.46-linux-amd64.tar.gz"
                  }
                ]
              }
            ]
        """.trimIndent()
        assertEquals("1.0.46", DeployStackSource.latestServerVersion(json))
        assertNull(DeployStackSource.latestServerVersion("[]"))
        assertNull(DeployStackSource.latestServerVersion("{\"not\":\"array\"}"))
    }

    @Test
    fun latestServerVersionIsTheHighestStackAcrossAssets() {
        // A newer stack attached to an existing tag lists its assets after the older one;
        // partial-deploy assets (index / hostfiles / layers / engine / compose) sit next to them.
        val json = """
            [
              {
                "tag_name": "v0.5.263",
                "draft": false,
                "assets": [
                  {"name": "ardtt-server-1.0.51-linux-amd64.tar.gz"},
                  {"name": "ardtt-server-1.0.51-linux-arm64.tar.gz"},
                  {"name": "ardtt-server-1.0.52-linux-amd64-hostfiles.tar.gz"},
                  {"name": "ardtt-server-1.0.52-linux-amd64-layer-00-0123456789abcdef.tar.gz"},
                  {"name": "ardtt-docker-engine-29.7.2-linux-amd64.tgz"},
                  {"name": "ardtt-docker-compose-2.32.4-linux-amd64"},
                  {"name": "ardtt-server-1.0.52-linux-amd64.index.json"},
                  {"name": "ardtt-server-1.0.9-linux-amd64.tar.gz"}
                ]
              },
              {
                "tag_name": "v0.5.264",
                "draft": true,
                "assets": [{"name": "ardtt-server-1.0.99-linux-amd64.tar.gz"}]
              }
            ]
        """.trimIndent()
        assertEquals("1.0.52", DeployStackSource.latestServerVersion(json))
        val toolingOnly = """
            [{"tag_name": "v1", "draft": false, "assets": [
              {"name": "ardtt-server-1.0.52-linux-amd64-hostfiles.tar.gz"},
              {"name": "ardtt-docker-engine-29.7.2-linux-amd64.tgz"}
            ]}]
        """.trimIndent()
        assertNull(DeployStackSource.latestServerVersion(toolingOnly))
    }

    @Test
    fun latestServerVersionPicksNewerStackAttachedToSameTag() {
        val json = """
            [
              {
                "tag_name": "v0.5.264",
                "draft": false,
                "assets": [
                  {"name": "ardtt-0.5.264-arm64-v8a.apk"},
                  {"name": "ardtt-server-1.0.52-linux-amd64.tar.gz"},
                  {"name": "ardtt-server-1.0.52-linux-amd64.index.json"},
                  {"name": "ardtt-server-1.0.53-linux-amd64.index.json"},
                  {"name": "ardtt-server-1.0.53-linux-amd64.tar.gz"}
                ]
              }
            ]
        """.trimIndent()
        assertEquals("1.0.53", DeployStackSource.latestServerVersion(json))
    }

    @Test
    fun gzipMagicAndInstallerSniff() {
        assertTrue(DeployStackFetcher.looksLikeGzip(byteArrayOf(0x1f, 0x8b.toByte(), 0x08)))
        assertFalse(DeployStackFetcher.looksLikeGzip("not gzip".toByteArray()))
        val installer = "#!/bin/bash\n# x\nARDTT_PROGRESS|0.1|hi\nARDTT_DONE|ok\n".toByteArray()
        assertTrue(DeployStackFetcher.looksLikeInstaller(installer))
        assertFalse(DeployStackFetcher.looksLikeInstaller("hello".toByteArray()))
    }

    @Test
    fun sourceHasNoMainOrRawFallback() {
        val src = java.io.File("../../../../../../src/main/java/com/ardtt/app/deploy/DeployStackSource.kt")
        val file = if (src.isFile) {
            src
        } else {
            java.io.File("src/main/java/com/ardtt/app/deploy/DeployStackSource.kt")
        }
        if (!file.isFile) return
        val text = file.readText()
        assertFalse(text.contains("raw.githubusercontent.com"))
        assertFalse(text.contains("archive/refs/heads/main"))
        assertFalse(text.contains("ardtt-stack-"))
    }
}
