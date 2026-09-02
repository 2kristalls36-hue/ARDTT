package com.nonamevpn.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.VpnLiveStats
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.TabFeedHeader
import com.nonamevpn.app.ui.theme.NvpnColors
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Event log: qWDTT-style terminal chrome + pinned stats. */
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val isDark = isSystemInDarkTheme()
    val terminalBg = if (isDark) NvpnColors.terminalBgDark else NvpnColors.terminalBg

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
    val uptimeText = remember(sessionUp, nowMs) {
        // Approx uptime from first Connected log is ideal; use live clock tick only when up.
        if (!sessionUp) null else formatUptimeRough(entries, nowMs)
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    fun dumpBody(): String = buildString {
        pinnedStats?.let { appendLine("[СТАТИСТИКА] $it") }
        append(AppLog.dumpText())
    }.trim()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = NvpnBottomChrome.navigationReserve() + 12.dp),
    ) {
        TabFeedHeader(
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
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ARDTT logs", dumpBody()))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "ARDTT logs")
                            putExtra(Intent.EXTRA_TEXT, dumpBody())
                        }
                        context.startActivity(Intent.createChooser(share, "Экспорт логов"))
                    },
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = MaterialTheme.colorScheme.primary)
                }
            },
        )

        ui.lastError?.takeIf { it.isNotBlank() }?.let { fatal ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    fatal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        AppSectionCard(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(24.dp),
            color = terminalBg.copy(alpha = if (isDark) 0.90f else 0.93f),
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (pinnedStats != null || uptimeText != null) {
                    Surface(
                        color = NvpnColors.terminalBlue.copy(alpha = if (isDark) 0.18f else 0.12f),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (pinnedStats != null) {
                                Text(
                                    text = pinnedStats,
                                    color = NvpnColors.terminalBlue,
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
                                    tint = NvpnColors.terminalBlue,
                                    modifier = Modifier.padding(end = 2.dp),
                                )
                                Text(
                                    uptimeText,
                                    color = NvpnColors.terminalBlue,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                if (entries.isEmpty()) {
                    Text(
                        "Пока пусто. Нажмите «Сеть» или «Подключить» — сюда пойдут probe / туннель / go_client.",
                        modifier = Modifier.padding(16.dp),
                        color = NvpnColors.terminalText.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(entries, key = { it.id }) { e ->
                            val color = when (e.level) {
                                AppLog.Level.E -> NvpnColors.terminalRed
                                AppLog.Level.W -> NvpnColors.warning
                                AppLog.Level.I -> NvpnColors.terminalGreen
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
