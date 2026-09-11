package com.ardtt.app.ui.components.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttScrollChromeTest {
    @Test
    fun gpuBlurStartsAtAndroid12() {
        assertFalse(ardttScrollChromeUsesGpuBlur(28))
        assertFalse(ardttScrollChromeUsesGpuBlur(30))
        assertTrue(ardttScrollChromeUsesGpuBlur(31))
        assertTrue(ardttScrollChromeUsesGpuBlur(35))
    }

    @Test
    fun contentStartsBelowFadeAtScrollZero() {
        val chrome = 72.dp
        val fade = 28.dp
        assertEquals(100.dp, ardttScrollChromeTopPadding(chrome, fade))
    }

    @Test
    fun contentFadeIsAlphaNotOpaqueWhite() {
        val stops = ardttScrollChromeContentFadeStops()
        assertEquals(0f, stops[0].first)
        assertEquals(0f, stops[0].second.alpha, 0f)
        assertEquals(ArdttScrollChromeFadeMid, stops[1].first)
        assertEquals(0f, stops[1].second.alpha, 0f)
        assertEquals(1f, stops[2].first)
        assertEquals(Color.Black, stops[2].second)
    }

    @Test
    fun blurFadeIsInverseOfContentFade() {
        val content = ardttScrollChromeContentFadeStops()
        val blur = ardttScrollChromeBlurFadeStops()
        assertEquals(content.size, blur.size)
        content.zip(blur).forEach { (c, b) ->
            assertEquals(c.first, b.first)
            assertEquals(1f - c.second.alpha, b.second.alpha, 0f)
        }
    }
}
