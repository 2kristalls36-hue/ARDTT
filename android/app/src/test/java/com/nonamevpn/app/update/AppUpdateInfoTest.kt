package com.nonamevpn.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
              "apkUrl": "http://159.194.225.162:8088/ardtt-latest.apk",
              "sha256": "ABCDEF",
              "sizeBytes": 123456,
              "notes": "test build"
            }
            """.trimIndent(),
        )

        assertEquals(78, info.versionCode)
        assertEquals("0.5.60", info.versionName)
        assertEquals("http://159.194.225.162:8088/ardtt-latest.apk", info.apkUrl)
        assertEquals("abcdef", info.sha256)
        assertEquals(123456, info.sizeBytes)
        assertTrue(info.notes.contains("test"))
    }

    @Test
    fun showsCardOnlyWhenUpdateIsReal() {
        assertTrue(shouldShowUpdateCard(availableNewer = true, downloading = false, hasApk = false))
        assertTrue(shouldShowUpdateCard(availableNewer = false, downloading = true, hasApk = false))
        assertTrue(shouldShowUpdateCard(availableNewer = false, downloading = false, hasApk = true))
        assertFalse(shouldShowUpdateCard(availableNewer = false, downloading = false, hasApk = false))
    }

    @Test
    fun updateButtonLabelFollowsDownloadState() {
        assertEquals("Загрузить", updatePrimaryActionLabel(downloading = false, hasApk = false))
        assertEquals("Отмена", updatePrimaryActionLabel(downloading = true, hasApk = false))
        assertEquals("Установить", updatePrimaryActionLabel(downloading = false, hasApk = true))
        assertEquals("Отмена", updatePrimaryActionLabel(downloading = true, hasApk = true))
    }
}
