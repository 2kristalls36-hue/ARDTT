package com.ardtt.app.ui.tunnel

import androidx.compose.ui.graphics.Color
import com.ardtt.app.core.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonateSupportTest {
    @Test
    fun copyAndPayUrl() {
        assertEquals("Поддержка автора", DonateSupport.TITLE)
        assertEquals(
            "Донат на развитие проекта. Для вас — цена чашки кофе, для меня — стимул.",
            DonateSupport.BODY,
        )
        assertEquals("https://spasibomir.ru/pay/34807", DonateSupport.URL)
    }

    @Test
    fun showsOnlyWhileConnectedAndNotDismissed() {
        assertTrue(DonateSupport.bannerVisible(dismissed = false, state = ConnState.Connected))
        assertFalse(DonateSupport.bannerVisible(dismissed = true, state = ConnState.Connected))
        assertFalse(DonateSupport.bannerVisible(dismissed = false, state = ConnState.Ready))
        assertFalse(DonateSupport.bannerVisible(dismissed = false, state = ConnState.Connecting))
        assertFalse(DonateSupport.bannerVisible(dismissed = false, state = ConnState.PausedTrustedWifi))
    }

    @Test
    fun darkPaletteDropsGoldForThemePrimary() {
        val surface = Color(0xFF16202C)
        val primary = Color(0xFFA8D0FF)
        val primaryContainer = Color(0xFF1B4A75)
        val onSurfaceVariant = Color(0xFFB9C9DB)
        val dark = donateBannerPalette(
            isDark = true,
            surface = surface,
            primary = primary,
            primaryContainer = primaryContainer,
            onSurfaceVariant = onSurfaceVariant,
        )
        assertEquals(primary, dark.accent)
        assertEquals(onSurfaceVariant, dark.body)
        assertEquals(primary, dark.icon)
        assertFalse(dark.card == Color(0xFF2A2510))
        assertTrue(dark.card.red < 0.20f)
        assertTrue(dark.card.blue > dark.card.red)

        val light = donateBannerPalette(
            isDark = false,
            surface = surface,
            primary = primary,
            primaryContainer = primaryContainer,
            onSurfaceVariant = onSurfaceVariant,
        )
        assertEquals(Color(0xFFB8860B), light.accent)
        assertEquals(Color(0xFFFFFBE6), light.card)
        assertEquals(Color(0xFFB8860B).copy(alpha = 0.45f).alpha, light.border.alpha, 1e-3f)
    }
}
