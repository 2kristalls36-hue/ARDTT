package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeployStackSourceTest {
    private fun release(vararg names: String, draft: Boolean = false): String =
        """{"draft":$draft,"assets":[${names.joinToString(",") { """{"name":"$it"}""" }}]}"""

    @Test
    fun imageAssetNameUsesLinuxArch() {
        assertEquals("ardtt-server-2.0.0-linux-arm64.image.tar.gz", DeployStackSource.imageAssetName("2.0.0", "aarch64"))
        assertEquals("ardtt-server-2.0.0-linux-amd64.image.tar.gz", DeployStackSource.imageAssetName(" 2.0.0 ", "x86_64"))
    }

    @Test
    fun latestServerVersionTakesOnlyV2Images() {
        val list = "[" + listOf(
            release("ardtt-server-1.0.57-linux-amd64.tar.gz", "ardtt-server-1.0.57-linux-amd64.index.json"),
            release("ardtt-server-2.0.0-linux-amd64.image.tar.gz", "ardtt-server-2.0.0-linux-arm64.image.tar.gz", "ardtt-deploy.sh"),
            release("ardtt-server-2.0.10-linux-arm64.image.tar.gz"),
            release("ardtt-server-9.9.9-linux-amd64.image.tar.gz", draft = true),
        ).joinToString(",") + "]"
        assertEquals("2.0.10", DeployStackSource.latestServerVersion(list))
    }

    @Test
    fun latestServerVersionNullWithoutV2() {
        assertNull(DeployStackSource.latestServerVersion("[" + release("ardtt-server-1.0.57-linux-amd64.tar.gz") + "]"))
        assertNull(DeployStackSource.latestServerVersion("not json"))
    }
}
