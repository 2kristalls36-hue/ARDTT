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

    @Test
    fun updateCardShowsVersionAndSizeWhenNotesAreEmpty() {
        val copy = updateCardCopy(
            installedVersionName = "0.5.110-callhash-wifi",
            versionName = "0.5.111-changelog",
            sizeBytes = 12_582_912L,
            notes = "",
        )
        assertEquals("Новая версия 0.5.111-changelog", copy.headline)
        assertEquals("Установлена 0.5.110-callhash-wifi", copy.installedLabel)
        assertEquals("Размер: 12,0 МБ", copy.sizeLabel)
        assertEquals(null, copy.notes)
    }

    @Test
    fun updateCardOmitsSizeWhenUnknownAndShowsNotesWhenPresent() {
        val empty = updateCardCopy(
            installedVersionName = "0.5.60",
            versionName = "0.5.60",
            sizeBytes = 0L,
            notes = "   ",
        )
        assertEquals("Новая версия 0.5.60", empty.headline)
        assertEquals(null, empty.installedLabel)
        assertEquals(null, empty.sizeLabel)
        assertEquals(null, empty.notes)

        val withNotes = updateCardCopy(
            installedVersionName = "0.5.59",
            versionName = "0.5.60",
            sizeBytes = 123_456L,
            notes = "  Исправления загрузки  ",
        )
        assertEquals("Новая версия 0.5.60", withNotes.headline)
        assertEquals("Установлена 0.5.59", withNotes.installedLabel)
        assertEquals("Размер: 120,6 КБ", withNotes.sizeLabel)
        assertEquals("Исправления загрузки", withNotes.notes)
    }

    @Test
    fun formatUpdateSizeUsesRussianUnits() {
        assertEquals("512 Б", formatUpdateSize(512L))
        assertEquals("1,0 КБ", formatUpdateSize(1024L))
        assertEquals("1,0 МБ", formatUpdateSize(1024L * 1024L))
    }
}
