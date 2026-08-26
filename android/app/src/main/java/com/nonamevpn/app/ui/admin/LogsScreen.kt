package com.nonamevpn.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.theme.NvpnColors
import java.text.SimpleDateFormat
import java.util.Locale

/** qWDTT-style event log: terminal list + copy/clear. */
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Лог событий",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Row {
                IconButton(
                    onClick = {
                        val text = AppLog.dumpText()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("nonameVPN logs", text))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { AppLog.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        AppSectionCard(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(20.dp),
            color = NvpnColors.terminalBg,
            shadowElevation = 4.dp,
        ) {
            if (entries.isEmpty()) {
                Text(
                    "Пока пусто. Нажмите «Сеть» или «Подключить» — сюда пойдут probe / VPN / go_client.",
                    modifier = Modifier.padding(16.dp),
                    color = NvpnColors.terminalText.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NvpnColors.terminalBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { it.id }) { e ->
                        val color = when (e.level) {
                            AppLog.Level.E -> NvpnColors.warning
                            AppLog.Level.W -> NvpnColors.warning
                            AppLog.Level.I -> NvpnColors.terminalText
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
