package com.ardtt.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.VpnLiveStats
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.terminalCardColor
import com.ardtt.app.ui.components.surface.terminalCardElevation
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttRadius
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.isDarkSurface
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.shareText
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Event log: qWDTT-style terminal chrome + pinned stats. */
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val isDark = isDarkSurface()
    val terminalBg = terminalCardColor()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<AppLog.Level?>(null) }
    var follow by remember { mutableStateOf(true) }
    var lastSeenId by remember { mutableIntStateOf(0) }

    val visible = remember(entries, query, levelFilter) {
        LogsCatalog.visible(entries, query, levelFilter)
    }

    val sessionUp = ui.state == ConnState.Connected || ui.state == ConnState.PausedTrustedWifi
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionUp) {
        if (!sessionUp) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val pinnedStats = remember(ui.state, ui.activePath, ui.statusText, nowMs) {
        if (ui.state == ConnState.Connected || ui.state == ConnState.Connecting) {
            val rates = VpnLiveStats.formatRateLine(VpnLiveStats.downBps, VpnLiveStats.upBps)
            val path = when (ui.activePath?.name) {
                "Direct" -> "прямое"
                "Bypass" -> "обход"
                else -> ui.statusText.take(24)
            }
            "$rates · $path"
        } else {
            null
        }
    }
    val uptimeText = remember(sessionUp, nowMs, entries.firstOrNull()?.id) {
        if (!sessionUp) null else formatUptimeRough(entries, nowMs)
    }

    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow true
            last.index >= info.totalItemsCount - 1
        }.distinctUntilChanged().collect { end ->
            if (!end) follow = false
        }
    }
    LaunchedEffect(visible.lastOrNull()?.id, follow) {
        val lastId = visible.lastOrNull()?.id ?: return@LaunchedEffect
        if (LogsCatalog.shouldFollow(follow, atEnd) && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
            lastSeenId = lastId
        }
    }
    val unseen = LogsCatalog.unseenCount(lastSeenId, visible)

    fun dumpBody(): String = buildString {
        pinnedStats?.let { appendLine("[СТАТИСТИКА] $it") }
        append(visible.joinToString("\n") { it.displayLine(fmt) }.ifBlank { AppLog.dumpText() })
    }.trim()

    fun jumpToLatest() {
        follow = true
        scope.launch {
            if (visible.isNotEmpty()) {
                listState.scrollToItem(visible.lastIndex)
                lastSeenId = visible.last().id
            }
        }
    }

    ArdttScrollChrome(
        header = {
            ArdttTabHeader(
                title = "Журнал событий",
                subtitle = if (AppLog.isDetailedEnabled()) {
                    "Подробные события (админ)"
                } else {
                    "Краткие события туннеля"
                },
                actions = {
                    IconButton(onClick = { AppLog.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { copyToClipboard(context, dumpBody(), "ARDTT logs") },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { shareText(context, dumpBody(), "ARDTT logs", "Экспорт логов") },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
    ) { topPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPad)
                .padding(horizontal = ArdttSpacing.Large),
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
                        modifier = Modifier.padding(horizontal = ArdttSpacing.Medium, vertical = ArdttSpacing.SmallPlus),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Поиск") },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Delete, contentDescription = "Очистить поиск")
                        }
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ArdttSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = levelFilter == null, onClick = { levelFilter = null }, label = { Text("Все") })
                FilterChip(selected = levelFilter == AppLog.Level.I, onClick = { levelFilter = AppLog.Level.I }, label = { Text("I") })
                FilterChip(selected = levelFilter == AppLog.Level.W, onClick = { levelFilter = AppLog.Level.W }, label = { Text("W") })
                FilterChip(selected = levelFilter == AppLog.Level.E, onClick = { levelFilter = AppLog.Level.E }, label = { Text("E") })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
            ) {
                ArdttButton(
                    text = if (follow) "Автопрокрутка вкл" else "Автопрокрутка",
                    onClick = {
                        follow = !follow
                        if (follow) jumpToLatest()
                    },
                    variant = if (follow) ArdttButtonVariant.Tonal else ArdttButtonVariant.Outlined,
                    size = ArdttButtonSize.Compact,
                    fillMaxWidth = false,
                    modifier = Modifier.weight(1f),
                )
                ArdttButton(
                    text = if (unseen > 0) "К последним ($unseen)" else "К последним",
                    onClick = { jumpToLatest() },
                    variant = ArdttButtonVariant.Outlined,
                    size = ArdttButtonSize.Compact,
                    fillMaxWidth = false,
                    icon = Icons.Default.KeyboardArrowDown,
                    modifier = Modifier.weight(1f),
                )
            }

            ArdttSectionCard(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = ArdttSpacing.Small),
                contentPadding = PaddingValues(ArdttSpacing.None),
                shape = ArdttShapes.Panel,
                color = terminalBg,
                shadowElevation = terminalCardElevation(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (pinnedStats != null || uptimeText != null) {
                        Surface(
                            color = ArdttColors.TerminalBlue.copy(alpha = if (isDark) ArdttAlpha.Fill else 0.12f),
                            shape = RoundedCornerShape(
                                topStart = ArdttRadius.Panel,
                                topEnd = ArdttRadius.Panel,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ArdttSpacing.Medium, vertical = ArdttSpacing.SmallPlus),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                            ) {
                                if (pinnedStats != null) {
                                    Text(
                                        text = pinnedStats,
                                        color = ArdttColors.TerminalBlue,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                                }
                                if (uptimeText != null) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = ArdttColors.TerminalBlue,
                                    )
                                    Text(
                                        uptimeText,
                                        color = ArdttColors.TerminalBlue,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    if (visible.isEmpty()) {
                        Text(
                            if (entries.isEmpty()) {
                                "Пока пусто. Нажмите «Сеть» или «Подключить» — сюда пойдут probe / туннель / go_client."
                            } else {
                                "Нет записей по фильтру. Сбросьте поиск или уровень."
                            },
                            modifier = Modifier.padding(ArdttSpacing.Large),
                            color = ArdttColors.TerminalText.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (entries.isNotEmpty()) {
                            ArdttButton(
                                text = "Сбросить фильтр",
                                onClick = {
                                    query = ""
                                    levelFilter = null
                                },
                                variant = ArdttButtonVariant.Text,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = ArdttSpacing.Medium, vertical = ArdttSpacing.SmallPlus),
                            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
                        ) {
                            items(visible, key = { it.id }) { e ->
                                val color = when (e.level) {
                                    AppLog.Level.E -> ArdttColors.TerminalRed
                                    AppLog.Level.W -> ArdttColors.Warning
                                    AppLog.Level.I -> ArdttColors.TerminalGreen
                                }
                                Text(
                                    text = e.displayLine(fmt),
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
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
