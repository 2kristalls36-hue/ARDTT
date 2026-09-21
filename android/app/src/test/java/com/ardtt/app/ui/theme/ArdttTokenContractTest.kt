package com.ardtt.app.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttTokenContractTest {
    @Test
    fun compactBadgeInsetStaysBelowTheSpacingScale() {
        assertEquals(3.dp, ArdttLayout.BadgeVerticalPadding)
        assertTrue(ArdttLayout.BadgeVerticalPadding < ArdttSpacing.Tiny)
    }
}
