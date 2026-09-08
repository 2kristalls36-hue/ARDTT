package com.ardtt.app.ui.components.control

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttButtonContractTest {
    @Test
    fun labeledPrimaryFillsWidthAndKeepsSidePadding() {
        assertTrue(ardttButtonShowsText(ArdttButtonVariant.Primary, "Подключиться"))
        assertTrue(ardttButtonFillsWidth(ArdttButtonVariant.Primary, ArdttButtonSize.Regular, "Подключиться"))
        assertEquals(
            PaddingValues(horizontal = ArdttSpacing.Large, vertical = ArdttSpacing.Small),
            ardttButtonContentPadding(ArdttButtonVariant.Primary, showsText = true),
        )
    }

    @Test
    fun iconOnlyPrimaryStaysInTheClusterSlot() {
        assertFalse(ardttButtonShowsText(ArdttButtonVariant.Primary, ""))
        assertFalse(ardttButtonShowsText(ArdttButtonVariant.Primary, "  "))
        assertFalse(ardttButtonFillsWidth(ArdttButtonVariant.Primary, ArdttButtonSize.Regular, ""))
        assertEquals(
            PaddingValues(ArdttSpacing.None),
            ardttButtonContentPadding(ArdttButtonVariant.Primary, showsText = false),
        )
        assertTrue(ArdttSize.ButtonCluster >= ArdttSize.Icon + ArdttSpacing.Small)
        assertEquals(62.dp, ArdttSize.ButtonCluster)
    }

    @Test
    fun iconVariantNeverTreatsItsTextAsALabel() {
        assertFalse(ardttButtonShowsText(ArdttButtonVariant.Icon, "Закрыть"))
        assertFalse(ardttButtonFillsWidth(ArdttButtonVariant.Icon, ArdttButtonSize.Regular, "Закрыть"))
        assertEquals(ArdttSize.TouchTarget, ardttButtonMinHeight(ArdttButtonVariant.Icon, ArdttButtonSize.Regular))
        assertEquals(ArdttSize.Button, ardttButtonMinHeight(ArdttButtonVariant.Primary, ArdttButtonSize.Regular))
        assertEquals(ArdttSize.ButtonCompact, ardttButtonMinHeight(ArdttButtonVariant.Text, ArdttButtonSize.Compact))
    }
}
