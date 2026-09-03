package com.nonamevpn.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvpnDialogDismissTest {
    @Test
    fun hideAllowedWhenEitherDismissFlagIsOn() {
        assertTrue(nvpnDialogAllowsHide(dismissOnBackPress = true, dismissOnClickOutside = true))
        assertTrue(nvpnDialogAllowsHide(dismissOnBackPress = true, dismissOnClickOutside = false))
        assertTrue(nvpnDialogAllowsHide(dismissOnBackPress = false, dismissOnClickOutside = true))
    }

    @Test
    fun hideBlockedWhenBothDismissFlagsAreOff() {
        assertFalse(nvpnDialogAllowsHide(dismissOnBackPress = false, dismissOnClickOutside = false))
    }
}
