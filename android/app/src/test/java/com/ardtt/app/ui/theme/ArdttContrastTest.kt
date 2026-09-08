package com.ardtt.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttContrastTest {
    @Test
    fun lightPrimaryOnWhiteMeetsTextContrast() {
        val ratio = ArdttSurface.contrastRatio(Color.White, Color(0xFF1565C0))
        assertTrue("primary/onPrimary $ratio", ratio >= 4.5f)
    }

    @Test
    fun connectedAndWarningKeepReadableWhiteLabels() {
        assertTrue(ArdttSurface.contrastRatio(Color.White, ArdttColors.Connected) >= 4.5f)
        assertTrue(ArdttSurface.contrastRatio(Color.White, ArdttColors.Warning) >= 4.5f)
    }

    @Test
    fun contentColorOnPicksReadableForegroundOnLegacyPrimary() {
        val mid = Color(0xFF4A90E2)
        val chosen = ArdttSurface.contentColorOn(mid)
        assertTrue(ArdttSurface.contrastRatio(chosen, mid) >= 4.5f)
    }

    @Test
    fun contentColorOnPicksTheHigherContrastCandidate() {
        val mid = Color(0xFF4A90E2)
        val chosen = ArdttSurface.contentColorOn(mid)
        val chosenRatio = ArdttSurface.contrastRatio(chosen, mid)
        val whiteRatio = ArdttSurface.contrastRatio(Color.White, mid)
        val darkRatio = ArdttSurface.contrastRatio(ArdttSurface.DarkContent, mid)
        assertTrue(chosenRatio + 0.01f >= maxOf(whiteRatio, darkRatio))
        assertTrue(chosenRatio >= 4.5f || chosenRatio >= whiteRatio)
    }

    @Test
    fun translucentLabelIsCompositedBeforeContrast() {
        val fg = Color.White.copy(alpha = 0.5f)
        val bg = Color(0xFF1565C0)
        val composed = ArdttSurface.compositeOver(fg, bg)
        val raw = ArdttSurface.contrastRatio(fg, bg)
        val opaqueWhite = ArdttSurface.contrastRatio(Color.White, bg)
        assertTrue(raw < opaqueWhite)
        assertTrue(ArdttSurface.contrastRatio(composed, bg) < opaqueWhite)
    }
}
