package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployStackSourceTest {
    @Test
    fun stackAssetNameMatchesReleaseConvention() {
        assertEquals("ardtt-stack-1.0.36.tar.gz", DeployStackSource.stackAssetName("1.0.36"))
        assertEquals("ardtt-stack-1.0.36.tar.gz", DeployStackSource.stackAssetName(" 1.0.36 "))
    }

    @Test
    fun gitUrlsPointAtArdttRepo() {
        assertTrue(DeployStackSource.gitRepoHttps().endsWith("/ARDTT.git"))
        assertTrue(DeployStackSource.gitRef().startsWith("v"))
        assertTrue(
            DeployStackSource.rawInstallUrl("v0.5.238").endsWith("/v0.5.238/server/install.sh"),
        )
        assertEquals(
            "https://github.com/2kristalls36-hue/ARDTT/releases/download/v0.5.238/ardtt-stack-1.0.36.tar.gz",
            DeployStackSource.releaseAssetUrl("v0.5.238", "1.0.36"),
        )
        assertEquals(
            "https://api.github.com/repos/2kristalls36-hue/ARDTT/tarball/v0.5.238",
            DeployStackSource.apiTarballUrl("v0.5.238"),
        )
        assertEquals(
            "https://github.com/2kristalls36-hue/ARDTT/archive/refs/tags/v0.5.238.tar.gz",
            DeployStackSource.publicTagArchiveUrl("v0.5.238"),
        )
        assertEquals(
            "https://github.com/2kristalls36-hue/ARDTT/archive/refs/heads/main.tar.gz",
            DeployStackSource.publicHeadArchiveUrl("main"),
        )
    }

    @Test
    fun pickStackAssetIgnoresApkAndWrongVersion() {
        val json = """
            {
              "tag_name": "v0.5.238",
              "draft": false,
              "assets": [
                {
                  "name": "ardtt-0.5.238-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/example/ardtt.apk"
                },
                {
                  "name": "ardtt-stack-1.0.35.tar.gz",
                  "browser_download_url": "https://github.com/example/old-stack.tar.gz"
                },
                {
                  "name": "ardtt-stack-1.0.36.tar.gz",
                  "browser_download_url": "https://github.com/example/ardtt-stack-1.0.36.tar.gz"
                }
              ]
            }
        """.trimIndent()
        assertEquals(
            "https://github.com/example/ardtt-stack-1.0.36.tar.gz",
            DeployStackSource.pickStackAssetUrl(json, "1.0.36"),
        )
        assertNull(DeployStackSource.pickStackAssetUrl(json, "9.9.9"))
    }

    @Test
    fun pickStackAssetSkipsDrafts() {
        val json = """
            {
              "draft": true,
              "assets": [
                {
                  "name": "ardtt-stack-1.0.36.tar.gz",
                  "browser_download_url": "https://github.com/example/ardtt-stack-1.0.36.tar.gz"
                }
              ]
            }
        """.trimIndent()
        assertNull(DeployStackSource.pickStackAssetUrl(json, "1.0.36"))
    }

    @Test
    fun gzipMagicAndInstallerSniff() {
        assertTrue(DeployStackFetcher.looksLikeGzip(byteArrayOf(0x1f, 0x8b.toByte(), 0x08)))
        assertFalse(DeployStackFetcher.looksLikeGzip("not gzip".toByteArray()))
        val installer = "#!/bin/bash\n# x\nARDTT_PROGRESS|0.1|hi\nARDTT_DONE|ok\n".toByteArray()
        assertTrue(DeployStackFetcher.looksLikeInstaller(installer))
        assertFalse(DeployStackFetcher.looksLikeInstaller("hello".toByteArray()))
    }
}
