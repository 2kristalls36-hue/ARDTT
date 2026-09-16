package com.ardtt.app.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAccordionTest {
    private val tools = diagnosticsTools()

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
}
