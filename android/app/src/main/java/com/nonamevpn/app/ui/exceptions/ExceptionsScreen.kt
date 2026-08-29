package com.nonamevpn.app.ui.exceptions

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.nonamevpn.app.ui.components.EdgeFeedTopInset
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.appIconDecodeSize
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.settings.AppSettingsRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExceptionsPane { Apps, Sites }

private val CardShape = RoundedCornerShape(24.dp)
private val ControlShape = RoundedCornerShape(14.dp)
private val AppCardShape = RoundedCornerShape(14.dp)

@Stable
data class ExceptionAppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
)

object ExceptionAppCache {
    @Volatile var cachedList: List<ExceptionAppItem>? = null
}

/**
 * Вкладка обхода (как qWDTT ExceptionsTab): приложения слева, сайты справа.
 * Отмеченные приложения поднимаются вверх; иначе алфавит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsScreen(settings: AppSettingsRepository) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    var pane by rememberSaveable { mutableStateOf(ExceptionsPane.Apps) }

    val selectedPackages by settings.excludedAppsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val siteRules by settings.excludedHostsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val isWhitelist by settings.appsWhitelistModeFlow.collectAsStateWithLifecycle(initialValue = false)

    var appsList by remember { mutableStateOf(ExceptionAppCache.cachedList ?: emptyList()) }
    var isLoading by remember { mutableStateOf(ExceptionAppCache.cachedList == null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val orderedSites = remember(siteRules) { siteRules.sortedBy { it.lowercase(Locale.getDefault()) } }
    var newRule by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun applyTransport(reason: String) {
        ConnectionManager.getOrNull()?.requestTransportRestart(reason)
    }

    fun persistSites(rules: List<String>, note: String? = null) {
        scope.launch {
            busy = true
            hint = null
            try {
                val cleaned = rules
                    .map { AppSettingsRepository.normalizeHost(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                settings.setExcludedHosts(cleaned.toSet())
                hint = note ?: when {
                    cleaned.isEmpty() -> null
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                        "Сайты сохранятся; исключение IP нужно Android 13+"
                    else -> null
                }
                applyTransport("Обновлены исключения сайтов")
            } finally {
                busy = false
            }
        }
    }

    fun addSite() {
        val rule = AppSettingsRepository.normalizeHost(newRule)
        if (rule.isBlank() || busy) return
        if (orderedSites.any { it.equals(rule, ignoreCase = true) }) {
            hint = "Уже в списке"
            newRule = ""
            return
        }
        newRule = ""
        persistSites(orderedSites + rule)
    }

    LaunchedEffect(Unit) {
        if (ExceptionAppCache.cachedList != null) {
            appsList = ExceptionAppCache.cachedList!!
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        appsList = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
            val list = installed.mapNotNull { app ->
                if (app.packageName == context.packageName) return@mapNotNull null
                if (app.packageName.contains("vkontakte") || app.packageName.contains("vk.calls")) {
                    return@mapNotNull null
                }
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val drawable = app.loadIcon(pm)
                val iconBitmap = if (drawable != null) {
                    val (w, h) = appIconDecodeSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
                    runCatching { drawable.toBitmap(w, h).asImageBitmap() }.getOrNull()
                } else {
                    null
                }
                ExceptionAppItem(
                    name = app.loadLabel(pm).toString(),
                    packageName = app.packageName,
                    icon = iconBitmap,
                    isSystem = isSystem,
                )
            }
            list.sortedWith(
                compareBy({ it.name.lowercase(Locale.getDefault()) }, { it.packageName }),
            )
        }
        ExceptionAppCache.cachedList = appsList
        isLoading = false
    }

    // Выбранные вверх, внутри групп — алфавит (как qWDTT).
    val filteredApps = remember(appsList, showSystemApps, searchQuery, selectedPackages) {
        val base = if (showSystemApps) appsList else appsList.filter { !it.isSystem }
        val matching = if (searchQuery.isBlank()) {
            base
        } else {
            base.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        matching.sortedWith(
            compareByDescending<ExceptionAppItem> { it.packageName in selectedPackages }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
                .thenBy { it.packageName },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = NvpnBottomChrome.navigationReserve()),
    ) {
        EdgeFeedTopInset()
        AppTabPageHeader(
            title = "Исключения",
            subtitle = "Приложения и сайты вне туннеля",
        )

        if (busy) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .height(2.dp),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        }

        // Приложения слева, сайты справа (в отличие от qWDTT).
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
        ) {
            SegmentedButton(
                selected = pane == ExceptionsPane.Apps,
                onClick = { pane = ExceptionsPane.Apps },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.secondaryContainer,
                    activeContentColor = colors.onSecondaryContainer,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = colors.onSurfaceVariant,
                ),
                border = SegmentedButtonDefaults.borderStroke(colors.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Text(
                    "Приложения ${selectedPackages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            SegmentedButton(
                selected = pane == ExceptionsPane.Sites,
                onClick = { pane = ExceptionsPane.Sites },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.secondaryContainer,
                    activeContentColor = colors.onSecondaryContainer,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = colors.onSurfaceVariant,
                ),
                border = SegmentedButtonDefaults.borderStroke(colors.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Text(
                    "Сайты ${orderedSites.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 4.dp),
            shape = CardShape,
            color = colors.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            when (pane) {
                ExceptionsPane.Apps -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    "Режим",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (isWhitelist) {
                                        "БС: только выбранные через VPN"
                                    } else {
                                        "ЧС: выбранные мимо VPN"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = !isWhitelist,
                                    onClick = {
                                        if (isWhitelist) {
                                            scope.launch {
                                                settings.setAppsWhitelistMode(false)
                                                applyTransport("Режим исключений: ЧС")
                                            }
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = colors.primary,
                                        activeContentColor = colors.onPrimary,
                                        inactiveContainerColor = Color.Transparent,
                                        inactiveContentColor = colors.onSurfaceVariant,
                                    ),
                                ) {
                                    Text("ЧС", style = MaterialTheme.typography.labelMedium)
                                }
                                SegmentedButton(
                                    selected = isWhitelist,
                                    onClick = {
                                        if (!isWhitelist) {
                                            scope.launch {
                                                settings.setAppsWhitelistMode(true)
                                                applyTransport("Режим исключений: БС")
                                            }
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = colors.primary,
                                        activeContentColor = colors.onPrimary,
                                        inactiveContainerColor = Color.Transparent,
                                        inactiveContentColor = colors.onSurfaceVariant,
                                    ),
                                ) {
                                    Text("БС", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Системные приложения",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = showSystemApps,
                                onCheckedChange = { showSystemApps = it },
                            )
                        }

                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))

                        if (isLoading) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            LazyColumn(
                                state = rememberLazyListState(),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = NvpnBottomChrome.ButtonHeight +
                                        NvpnBottomChrome.StickyGap + 16.dp,
                                ),
                            ) {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    val isSelected = app.packageName in selectedPackages
                                    AppExceptionRow(
                                        app = app,
                                        isSelected = isSelected,
                                        onClick = {
                                            scope.launch {
                                                val selected = settings.excludedAppsSnapshot()
                                                if (app.packageName in selected) {
                                                    settings.removeExcludedApp(app.packageName)
                                                } else {
                                                    settings.addExcludedApp(app.packageName)
                                                }
                                                applyTransport("Обновлены исключения приложений")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                ExceptionsPane.Sites -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (orderedSites.isEmpty()) "Нет сайтов" else "${orderedSites.size}",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.2.sp,
                                ),
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (orderedSites.isNotEmpty()) {
                                TextButton(
                                    onClick = { showClearConfirm = true },
                                    enabled = !busy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Очистить",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.error,
                                    )
                                }
                            }
                        }

                        BypassInputBar(
                            value = newRule,
                            onValueChange = { newRule = it.filter { c -> c != '\n' && c != '\r' } },
                            enabled = !busy,
                            canAdd = !busy && newRule.isNotBlank(),
                            busy = busy,
                            onAdd = { addSite() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        )

                        hint?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                            )
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Text(
                                "Исключение сайтов по IP требует Android 13+",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(top = 10.dp),
                            color = colors.outlineVariant.copy(alpha = 0.35f),
                        )

                        if (orderedSites.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Список пуст\nДобавьте домен или IP",
                                    textAlign = TextAlign.Center,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                items(orderedSites, key = { it }) { rule ->
                                    BypassRuleRow(
                                        rule = rule,
                                        enabled = !busy,
                                        onRemove = {
                                            persistSites(orderedSites.filterNot { it == rule })
                                        },
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = colors.outlineVariant.copy(alpha = 0.22f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pane == ExceptionsPane.Apps) {
        val chromePad = NvpnBottomChrome.stickyBottomPadding()
        val imePad = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        BypassSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            keyboardVisible = imePad > 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .padding(horizontal = 16.dp)
                .padding(bottom = maxOf(chromePad, imePad + 8.dp)),
        )
    }
    }

    if (showClearConfirm) {
        NvpnDialog(
            title = "Очистить сайты?",
            onDismissRequest = { showClearConfirm = false },
            confirmAction = NvpnDialogAction(
                text = "Удалить",
                onClick = {
                    showClearConfirm = false
                    persistSites(emptyList())
                },
                destructive = true,
            ),
            dismissAction = NvpnDialogAction("Отмена", { showClearConfirm = false }),
        ) {
            Text(
                "Будут удалены все ${orderedSites.size} правил.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BypassInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    canAdd: Boolean,
    busy: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(52.dp),
        shape = ControlShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.onSurface,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "домен или IP…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.45f)),
            )
            IconButton(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier.size(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Добавить",
                        tint = if (canAdd) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BypassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (keyboardVisible) 1f else 0.70f,
        label = "bypass_search_alpha",
    )
    val elevation by animateDpAsState(
        targetValue = if (keyboardVisible) 6.dp else 3.dp,
        label = "bypass_search_elev",
    )
    Surface(
        modifier = modifier.height(NvpnBottomChrome.ButtonHeight),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface.copy(alpha = surfaceAlpha),
        shadowElevation = elevation,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "Поиск",
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    },
                tint = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboard?.hide() },
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "Поиск",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Очистить",
                        modifier = Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BypassRuleRow(
    rule: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rule,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Удалить",
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun AppExceptionRow(
    app: ExceptionAppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = AppCardShape,
        color = colors.surface,
        contentColor = colors.onSurface,
        shadowElevation = 1.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(8.dp)),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = isSelected,
                onCheckedChange = { onClick() },
            )
        }
    }
}
