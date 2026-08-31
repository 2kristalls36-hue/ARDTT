package com.nonamevpn.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUiActionTest {
    @Test
    fun bypassChipInactiveWithoutCallHash() {
        assertTrue(callHashMissing(hasCallHash = false))
        assertFalse(callHashMissing(hasCallHash = true))
    }

    @Test
    fun qsTileOpensSettingsOnlyWhenIdleAndNoHash() {
        assertTrue(qsTileOpensCallHashSettings(hasCallHash = false, running = false))
        assertFalse(qsTileOpensCallHashSettings(hasCallHash = false, running = true))
        assertFalse(qsTileOpensCallHashSettings(hasCallHash = true, running = false))
        assertFalse(qsTileOpensCallHashSettings(hasCallHash = true, running = true))
    }

    @Test
    fun hideIpCopyAvoidsWarpAndPryamoy() {
        assertEquals("Адрес сервера", HideIpCopy.SERVER_CHIP)
        assertEquals("Скрытый адрес", HideIpCopy.HIDDEN_CHIP)
        assertFalse(HideIpCopy.SERVER_CHIP.contains("Прямой"))
        assertFalse(HideIpCopy.HIDDEN_CHIP.contains("WARP", ignoreCase = true))
        assertFalse(HideIpCopy.subtitle(true).contains("WARP", ignoreCase = true))
        assertFalse(HideIpCopy.subtitle(true).contains("Cloudflare", ignoreCase = true))
        assertEquals("Выход с адреса сервера.", HideIpCopy.subtitle(false))
    }

    @Test
    fun openUpdateDownloadIsOneShotFlag() {
        PendingUiAction.consumeOpenUpdateDownload()
        PendingUiAction.requestOpenUpdateDownload()
        assertTrue(PendingUiAction.openUpdateDownload.value)
        assertTrue(PendingUiAction.consumeOpenUpdateDownload())
        assertFalse(PendingUiAction.openUpdateDownload.value)
        assertFalse(PendingUiAction.consumeOpenUpdateDownload())
    }

    @Test
    fun openAppearanceSettingsIsOneShotFlag() {
        PendingUiAction.consumeOpenAppearanceSettings()
        PendingUiAction.requestOpenAppearanceSettings()
        assertTrue(PendingUiAction.openAppearanceSettings.value)
        assertTrue(PendingUiAction.consumeOpenAppearanceSettings())
        assertFalse(PendingUiAction.openAppearanceSettings.value)
        assertFalse(PendingUiAction.consumeOpenAppearanceSettings())
    }
}
