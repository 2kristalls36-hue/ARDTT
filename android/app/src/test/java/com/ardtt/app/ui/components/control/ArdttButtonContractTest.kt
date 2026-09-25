package com.ardtt.app.ui.components.control

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttSurface
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
    fun labeledDangerFillsWidthLikePrimary() {
        assertTrue(ardttButtonFillsWidth(ArdttButtonVariant.Primary, ArdttButtonSize.Regular, "Авторизация"))
        assertTrue(ardttButtonFillsWidth(ArdttButtonVariant.Danger, ArdttButtonSize.Regular, "Завершить"))
        assertFalse(ardttButtonFillsWidth(ArdttButtonVariant.Danger, ArdttButtonSize.Compact, "Завершить"))
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
    fun disabledFilledButtonRecedesButKeepsItsHue() {
        val pair = ButtonPair(container = Color(0xFF1565C0), content = Color.White)
        val disabled = ardttDisabledButtonColors(ArdttButtonVariant.Primary, pair, containerOverridden = false)
        assertEquals(ArdttAlpha.DisabledContainer, disabled.container.alpha, 0.01f)
        assertEquals(pair.container.red, disabled.container.red, 0.001f)
        assertEquals(pair.container.blue, disabled.container.blue, 0.001f)
        assertEquals(ArdttAlpha.Subtle, disabled.content.alpha, 0.01f)
        assertTrue(disabled.container.alpha < pair.container.alpha)
    }

    @Test
    fun disabledGlassCtaRecedesFromItsOwnAlpha() {
        val glass = Color(0xFF1565C0).copy(alpha = 0.92f)
        val pair = ButtonPair(container = glass, content = Color.White)
        val disabled = ardttDisabledButtonColors(ArdttButtonVariant.Primary, pair, containerOverridden = false)
        assertEquals(0.92f * ArdttAlpha.DisabledContainer, disabled.container.alpha, 0.01f)
        assertTrue(disabled.container.alpha < glass.alpha)
    }

    @Test
    fun floatingLabelKeepsAReadableOverride() {
        val dark = Color(0xFF102033)
        assertTrue(ArdttSurface.contrastRatio(Color.White, dark) >= ArdttSurface.TextContrastMin)
        assertEquals(Color.White, ardttFloatingContentColor(dark, Color.White))
    }

    @Test
    fun floatingLabelDropsAnOverrideThatFailsContrast() {
        val pale = Color(0xFFFFE0C2)
        assertTrue(ArdttSurface.contrastRatio(Color.White, pale) < ArdttSurface.TextContrastMin)
        assertEquals(ArdttSurface.contentColorOn(pale), ardttFloatingContentColor(pale, Color.White))
        assertEquals(ArdttSurface.contentColorOn(pale), ardttFloatingContentColor(pale, null))
    }

    @Test
    fun disabledButtonKeepsCallerContainerOverride() {
        val locked = Color(0xFFFAFCFF).copy(alpha = ArdttAlpha.Strong)
        val pair = ButtonPair(container = locked, content = Color(0xFF1C1B1A))
        val disabled = ardttDisabledButtonColors(ArdttButtonVariant.Primary, pair, containerOverridden = true)
        assertEquals(locked, disabled.container)
    }

    @Test
    fun disabledOutlineAndTextLabelsShareTheDisabledStep() {
        val pair = ButtonPair(container = Color.Transparent, content = Color(0xFF1565C0))
        for (variant in listOf(ArdttButtonVariant.Outlined, ArdttButtonVariant.Text, ArdttButtonVariant.Icon)) {
            val disabled = ardttDisabledButtonColors(variant, pair, containerOverridden = false)
            assertEquals(variant.name, Color.Transparent, disabled.container)
            assertEquals(variant.name, ArdttAlpha.Disabled, disabled.content.alpha, 0.01f)
        }
    }

    @Test
    fun iconVariantNeverTreatsItsTextAsALabel() {
        assertFalse(ardttButtonShowsText(ArdttButtonVariant.Icon, "Закрыть"))
        assertFalse(ardttButtonFillsWidth(ArdttButtonVariant.Icon, ArdttButtonSize.Regular, "Закрыть"))
        assertEquals(ArdttSize.TouchTarget, ardttButtonMinHeight(ArdttButtonVariant.Icon, ArdttButtonSize.Regular))
        assertEquals(ArdttSize.IconLarge, ardttButtonMinHeight(ArdttButtonVariant.Icon, ArdttButtonSize.Compact))
        assertEquals(ArdttSize.Button, ardttButtonMinHeight(ArdttButtonVariant.Primary, ArdttButtonSize.Regular))
        assertEquals(ArdttSize.ButtonCompact, ardttButtonMinHeight(ArdttButtonVariant.Text, ArdttButtonSize.Compact))
        assertTrue(ardttCompactIconUsesExactMinSize())
        assertTrue(
            ardttButtonMinHeight(ArdttButtonVariant.Icon, ArdttButtonSize.Compact) <
                ArdttSize.TouchTarget,
        )
    }
}
