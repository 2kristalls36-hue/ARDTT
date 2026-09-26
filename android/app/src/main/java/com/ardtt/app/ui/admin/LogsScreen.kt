package com.ardtt.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.feedback.ArdttEmptyState
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.surface.ArdttConfirmDialog
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.terminalCardColor
import com.ardtt.app.ui.components.surface.terminalCardElevation
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.ArdttTerminalLabelStyle
import com.ardtt.app.ui.theme.ArdttTerminalTextStyle
import com.ardtt.app.ui.theme.isDarkSurface
import com.ardtt.app.ui.theme.warningStatusColor
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.shareText
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@Composable
fun LogsScreen(
    onBack: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fmt = remember { logsLineFormat() }
    val isDark = isDarkSurface()
    val terminalBg = terminalCardColor()
    val bottomReserve = ArdttBottomChrome.navigationReserve()

    val sessionUp = ui.state == ConnState.Connected || ui.state == ConnState.PausedTrustedWifi
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(sessionUp, lifecycleOwner) {
        if (!sessionUp) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(LogsCopy.UPTIME_TICK_MS)
            }
        }
    }

    val uptimeText = formatJournalUptime(
        sessionUp = sessionUp,
        startedAtMs = conn.sessionStartedAtMs(),
        nowMs = nowMs,
    )

    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    @Composable
    fun LogsBody(topContentPadding: Dp, bottomContentPadding: Dp) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topContentPadding, bottom = bottomContentPadding)
                .padding(horizontal = if (embedded) ArdttSpacing.None else ArdttSpacing.Large),
        ) {
            ui.lastError?.takeIf { it.isNotBlank() }?.let { fatal ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = ArdttSpacing.Small),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = ArdttShapes.Row,
                ) {
                    Text(
                        fatal,
                        modifier = Modifier.padding(
                            horizontal = ArdttSpacing.Medium,
                            vertical = ArdttSpacing.SmallPlus,
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = ArdttTerminalTextStyle,
                    )
                }
            }
            ArdttSectionCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(ArdttSpacing.None),
                shape = ArdttShapes.Panel,
                color = terminalBg,
                shadowElevation = terminalCardElevation(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (logsShowsTerminalHeaderWithoutSession() || logsTerminalHeaderAlwaysShowsUptime()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) ArdttAlpha.Fill else ArdttAlpha.FillSoft,
                            ),
                            shape = ArdttShapes.PanelTop,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = ArdttSpacing.Medium,
                                        vertical = ArdttSpacing.SmallPlus,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = if (
                                        logsChromeUptimePlacement() == LogsChromeUptimePlacement.Start
                                    ) {
                                        Arrangement.Start
                                    } else {
                                        Arrangement.End
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(logsTerminalHeaderIconSize()),
                                    )
                                    Text(
                                        uptimeText,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = ArdttTerminalTextStyle,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = ArdttSpacing.Small),
                                    )
                                }
                                if (logsChromeActionAnchor() == LogsChromeActionAnchor.TerminalHeader) {
                                    LogsJournalHeaderActions(
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    if (entries.isEmpty()) {
                        ArdttEmptyState(
                            title = LogsCopy.EMPTY_TITLE,
                            description = LogsCopy.EMPTY_BODY,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    horizontal = ArdttSpacing.Medium,
                                    vertical = ArdttSpacing.SmallPlus,
                                ),
                            contentPadding = PaddingValues(bottom = ArdttSpacing.Large),
                            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
                        ) {
                            items(entries, key = { it.id }) { e ->
                                LogEventRow(entry = e, fmt = fmt)
                            }
                        }
                    }
                }
            }
        }
    }

    if (embedded) {
        LogsBody(ArdttSpacing.None, ArdttSpacing.None)
    } else {
        ArdttScrollChrome(
            header = {
                ArdttTabHeader(
                    title = "Журнал событий",
                    onBack = onBack,
                    actions = if (logsActionsInPageHeader(embedded = false)) {
                        { LogsJournalHeaderActions() }
                    } else {
                        null
                    },
                )
            },
        ) { chromeTop ->
            LogsBody(chromeTop, bottomReserve)
        }
    }
}

/** Copy / Share / Delete for the journal terminal chrome. */
@Composable
internal fun RowScope.LogsJournalHeaderActions(
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val fmt = remember { logsLineFormat() }
    var showClearConfirm by remember { mutableStateOf(false) }
    val dump = { logsDumpBody(entries, fmt) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(logsHeaderActionSpacing()),
    ) {
        logsHeaderActionOrder().forEach { label ->
            when (label) {
                LogsCopy.COPY -> LogsHeaderIconButton(
                    onClick = { copyToClipboard(context, dump(), "ARDTT logs") },
                    icon = Icons.Default.ContentCopy,
                    contentDescription = label,
                    contentColor = contentColor,
                )
                LogsCopy.SHARE -> LogsHeaderIconButton(
                    onClick = { shareText(context, dump(), "ARDTT logs", "Экспорт логов") },
                    icon = Icons.Default.Share,
                    contentDescription = label,
                    contentColor = contentColor,
                )
                LogsCopy.CLEAR -> LogsHeaderIconButton(
                    onClick = { showClearConfirm = true },
                    icon = Icons.Default.Delete,
                    contentDescription = label,
                    enabled = entries.isNotEmpty(),
                    contentColor = contentColor,
                )
            }
        }
    }
    if (showClearConfirm) {
        ArdttConfirmDialog(
            title = LogsCopy.CLEAR_TITLE,
            body = LogsCopy.clearBody(entries.size),
            confirmText = LogsCopy.CLEAR,
            onConfirm = {
                showClearConfirm = false
                AppLog.clear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun LogsHeaderIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    ArdttButton(
        onClick = onClick,
        variant = ArdttButtonVariant.Icon,
        size = ArdttButtonSize.Compact,
        icon = icon,
        contentDescription = contentDescription,
        enabled = enabled,
        contentColor = contentColor,
    )
}

/** Strings of the Logs screen that are not derived from state. */
internal object LogsCopy {
    const val COPY = "Копировать"
    const val SHARE = "Расшарить"
    const val CLEAR = "Удалить"
    const val CLEAR_TITLE = "Очистить журнал?"
    const val EMPTY_TITLE = "Пока пусто"
    const val EMPTY_BODY = "Нажмите «Сеть» или «Подключить» — сюда пойдут probe / туннель / go_client."
    const val UPTIME_TICK_MS = 1_000L
    const val UPTIME_IDLE = "00:00"

    fun clearBody(count: Int): String =
        "Будет удалено записей: $count. Действие нельзя отменить; при необходимости сначала скопируйте или поделитесь журналом."
}

internal fun logsHeaderActionOrder(): List<String> =
    listOf(LogsCopy.COPY, LogsCopy.SHARE, LogsCopy.CLEAR)

/** Gap between copy, share, and delete. The glyphs are 24 dp and used to touch. */
internal fun logsHeaderActionSpacing() = ArdttLayout.ControlSpacing

internal fun logsShowsInlineActionRow(embedded: Boolean): Boolean = false

internal enum class LogsChromeActionAnchor {
    PageHeader,
    TerminalHeader,
}

internal enum class LogsChromeUptimePlacement {
    Start,
    End,
}

internal fun logsChromeActionAnchor(): LogsChromeActionAnchor =
    LogsChromeActionAnchor.TerminalHeader

internal fun logsActionsInPageHeader(embedded: Boolean): Boolean = false

internal fun logsShowsTerminalHeaderWithoutSession(): Boolean = true

internal fun logsTerminalHeaderAlwaysShowsUptime(): Boolean = true

internal fun logsTerminalHeaderIconSize() = ArdttSize.IconLarge

internal fun logsChromeUptimePlacement(): LogsChromeUptimePlacement =
    LogsChromeUptimePlacement.Start

internal fun logsDumpBody(entries: List<AppLog.Entry>, fmt: SimpleDateFormat): String =
    entries.joinToString("\n") { it.displayLine(fmt) }.ifBlank { AppLog.dumpText() }

private fun logsLineFormat(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun LogEventRow(
    entry: AppLog.Entry,
    fmt: SimpleDateFormat,
) {
    val labelColor = when (entry.level) {
        AppLog.Level.E -> MaterialTheme.colorScheme.error
        AppLog.Level.W -> warningStatusColor()
        AppLog.Level.I -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bodyColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = LogsCatalog.levelShort(entry.level),
            color = labelColor,
            style = ArdttTerminalLabelStyle,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = ArdttSpacing.Hairline),
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayLine(fmt),
                color = bodyColor,
                style = ArdttTerminalTextStyle,
            )
        }
    }
}

internal fun formatJournalUptime(
    sessionUp: Boolean,
    startedAtMs: Long,
    nowMs: Long,
): String {
    if (!sessionUp || startedAtMs <= 0L) return LogsCopy.UPTIME_IDLE
    val ms = (nowMs - startedAtMs).coerceAtLeast(0L)
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
