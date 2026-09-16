package com.ardtt.app.ui.admin

/**
 * Accordion on the admin Diagnostics tab.
 *
 * Opening a tool pins a contiguous span from the earliest already-opened
 * item through the one just tapped (original order). The tapped button sits
 * at the top of that span with its content; later tools stay collapsed below
 * the pane. Tapping the open tool again returns to the hub.
 */
enum class DiagnosticsTool {
    Network,
    Logs,
}

data class DiagnosticsAccordionState(
    val expanded: DiagnosticsTool? = null,
    val selected: Set<DiagnosticsTool> = emptySet(),
)

data class DiagnosticsAccordionLayout(
    val leading: List<DiagnosticsTool>,
    val expanded: DiagnosticsTool?,
    val trailing: List<DiagnosticsTool>,
) {
    val showsTool: Boolean get() = expanded != null
}

fun diagnosticsTools(): List<DiagnosticsTool> = listOf(
    DiagnosticsTool.Network,
    DiagnosticsTool.Logs,
)

fun DiagnosticsAccordionState.open(
    tool: DiagnosticsTool,
    tools: List<DiagnosticsTool>,
): DiagnosticsAccordionState {
    if (tool !in tools) return this
    if (tool == expanded) {
        return DiagnosticsAccordionState()
    }
    val nextSelected = if (selected.isEmpty()) {
        setOf(tool)
    } else {
        val indices = (selected + tool).mapNotNull { item ->
            tools.indexOf(item).takeIf { it >= 0 }
        }
        val from = indices.minOrNull() ?: return copy(expanded = tool, selected = setOf(tool))
        val to = indices.maxOrNull() ?: return copy(expanded = tool, selected = setOf(tool))
        tools.subList(from, to + 1).toSet()
    }
    return DiagnosticsAccordionState(expanded = tool, selected = nextSelected)
}

fun DiagnosticsAccordionState.layout(
    tools: List<DiagnosticsTool>,
): DiagnosticsAccordionLayout {
    val liveExpanded = expanded?.takeIf { it in tools }
    if (liveExpanded == null) {
        return DiagnosticsAccordionLayout(
            leading = emptyList(),
            expanded = null,
            trailing = tools,
        )
    }
    val span = tools.filter { it in selected || it == liveExpanded }
    val idx = span.indexOf(liveExpanded).coerceAtLeast(0)
    val leading = span.take(idx)
    val trailing = tools.filter { it != liveExpanded && it !in leading }
    return DiagnosticsAccordionLayout(
        leading = leading,
        expanded = liveExpanded,
        trailing = trailing,
    )
}
