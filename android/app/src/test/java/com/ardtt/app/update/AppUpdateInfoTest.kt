package com.ardtt.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateInfoTest {
    @Test
    fun parsesManifestFields() {
        val info = AppUpdateInfo.parse(
            """
            {
              "versionCode": 78,
              "versionName": "0.5.60",
              "apkUrl": "https://45.129.2.3/ardtt-latest.apk",
              "sha256": "ABCDEF",
              "sizeBytes": 123456,
              "notes": "test build"
            }
            """.trimIndent(),
        )

        assertEquals(78, info.versionCode)
        assertEquals("0.5.60", info.versionName)
        assertEquals("https://45.129.2.3/ardtt-latest.apk", info.apkUrl)
        assertEquals("abcdef", info.sha256)
        assertEquals(123456, info.sizeBytes)
        assertTrue(info.notes.contains("test"))
    }
}
