package com.ardtt.app.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAccordionTest {
    private val tools = diagnosticsTools(testingVisible = true)

    @Test
    fun hubKeepsOriginalOrder() {
        val layout = DiagnosticsAccordionState().layout(tools)
        assertNull(layout.expanded)
        assertTrue(layout.leading.isEmpty())
        assertEquals(tools, layout.trailing)
    }

    @Test
    fun openingNetworkMovesItToTopWithOthersBelow() {
        val state = DiagnosticsAccordionState().open(DiagnosticsTool.Network, tools)
        val layout = state.layout(tools)
        assertEquals(DiagnosticsTool.Network, layout.expanded)
        assertTrue(layout.leading.isEmpty())
        assertEquals(
            listOf(DiagnosticsTool.Logs, DiagnosticsTool.Testing),
            layout.trailing,
        )
    }

    @Test
    fun openingTestingAfterNetworkPinsNetworkThenUnfoldsLogsAtTop() {
        val afterNetwork = DiagnosticsAccordionState().open(DiagnosticsTool.Network, tools)
        val afterTesting = afterNetwork.open(DiagnosticsTool.Testing, tools)
        val layout = afterTesting.layout(tools)
        assertEquals(
            listOf(DiagnosticsTool.Network, DiagnosticsTool.Logs),
            layout.leading,
        )
        assertEquals(DiagnosticsTool.Testing, layout.expanded)
        assertTrue(layout.trailing.isEmpty())
    }

    @Test
    fun openingLogsAfterNetworkKeepsNetworkAtTop() {
        val state = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Network, tools)
            .open(DiagnosticsTool.Logs, tools)
        val layout = state.layout(tools)
        assertEquals(listOf(DiagnosticsTool.Network), layout.leading)
        assertEquals(DiagnosticsTool.Logs, layout.expanded)
        assertEquals(listOf(DiagnosticsTool.Testing), layout.trailing)
    }

    @Test
    fun openingTestingFirstOnlyMovesTesting() {
        val layout = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Testing, tools)
            .layout(tools)
        assertTrue(layout.leading.isEmpty())
        assertEquals(DiagnosticsTool.Testing, layout.expanded)
        assertEquals(
            listOf(DiagnosticsTool.Network, DiagnosticsTool.Logs),
            layout.trailing,
        )
    }

    @Test
    fun tappingExpandedToolReturnsToHub() {
        val state = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Network, tools)
            .open(DiagnosticsTool.Network, tools)
        val layout = state.layout(tools)
        assertNull(layout.expanded)
        assertEquals(tools, layout.trailing)
    }

    @Test
    fun hiddenTestingIsDroppedFromLayout() {
        val two = diagnosticsTools(testingVisible = false)
        val state = DiagnosticsAccordionState()
            .open(DiagnosticsTool.Testing, tools)
        val layout = state.layout(two)
        assertNull(layout.expanded)
        assertEquals(two, layout.trailing)
        assertEquals(2, two.size)
    }
}
