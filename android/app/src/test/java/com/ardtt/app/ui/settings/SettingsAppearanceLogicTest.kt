package com.ardtt.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAppearanceLogicTest {
    @Test
    fun themeControlsStayVisibleWhileRecording() {
        val adminRecording = settingsAppearanceSections(admin = true, recordingActive = true)
        assertTrue(adminRecording.showThemeControls)
        assertFalse(adminRecording.showClassicLook)

        val userRecording = settingsAppearanceSections(admin = false, recordingActive = true)
        assertTrue(userRecording.showThemeControls)
        assertTrue(userRecording.showClassicLook)
    }

    @Test
    fun classicLookOnlyInUserMode() {
        assertTrue(settingsAppearanceSections(admin = false, recordingActive = false).showClassicLook)
        assertFalse(settingsAppearanceSections(admin = true, recordingActive = false).showClassicLook)
    }
}
