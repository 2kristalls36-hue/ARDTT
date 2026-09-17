package com.ardtt.app.ui.admin

import androidx.compose.ui.unit.dp
import com.ardtt.app.ui.components.layout.ardttScrollChromeTopPadding
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAccordionTest {
    private val tools = diagnosticsTools()

    @Test
    fun diagnosticsStartsWithNetworkExpanded() {
        val layout = diagnosticsInitialState().layout(tools)
        assertEquals(DiagnosticsTool.Network, layout.expanded)
        assertTrue(layout.leading.isEmpty())
        assertEquals(listOf(DiagnosticsTool.Logs), layout.trailing)
    }

    @Test
    fun hubKeepsNetworkAndLogs() {
        val layout = DiagnosticsAccordionState().layout(tools)
        assertNull(layout.expanded)
        assertTrue(layout.leading.isEmpty())
        assertEquals(
            listOf(DiagnosticsTool.Network, DiagnosticsTool.Logs),
            layout.trailing,
        )
    }

    @Test
    fun openingNetworkMovesItToTopWithLogsBelow() {
        val layout = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Network, tools)
            .layout(tools)
        assertEquals(DiagnosticsTool.Network, layout.expanded)
        assertTrue(layout.leading.isEmpty())
        assertEquals(listOf(DiagnosticsTool.Logs), layout.trailing)
    }

    @Test
    fun openingLogsAfterNetworkKeepsNetworkAtTop() {
        val layout = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Network, tools)
            .open(DiagnosticsTool.Logs, tools)
            .layout(tools)
        assertEquals(listOf(DiagnosticsTool.Network), layout.leading)
        assertEquals(DiagnosticsTool.Logs, layout.expanded)
        assertTrue(layout.trailing.isEmpty())
    }

    @Test
    fun openingLogsFirstOnlyMovesLogs() {
        val layout = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Logs, tools)
            .layout(tools)
        assertTrue(layout.leading.isEmpty())
        assertEquals(DiagnosticsTool.Logs, layout.expanded)
        assertEquals(listOf(DiagnosticsTool.Network), layout.trailing)
    }

    @Test
    fun tappingExpandedToolReturnsToHub() {
        val layout = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Network, tools)
            .open(DiagnosticsTool.Network, tools)
            .layout(tools)
        assertNull(layout.expanded)
        assertEquals(tools, layout.trailing)
    }

    @Test
    fun diagnosticsHeaderOmitsNetworkAndLogsSubtitle() {
        assertTrue(diagnosticsHeaderSubtitle().isNullOrBlank())
    }

    @Test
    fun diagnosticsPacksOpenToolAgainstHeaderAndTabPill() {
        assertEquals(ArdttSpacing.None, diagnosticsChromeFade())
        assertEquals(ArdttSpacing.Tiny, diagnosticsFeedSpacing())
        assertEquals(ArdttSpacing.None, diagnosticsFeedBottomExtra())
        assertTrue(diagnosticsFeedSpacing() < ArdttLayout.FeedSpacing)
        assertTrue(diagnosticsFeedBottomExtra() < ArdttLayout.FeedBottomExtra)
        assertEquals(
            72.dp,
            ardttScrollChromeTopPadding(72.dp, diagnosticsChromeFade()),
        )
    }
}
