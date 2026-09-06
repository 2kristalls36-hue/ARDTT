package com.ardtt.app.deploy

import java.io.File
import org.junit.Assert.assertArrayEquals
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
        val lateMarkers = ("# " + "x".repeat(500) + "\n#   ARDTT_PROGRESS|<0..1>|<step>\n#   ARDTT_DONE|ok\n")
            .toByteArray()
        assertTrue(
            "markers past the old 400-char window must still count",
            DeployStackFetcher.looksLikeInstaller(lateMarkers),
        )
    }

    @Test
    fun extractInstallScriptFromGzipTar() {
        val script = "#!/bin/bash\nARDTT_PROGRESS|0.1|hi\nARDTT_DONE|ok\n".toByteArray()
        val packed = gzipTar(
            listOf(
                "README.md" to "not an installer".toByteArray(),
                "server/install.sh" to script,
            ),
        )
        val extracted = DeployStackArchive.extractInstallScript(packed)
        assertArrayEquals(script, extracted)
        assertTrue(DeployStackArchive.isInstallPath("install.sh"))
        assertTrue(DeployStackArchive.isInstallPath("ARDTT-0.5.245/server/install.sh"))
        assertFalse(DeployStackArchive.isInstallPath("scripts/create-user.sh"))
    }

    @Test
    fun repoInstallShFitsLegacySniffWindow() {
        val file = File("../../server/install.sh")
        if (!file.isFile) {
            return
        }
        val text = file.readText()
        assertTrue(text.take(400).contains("ARDTT_PROGRESS|"))
        assertTrue(text.take(400).contains("ARDTT_DONE|"))
        val script = file.readBytes()
        assertTrue(DeployStackFetcher.looksLikeInstaller(script))
        val gz = gzipTar(listOf("install.sh" to script, "docker-compose.yml" to "x".toByteArray()))
        assertArrayEquals(script, DeployStackArchive.extractInstallScript(gz))
    }
}

private fun gzipTar(entries: List<Pair<String, ByteArray>>): ByteArray {
    val tar = java.io.ByteArrayOutputStream()
    for ((name, data) in entries) {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray()
        nameBytes.copyInto(header, endIndex = nameBytes.size.coerceAtMost(100))
        val sizeOct = data.size.toString(8).padStart(11, '0') + "\u0000"
        sizeOct.toByteArray().copyInto(header, destinationOffset = 124)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, destinationOffset = 257)
        header.fill(32, 148, 156)
        val sum = header.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }
        val chk = sum.toString(8).padStart(6, '0') + "\u0000 "
        chk.toByteArray().copyInto(header, destinationOffset = 148)
        tar.write(header)
        tar.write(data)
        val pad = (512 - (data.size % 512)) % 512
        if (pad > 0) tar.write(ByteArray(pad))
    }
    tar.write(ByteArray(1024))
    val gz = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
    return gz.toByteArray()
}
