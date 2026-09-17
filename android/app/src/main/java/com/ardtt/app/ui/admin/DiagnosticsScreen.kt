package com.ardtt.app.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttDestinationRow
import com.ardtt.app.ui.components.layout.ArdttFeedScaffold
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.theme.ArdttLayout

@Composable
fun DiagnosticsScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    reselectSignal: Int = 0,
) {
    val tools = diagnosticsTools()
    val initial = diagnosticsInitialState()
    var expandedName by rememberSaveable { mutableStateOf(initial.expanded?.name) }
    var selectedNames by rememberSaveable {
        mutableStateOf(tools.filter { it in initial.selected }.map { it.name })
    }
    val expanded = expandedName?.let { name ->
        DiagnosticsTool.entries.firstOrNull { it.name == name }
    }?.takeIf { it in tools }
    val selected = selectedNames.mapNotNull { name ->
        DiagnosticsTool.entries.firstOrNull { it.name == name }
    }.filter { it in tools }.toSet()
    val accordion = DiagnosticsAccordionState(
        expanded = expanded,
        selected = selected,
    )
    val layout = accordion.layout(tools)

    fun select(tool: DiagnosticsTool) {
        val next = accordion.open(tool, tools)
        expandedName = next.expanded?.name
        selectedNames = tools.filter { it in next.selected }.map { it.name }
    }

    BackHandler(enabled = layout.showsTool) {
        expandedName = null
        selectedNames = emptyList()
    }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal > 0) {
            val reset = diagnosticsInitialState()
            expandedName = reset.expanded?.name
            selectedNames = tools.filter { it in reset.selected }.map { it.name }
        }
    }

    if (!layout.showsTool) {
        ArdttFeedScaffold(
            modifier = Modifier.fillMaxSize(),
            header = {
                ArdttTabHeader(
                    title = DiagnosticsCopy.TITLE,
                    subtitle = diagnosticsHeaderSubtitle(),
                )
            },
            stickyContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(ArdttLayout.FeedSpacing),
                ) {
                    layout.trailing.forEach { tool ->
                        DiagnosticsToolRow(
                            tool = tool,
                            expanded = false,
                            onClick = { select(tool) },
                        )
                    }
                }
            },
        ) {
            // Hub actions sit in stickyContent above the tab pill.
        }
        return
    }

    val openTool = layout.expanded ?: return
    ArdttScrollChrome(
        modifier = Modifier.fillMaxSize(),
        header = {
            ArdttTabHeader(
                title = DiagnosticsCopy.TITLE,
                subtitle = diagnosticsHeaderSubtitle(),
                actions = if (openTool == DiagnosticsTool.Logs && logsActionsInPageHeader(embedded = true)) {
                    { LogsJournalHeaderActions() }
                } else {
                    null
                },
            )
        },
    ) { topPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ArdttLayout.ScreenPadding)
                .padding(top = topPad, bottom = ArdttBottomChrome.navigationReserve()),
            verticalArrangement = Arrangement.spacedBy(ArdttLayout.FeedSpacing),
        ) {
            layout.leading.forEach { tool ->
                DiagnosticsToolRow(
                    tool = tool,
                    expanded = false,
                    onClick = { select(tool) },
                )
            }
            DiagnosticsToolRow(
                tool = openTool,
                expanded = true,
                onClick = { select(openTool) },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                when (openTool) {
                    DiagnosticsTool.Network -> NetworkScreen(
                        settings = settings,
                        profiles = profiles,
                        serversRepo = serversRepo,
                        embedded = true,
                    )
                    DiagnosticsTool.Logs -> LogsScreen(embedded = true)
                }
            }
            layout.trailing.forEach { tool ->
                DiagnosticsToolRow(
                    tool = tool,
                    expanded = false,
                    onClick = { select(tool) },
                )
            }
            Spacer(Modifier.height(ArdttLayout.FeedBottomExtra))
        }
    }
}

@Composable
private fun DiagnosticsToolRow(
    tool: DiagnosticsTool,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val spec = diagnosticsToolSpec(tool)
    ArdttDestinationRow(
        icon = spec.icon,
        title = spec.title,
        subtitle = spec.subtitle,
        onClick = onClick,
        expanded = expanded,
    )
}

private data class DiagnosticsToolSpec(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

private fun diagnosticsToolSpec(tool: DiagnosticsTool): DiagnosticsToolSpec = when (tool) {
    DiagnosticsTool.Network -> DiagnosticsToolSpec(
        icon = Icons.Outlined.Wifi,
        title = "Сеть",
        subtitle = DiagnosticsCopy.NETWORK_SUBTITLE,
    )
    DiagnosticsTool.Logs -> DiagnosticsToolSpec(
        icon = Icons.Outlined.Terminal,
        title = "Журнал",
        subtitle = DiagnosticsCopy.LOGS_SUBTITLE,
    )
}

internal object DiagnosticsCopy {
    const val TITLE = "Диагностика"
    const val NETWORK_SUBTITLE = "Карта пути и задержки"
    const val LOGS_SUBTITLE = "События туннеля и деплоя"
}

internal fun diagnosticsHeaderSubtitle(): String? = null
