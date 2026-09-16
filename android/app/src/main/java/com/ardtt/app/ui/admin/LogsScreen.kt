package com.ardtt.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ardtt.app.ui.theme.ArdttRadius
import com.ardtt.app.ui.theme.ArdttShapes
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
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val isDark = isDarkSurface()
    val terminalBg = terminalCardColor()
    val bottomReserve = ArdttBottomChrome.navigationReserve()

    val sessionUp = ui.state == ConnState.Connected || ui.state == ConnState.PausedTrustedWifi
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showClearConfirm by remember { mutableStateOf(false) }
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

    val uptimeText = remember(sessionUp, nowMs, entries.firstOrNull()?.id) {
        if (!sessionUp) null else formatUptimeRough(entries, nowMs)
    }

    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    fun dumpBody(): String =
        entries.joinToString("\n") { it.displayLine(fmt) }.ifBlank { AppLog.dumpText() }

    val logActions: @Composable RowScope.() -> Unit = {
        ArdttButton(
            onClick = { copyToClipboard(context, dumpBody(), "ARDTT logs") },
            variant = ArdttButtonVariant.Icon,
            icon = Icons.Default.ContentCopy,
            contentDescription = "Копировать",
            contentColor = MaterialTheme.colorScheme.primary,
        )
        ArdttButton(
            onClick = { shareText(context, dumpBody(), "ARDTT logs", "Экспорт логов") },
            variant = ArdttButtonVariant.Icon,
            icon = Icons.Default.Share,
            contentDescription = "Расшарить",
            contentColor = MaterialTheme.colorScheme.primary,
        )
        ArdttButton(
            onClick = { showClearConfirm = true },
            variant = ArdttButtonVariant.Icon,
            icon = Icons.Default.Delete,
            contentDescription = LogsCopy.CLEAR,
            enabled = entries.isNotEmpty(),
            contentColor = MaterialTheme.colorScheme.primary,
        )
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
                    if (uptimeText != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) ArdttAlpha.Fill else ArdttAlpha.FillSoft,
                            ),
                            shape = RoundedCornerShape(
                                topStart = ArdttRadius.Panel,
                                topEnd = ArdttRadius.Panel,
                            ),
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
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                Icon(
                                    Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    uptimeText,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = ArdttTerminalTextStyle,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = ArdttSpacing.Small),
                                )
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
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = logActions,
            )
            LogsBody(ArdttSpacing.None, ArdttSpacing.None)
        }
    } else {
        ArdttScrollChrome(
            header = {
                ArdttTabHeader(
                    title = "Журнал событий",
                    subtitle = if (AppLog.isDetailedEnabled()) {
                        "Подробные события (админ)"
                    } else {
                        "Краткие события туннеля"
                    },
                    onBack = onBack,
                    actions = logActions,
                )
            },
        ) { chromeTop ->
            LogsBody(chromeTop, bottomReserve)
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

/** Strings of the Logs screen that are not derived from state. */
internal object LogsCopy {
    const val CLEAR = "Удалить"
    const val CLEAR_TITLE = "Очистить журнал?"
    const val EMPTY_TITLE = "Пока пусто"
    const val EMPTY_BODY = "Нажмите «Сеть» или «Подключить» — сюда пойдут probe / туннель / go_client."
    const val UPTIME_TICK_MS = 1_000L

    fun clearBody(count: Int): String =
        "Будет удалено записей: $count. Действие нельзя отменить; при необходимости сначала скопируйте или поделитесь журналом."
}

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

private fun formatUptimeRough(entries: List<AppLog.Entry>, nowMs: Long): String? {
    val start = entries.firstOrNull {
        it.message.contains("VpnService started", ignoreCase = true) ||
            it.message.contains("tunnel running", ignoreCase = true) ||
            (it.tag.equals("ConnMgr", ignoreCase = true) && it.message.contains("Connected", ignoreCase = true))
    }?.timeMs ?: return null
    val ms = (nowMs - start).coerceAtLeast(0L)
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
