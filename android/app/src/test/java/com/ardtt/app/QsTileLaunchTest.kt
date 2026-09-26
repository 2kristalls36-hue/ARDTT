package com.ardtt.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QsTileLaunchTest {
    @Test
    fun missingProfileDoesNotOpenSettingsOrCollapseTheShade() {
        assertEquals(
            QuickLaunchOutcome.MissingProfile,
            quickLaunchOutcome(hasActiveProfile = false, running = false, vpnConsentRequired = true),
        )
        assertEquals(
            QuickLaunchOutcome.Disconnect,
            quickLaunchOutcome(hasActiveProfile = false, running = true, vpnConsentRequired = false),
        )
        assertFalse(qsToggleCollapsesShade(QuickLaunchOutcome.MissingProfile))
    }

    @Test
    fun shortTapTogglesInPlaceEvenWithoutCallHash() {
        assertFalse(com.ardtt.app.ui.qsTileOpensCallHashSettings(hasCallHash = false, running = false))
        assertEquals(
            QuickLaunchOutcome.ConnectInPlace,
            quickLaunchOutcome(hasActiveProfile = true, running = false, vpnConsentRequired = false),
        )
        assertEquals(
            QuickLaunchOutcome.Disconnect,
            quickLaunchOutcome(hasActiveProfile = true, running = true, vpnConsentRequired = true),
        )
    }

    @Test
    fun coldStartWithSavedProfileConnectsWithoutOpeningTheApp() {
        assertEquals(
            QuickLaunchOutcome.ConnectInPlace,
            quickLaunchOutcome(hasActiveProfile = true, running = false, vpnConsentRequired = false),
        )
        assertFalse(qsToggleCollapsesShade(QuickLaunchOutcome.ConnectInPlace))
        assertFalse(qsToggleStartsActivity(QuickLaunchOutcome.ConnectInPlace))
    }

    @Test
    fun tileClickStartsTheForegroundServiceBeforeTheTileUnbinds() {
        assertEquals(
            QsClickEffect.StartToggleService,
            qsClickEffect(sessionUp = false, vpnConsentRequired = false),
        )
        assertEquals(
            QsClickEffect.Disconnect,
            qsClickEffect(sessionUp = true, vpnConsentRequired = true),
        )
        assertEquals(
            QsClickEffect.OpenVpnConsent,
            qsClickEffect(sessionUp = false, vpnConsentRequired = true),
        )
        assertTrue(qsClickStartsForegroundService(QsClickEffect.StartToggleService))
        assertFalse(qsClickStartsForegroundService(QsClickEffect.Disconnect))
        assertFalse(qsClickStartsForegroundService(QsClickEffect.OpenVpnConsent))
    }

    @Test
    fun shadeStaysOpenUnlessSystemVpnConsentIsRequired() {
        assertFalse(qsToggleCollapsesShade(QuickLaunchOutcome.ConnectInPlace))
        assertFalse(qsToggleCollapsesShade(QuickLaunchOutcome.Disconnect))
        assertFalse(qsToggleCollapsesShade(QuickLaunchOutcome.MissingProfile))
        assertTrue(qsToggleCollapsesShade(QuickLaunchOutcome.NeedVpnConsent))
        assertTrue(qsToggleStartsActivity(QuickLaunchOutcome.NeedVpnConsent))
        assertFalse(qsToggleStartsActivity(QuickLaunchOutcome.Disconnect))
        assertFalse(qsToggleStartsActivity(QuickLaunchOutcome.MissingProfile))
    }

    @Test
    fun widgetLaunchFlagsStayOnTheHomeWidgetTrampoline() {
        val flags = widgetToggleLaunchFlags()
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test
    fun longPressPreferencesShouldOpenMainActivityAction() {
        assertEquals(
            "android.service.quicksettings.action.QS_TILE_PREFERENCES",
            "android.service.quicksettings.action.QS_TILE_PREFERENCES",
        )
    }
}
