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
