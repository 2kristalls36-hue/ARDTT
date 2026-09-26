package com.ardtt.app.ui.components

import com.ardtt.app.ui.components.layout.ArdttHeaderDefaults
import com.ardtt.app.ui.components.layout.ArdttPullRefreshDefaults
import com.ardtt.app.ui.components.surface.ArdttSheetDefaults
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedDefaultsContractTest {
    @Test
    fun sharedComponentsKeepTokenAliases() {
        assertEquals(ArdttSize.TitleRow, ArdttHeaderDefaults.TitleRowHeight)
        assertEquals(ArdttLayout.SheetPadding, ArdttSheetDefaults.HorizontalPadding)
        assertEquals(ArdttSpacing.HairlinePlus, ArdttLayout.BadgeVerticalPadding)
    }

    @Test
    fun pullRefreshPositionTracksHeaderGeometry() {
        assertEquals(
            ArdttHeaderDefaults.TitleRowHeight + ArdttHeaderDefaults.TopPaddingAfterStatusBar,
            ArdttPullRefreshDefaults.IndicatorTop,
        )
    }

    @Test
    fun cardAndTerminalElevationPoliciesStayExplicit() {
        assertEquals(ArdttElevation.None, ArdttSurface.cardShadowElevation(dark = true))
        assertEquals(ArdttElevation.None, ArdttSurface.cardShadowElevation(dark = false))
        assertEquals(ArdttElevation.None, ArdttSurface.terminalShadowElevation(dark = true))
        assertEquals(ArdttElevation.None, ArdttSurface.terminalShadowElevation(dark = false))
    }
}
