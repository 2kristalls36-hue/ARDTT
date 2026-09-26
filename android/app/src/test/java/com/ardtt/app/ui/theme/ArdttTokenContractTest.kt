package com.ardtt.app.ui.theme

import androidx.compose.ui.unit.LayoutDirection
import com.ardtt.app.ui.components.surface.messageCardDismissUsesCompactIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArdttTokenContractTest {
    @Test
    fun compactBadgeInsetStaysBelowTheSpacingScale() {
        assertEquals(ArdttSpacing.HairlinePlus, ArdttLayout.BadgeVerticalPadding)
        assertTrue(ArdttLayout.BadgeVerticalPadding < ArdttSpacing.Tiny)
    }

    @Test
    fun semanticSizeAliasesStayConnectedToTheirBaseSteps() {
        assertEquals(ArdttSize.IconSmall, ArdttSize.SpinnerSmall)
        assertEquals(ArdttSize.Icon, ArdttSize.Spinner)
        assertEquals(ArdttSize.IconFeature, ArdttSize.SpinnerLarge)
        assertEquals(ArdttSize.Contour, ArdttSize.Stroke)
    }

    @Test
    fun noteCardGivesTheParagraphOneInset() {
        val padding = ArdttLayout.NoteCardPadding
        assertEquals(ArdttSpacing.Large, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(ArdttSpacing.Large, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(ArdttSpacing.Medium, padding.calculateTopPadding())
        assertEquals(ArdttSpacing.Medium, padding.calculateBottomPadding())
        assertEquals(ArdttSpacing.Small, ArdttLayout.NoteCardSpacing)
        assertTrue(
            padding.calculateLeftPadding(LayoutDirection.Ltr) >
                ArdttLayout.CardPadding.calculateLeftPadding(LayoutDirection.Ltr),
        )
        assertTrue(messageCardDismissUsesCompactIcon())
    }

    @Test
    fun stackedLabelsKeepAirBetweenTitleAndSupport() {
        assertEquals(ArdttSpacing.TinyPlus, ArdttLayout.StackedLabelSpacing)
        assertTrue(ArdttLayout.StackedLabelSpacing > ArdttSpacing.Hairline)
    }

    @Test
    fun sharedLayoutRolesUseTheCommonScale() {
        assertEquals(ArdttSpacing.SmallPlus, ArdttLayout.ScreenPadding)
        assertEquals(ArdttSpacing.XLargePlus, ArdttLayout.SheetPadding)
        assertEquals(ArdttSpacing.HairlinePlus, ArdttLayout.BadgeVerticalPadding)
    }

    @Test
    fun interactiveRowsMeetTheMinimumTouchTarget() {
        assertEquals(ArdttSize.TouchTarget, ArdttSize.TitleRow)
        assertTrue(ArdttSize.Button >= ArdttSize.TouchTarget)
        assertTrue(ArdttSize.ButtonCompact >= ArdttSize.TouchTarget)
        assertTrue(ArdttSize.MenuItem >= ArdttSize.TouchTarget)
    }

    @Test
    fun navigationZoneIncludesTrackPadding() {
        assertEquals(
            ArdttSize.NavTrack + ArdttSpacing.Small * 2,
            ArdttSize.NavZone,
        )
    }

    @Test
    fun cardElevationFollowsTheSharedSurfacePolicy() {
        assertEquals(ArdttElevation.None, ArdttSurface.cardShadowElevation(dark = true))
        assertEquals(ArdttElevation.None, ArdttSurface.cardShadowElevation(dark = false))
    }

    @Test
    fun alphaAndMotionContractsRemainValid() {
        val alphas = listOf(
            ArdttAlpha.Contour,
            ArdttAlpha.Fill,
            ArdttAlpha.FillSoft,
            ArdttAlpha.Outline,
            ArdttAlpha.Divider,
            ArdttAlpha.Shadow,
            ArdttAlpha.Disabled,
            ArdttAlpha.DisabledContainer,
            ArdttAlpha.Muted,
            ArdttAlpha.Subtle,
            ArdttAlpha.Strong,
        )
        assertTrue(alphas.all { it in 0f..1f })
        assertTrue(ArdttMotion.Quick < ArdttMotion.Fast)
        assertTrue(ArdttMotion.Fast < ArdttMotion.Standard)
        assertTrue(ArdttMotion.Standard <= ArdttMotion.Relaxed)
        assertTrue(ArdttMotion.Relaxed < ArdttMotion.Slow)
    }
}
