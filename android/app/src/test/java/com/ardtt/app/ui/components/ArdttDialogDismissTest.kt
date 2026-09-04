package com.ardtt.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttDialogDismissTest {
    @Test
    fun hideAllowedWhenEitherDismissFlagIsOn() {
        assertTrue(ardttDialogAllowsHide(dismissOnBackPress = true, dismissOnClickOutside = true))
        assertTrue(ardttDialogAllowsHide(dismissOnBackPress = true, dismissOnClickOutside = false))
        assertTrue(ardttDialogAllowsHide(dismissOnBackPress = false, dismissOnClickOutside = true))
    }

    @Test
    fun hideBlockedWhenBothDismissFlagsAreOff() {
        assertFalse(ardttDialogAllowsHide(dismissOnBackPress = false, dismissOnClickOutside = false))
    }

    @Test
    fun lockedSheetGetsTitleTopPaddingWhenDragHandleIsHidden() {
        assertEquals(0, ardttDialogTitleTopPaddingDp(allowsHide = true))
        assertEquals(
            ARDTT_DIALOG_LOCKED_TITLE_TOP_DP,
            ardttDialogTitleTopPaddingDp(allowsHide = false),
        )
    }
}
