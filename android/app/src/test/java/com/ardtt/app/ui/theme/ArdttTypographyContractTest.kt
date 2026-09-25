package com.ardtt.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttTypographyContractTest {
    @Test
    fun materialScaleHasReadableLineHeights() {
        val styles = listOf(
            ArdttTypography.displayLarge,
            ArdttTypography.displayMedium,
            ArdttTypography.displaySmall,
            ArdttTypography.headlineLarge,
            ArdttTypography.headlineMedium,
            ArdttTypography.headlineSmall,
            ArdttTypography.titleLarge,
            ArdttTypography.titleMedium,
            ArdttTypography.titleSmall,
            ArdttTypography.bodyLarge,
            ArdttTypography.bodyMedium,
            ArdttTypography.bodySmall,
            ArdttTypography.labelLarge,
            ArdttTypography.labelMedium,
            ArdttTypography.labelSmall,
        )

        assertTrue(styles.all { it.hasReadableLineHeight() })
    }

    @Test
    fun semanticStylesRemainAliasesOfTheMaterialScale() {
        assertEquals(ArdttTypography.titleSmall.fontSize, ArdttButtonLabelStyle.fontSize)
        assertEquals(FontWeight.SemiBold, ArdttButtonLabelStyle.fontWeight)
        assertEquals(ArdttTypography.labelMedium.fontSize, ArdttNavigationLabelStyle.fontSize)
        assertEquals(FontWeight.ExtraBold, ArdttPageTitleStyle.fontWeight)
        assertEquals(ArdttTypography.titleMedium, ArdttSectionTitleStyle)
        assertEquals(ArdttTypography.bodyMedium.fontSize, ArdttValueTextStyle.fontSize)
        assertEquals(FontWeight.SemiBold, ArdttValueTextStyle.fontWeight)
    }

    @Test
    fun terminalStylesStayMonospaceAndOrdered() {
        assertEquals(FontFamily.Monospace, ArdttTerminalTextStyle.fontFamily)
        assertEquals(FontFamily.Monospace, ArdttTerminalLabelStyle.fontFamily)
        assertTrue(ArdttTerminalLabelStyle.fontSize < ArdttTerminalTextStyle.fontSize)
        assertTrue(ArdttTerminalTextStyle.hasReadableLineHeight())
    }
}

private fun TextStyle.hasReadableLineHeight(): Boolean =
    lineHeight.isSpecified && fontSize.isSpecified && lineHeight >= fontSize
