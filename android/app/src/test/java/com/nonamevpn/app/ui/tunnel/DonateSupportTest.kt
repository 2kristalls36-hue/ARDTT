package com.nonamevpn.app.ui.tunnel

import com.nonamevpn.app.core.ConnState
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
}
