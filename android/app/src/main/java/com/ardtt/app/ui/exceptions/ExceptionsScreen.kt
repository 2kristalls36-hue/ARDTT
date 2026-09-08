package com.ardtt.app.ui.exceptions

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.core.ExceptionAppVisibility
import com.ardtt.app.core.HostExclusion
import com.ardtt.app.core.appIconDecodeSize
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttSwitchRow
import com.ardtt.app.ui.components.feedback.ArdttEmptyState
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttPullRefresh
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.sectionCardContourBorder
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.backdropSegmentInactiveContainer
import com.ardtt.app.ui.theme.backdropSegmentInactiveContent
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExceptionsPane { Apps, Sites }

private val CardShape = ArdttShapes.Panel
private val AppCardShape = ArdttShapes.Row
/** Keep [AppsLoadingAnimation] stubs in lockstep with [AppExceptionRow]. */
private val AppRowHorizontalPadding = ArdttSpacing.Medium
private val AppRowVerticalPadding = 3.dp
private val AppRowContentPadding = PaddingValues(
    start = ArdttSpacing.Medium,
    end = ArdttSpacing.Small,
    top = ArdttSpacing.TinyPlus,
    bottom = ArdttSpacing.TinyPlus,
)
private val AppRowIconSize = 36.dp
private val AppRowIconCorner = ArdttShapes.Badge
private val AppRowIconGap = ArdttSpacing.SmallPlus
private val AppRowShadow = 1.dp
private const val AppRowTitleBarWidth = 0.62f
private const val AppRowSubtitleBarWidth = 0.86f

@Stable
data class ExceptionAppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val hideByDefault: Boolean,
)

object ExceptionAppCache {
    @Volatile var cachedList: List<ExceptionAppItem>? = null
}

private fun httpsHandlerPackages(pm: PackageManager): Set<String> {
    val https = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browser = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    fun query(intent: Intent): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
    }
    return (query(https) + query(browser))
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
}

private suspend fun loadInstalledExceptionApps(
    context: android.content.Context,
): List<ExceptionAppItem> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }
    val httpsHandlers = httpsHandlerPackages(pm)
    val list = installed.mapNotNull { app ->
        if (app.packageName == context.packageName) return@mapNotNull null
        if (app.packageName.contains("vkontakte") || app.packageName.contains("vk.calls")) {
            return@mapNotNull null
        }
        val systemPkg = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val hasLauncher = pm.getLaunchIntentForPackage(app.packageName) != null
        val userFacing = ExceptionAppVisibility.isUserFacing(
            packageName = app.packageName,
            hasLauncher = hasLauncher,
            httpsHandlerPackages = httpsHandlers,
        )
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
            hideByDefault = ExceptionAppVisibility.hideByDefault(systemPkg, userFacing),
        )
    }
    list.sortedWith(
        compareBy({ it.name.lowercase(Locale.getDefault()) }, { it.packageName }),
    )
}

/**
 * Вкладка обхода (как qWDTT ExceptionsTab): приложения слева, сайты справа.
 * Отмеченные приложения поднимаются вверх; иначе алфавит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsScreen(
    settings: AppSettingsRepository,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val hasBrowserHandlers = remember {
        runCatching { httpsHandlerPackages(context.packageManager).isNotEmpty() }.getOrDefault(false)
    }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val segmentInactiveContainer = backdropSegmentInactiveContainer()
    val segmentInactiveContent = backdropSegmentInactiveContent()

    var pane by rememberSaveable { mutableStateOf(ExceptionsPane.Apps) }

    val selectedPackages by settings.excludedAppsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val siteRules by settings.excludedHostsFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val isWhitelist by settings.appsWhitelistModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val sitesSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val sitesEnabled = sitesSupported && hasBrowserHandlers

    var appsList by remember { mutableStateOf(ExceptionAppCache.cachedList ?: emptyList()) }
    var isLoading by remember { mutableStateOf(ExceptionAppCache.cachedList == null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var includeSubdomains by rememberSaveable { mutableStateOf(true) }

    val orderedSites = remember(siteRules) { siteRules.sortedBy { it.lowercase(Locale.getDefault()) } }
    var newRule by remember { mutableStateOf("") }
    val visibleSites = remember(orderedSites, newRule) {
        val query = newRule.trim()
        if (query.isEmpty()) orderedSites
        else orderedSites.filter { it.contains(query, ignoreCase = true) }
    }
    var hint by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun applyTransport(reason: String) {
        ConnectionManager.getOrNull()?.requestTransportRestart(reason, rebuildTun = true)
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
        val wildcardRule = if (includeSubdomains && HostExclusion.parseLiteral(rule) == null) {
            "*.$rule"
        } else {
            rule
        }
        if (orderedSites.any { it.equals(wildcardRule, ignoreCase = true) }) {
            hint = "Уже в списке"
            newRule = ""
            return
        }
        newRule = ""
        persistSites(orderedSites + wildcardRule)
    }

    LaunchedEffect(Unit) {
        if (ExceptionAppCache.cachedList != null) {
            appsList = ExceptionAppCache.cachedList!!
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        appsList = loadInstalledExceptionApps(context)
        ExceptionAppCache.cachedList = appsList
        isLoading = false
    }
    LaunchedEffect(sitesEnabled, pane) {
        if (!sitesEnabled && pane == ExceptionsPane.Sites) {
            pane = ExceptionsPane.Apps
        }
    }

    val pull = rememberPullRefresh {
        val list = loadInstalledExceptionApps(context)
        ExceptionAppCache.cachedList = list
        appsList = list
    }

    // Выбранные вверх, внутри групп — алфавит (как qWDTT).
    val filteredApps = remember(appsList, showSystemApps, searchQuery, selectedPackages) {
        val base = if (showSystemApps) {
            appsList
        } else {
            appsList.filter { !it.hideByDefault || it.packageName in selectedPackages }
        }
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
        ArdttScrollChrome(
            header = {
                ArdttTabHeader(
                    title = "Исключения",
                    subtitle = "Приложения и сайты вне туннеля",
                    onBack = onBack,
                )
            },
        ) { topPad ->
        ArdttPullRefresh(
            refreshing = pull.refreshing,
            onRefresh = pull.onRefresh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ArdttSpacing.Large)
                    .padding(top = topPad),
            ) {

                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = ArdttSpacing.SmallPlus)
                            .height(ArdttSize.Contour),
                        color = colors.primary,
                        trackColor = colors.surfaceVariant,
                    )
                }

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = ArdttSpacing.MediumPlus),
                ) {
                    SegmentedButton(
                        selected = pane == ExceptionsPane.Apps,
                        onClick = { pane = ExceptionsPane.Apps },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.secondaryContainer,
                            activeContentColor = colors.onSecondaryContainer,
                            inactiveContainerColor = segmentInactiveContainer,
                            inactiveContentColor = segmentInactiveContent,
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
                        onClick = {
                            if (sitesEnabled) pane = ExceptionsPane.Sites
                        },
                        enabled = sitesEnabled,
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.secondaryContainer,
                            activeContentColor = colors.onSecondaryContainer,
                            inactiveContainerColor = segmentInactiveContainer,
                            inactiveContentColor = segmentInactiveContent,
                        ),
                        border = SegmentedButtonDefaults.borderStroke(colors.outlineVariant.copy(alpha = 0.7f)),
                    ) {
                        Text(
                            when {
                                !sitesSupported -> "Сайты (Android 13+)"
                                !hasBrowserHandlers -> "Сайты (нужен браузер)"
                                else -> "Правила ${orderedSites.size}"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                ArdttSectionCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(ArdttSpacing.None),
                    verticalArrangement = Arrangement.Top,
                    shape = CardShape,
                    fillHeight = true,
                ) {
            when (pane) {
                ExceptionsPane.Apps -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = ArdttSpacing.LargePlus, end = ArdttSpacing.LargePlus, top = ArdttSpacing.Large),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = ArdttSpacing.Medium)) {
                                Text(
                                    "Режим",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (isWhitelist) {
                                        if (admin) {
                                            "БС: только выбранные через туннель"
                                        } else {
                                            "Только выбранные приложения идут через туннель"
                                        }
                                    } else {
                                        if (admin) {
                                            "ЧС: выбранные мимо туннеля"
                                        } else {
                                            "Выбранные приложения работают вне туннеля"
                                        }
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

                        ArdttSwitchRow(
                            title = "Системные приложения",
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.padding(
                                horizontal = ArdttSpacing.LargePlus,
                                vertical = ArdttSpacing.Small,
                            ),
                        )

                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = ArdttAlpha.Divider))

                        if (isLoading) {
                            AppsLoadingAnimation(modifier = Modifier.fillMaxSize())
                        } else if (filteredApps.isEmpty()) {
                            val kind = ExceptionsCatalog.emptyKind(
                                total = appsList.size,
                                visible = 0,
                                query = searchQuery,
                            )
                            ArdttEmptyState(
                                title = if (kind == ExceptionsEmptyKind.NoMatches) {
                                    "Нет совпадений"
                                } else {
                                    "Нет приложений"
                                },
                                description = if (ExceptionsCatalog.offersClearSearch(kind)) {
                                    "Сбросьте поиск, чтобы снова увидеть список."
                                } else {
                                    null
                                },
                                modifier = Modifier.fillMaxSize(),
                                action = if (ExceptionsCatalog.offersClearSearch(kind)) {
                                    {
                                        ArdttButton(
                                            text = "Очистить поиск",
                                            onClick = { searchQuery = "" },
                                            variant = ArdttButtonVariant.Outlined,
                                            size = ArdttButtonSize.Compact,
                                            fillMaxWidth = false,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        } else {
                            LazyColumn(
                                state = rememberLazyListState(),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = ArdttSpacing.Small,
                                    bottom = ArdttBottomChrome.scrollContentPadding(
                                        extra = ArdttSpacing.Small,
                                    ),
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
                        if (!sitesEnabled) {
                            ArdttEmptyState(
                                title = if (!sitesSupported) {
                                    "Нужен Android 13"
                                } else {
                                    "Нужен браузер"
                                },
                                description = if (!sitesSupported) {
                                    "Раздел «Сайты» доступен только на Android 13 и выше."
                                } else {
                                    "Раздел «Сайты» работает только через браузеры. Установите браузер по умолчанию."
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            return@Column
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = ArdttSpacing.LargePlus, end = ArdttSpacing.Small, top = ArdttSpacing.MediumPlus, bottom = ArdttSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Правила: ${orderedSites.size}",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.2.sp,
                                ),
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (orderedSites.isNotEmpty()) {
                                ArdttButton(
                                    text = "Очистить",
                                    onClick = { showClearConfirm = true },
                                    enabled = !busy,
                                    variant = ArdttButtonVariant.Text,
                                    size = ArdttButtonSize.Compact,
                                    fillMaxWidth = false,
                                    contentColor = colors.error,
                                )
                            }
                        }
                        hint?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = ArdttSpacing.LargePlus, vertical = ArdttSpacing.TinyPlus),
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(top = ArdttSpacing.Tiny),
                            color = colors.outlineVariant.copy(alpha = ArdttAlpha.Divider),
                        )

                        if (orderedSites.isEmpty()) {
                            ArdttEmptyState(
                                title = "Список пуст",
                                description = "Добавьте домен или IP",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (visibleSites.isEmpty()) {
                            ArdttEmptyState(
                                title = "Нет совпадений",
                                description = "Сбросьте поиск, чтобы снова увидеть правила.",
                                modifier = Modifier.fillMaxSize(),
                                action = {
                                    ArdttButton(
                                        text = "Очистить поиск",
                                        onClick = { newRule = "" },
                                        variant = ArdttButtonVariant.Outlined,
                                        size = ArdttButtonSize.Compact,
                                        fillMaxWidth = false,
                                    )
                                },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = ArdttSpacing.Small,
                                    bottom = ArdttBottomChrome.scrollContentPadding(
                                        extra = ArdttSpacing.Small,
                                    ),
                                ),
                            ) {
                                items(visibleSites, key = { it }) { rule ->
                                    BypassRuleRow(
                                        rule = rule,
                                        enabled = !busy,
                                        onRemove = {
                                            persistSites(orderedSites.filterNot { it == rule })
                                        },
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = ArdttSpacing.Large),
                                        color = colors.outlineVariant.copy(alpha = ArdttAlpha.Outline),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        val chromePad = ArdttBottomChrome.stickyBottomPadding()
        val imePad = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val keyboardVisible = imePad > ArdttSpacing.Medium
        val floatingBarModifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .zIndex(2f)
            .padding(horizontal = ArdttSpacing.Large)
            .padding(bottom = maxOf(chromePad, imePad + ArdttSpacing.Small))
        if (pane == ExceptionsPane.Apps) {
            BypassSearchBar(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                keyboardVisible = keyboardVisible,
                modifier = floatingBarModifier,
            )
        } else {
            Column(
                modifier = floatingBarModifier,
                verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
            ) {
                if (sitesEnabled && keyboardVisible) {
                    Surface(
                        shape = ArdttShapes.Chip,
                        color = if (keyboardVisible) colors.surface else ArdttFloatingShell.shellColor(),
                        border = if (keyboardVisible) null else ArdttFloatingShell.shellBorder(),
                        shadowElevation = ArdttFloatingShell.shadowElevation,
                        tonalElevation = ArdttElevation.None,
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        FilterChip(
                            selected = includeSubdomains,
                            onClick = { includeSubdomains = !includeSubdomains },
                            label = {
                                Text(if (includeSubdomains) "С поддоменами" else "Точный домен")
                            },
                            modifier = Modifier.padding(horizontal = ArdttSpacing.Small, vertical = ArdttSpacing.TinyPlus),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                ) {
                    BypassSearchBar(
                        value = newRule,
                        onValueChange = { newRule = it.filter { c -> c != '\n' && c != '\r' } },
                        keyboardVisible = keyboardVisible,
                        placeholder = "домен / *.домен / IP/CIDR",
                        imeAction = ImeAction.Done,
                        onImeAction = { if (!busy && newRule.isNotBlank()) addSite() },
                        modifier = Modifier.weight(1f),
                    )
                    ArdttButton(
                        text = "Добавить",
                        onClick = { addSite() },
                        enabled = !busy && newRule.isNotBlank(),
                        busy = busy,
                        variant = ArdttButtonVariant.Primary,
                        fillMaxWidth = false,
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        ArdttDialog(
            title = "Очистить сайты?",
            onDismissRequest = { showClearConfirm = false },
            confirmAction = ArdttDialogAction(
                text = "Удалить",
                onClick = {
                    showClearConfirm = false
                    persistSites(emptyList())
                },
                destructive = true,
            ),
            dismissAction = ArdttDialogAction("Отмена", { showClearConfirm = false }),
        ) {
            Text(
                "Будут удалены все ${orderedSites.size} правил.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
    }
}

@Composable
private fun AppsLoadingAnimation(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val base = colors.surfaceVariant.copy(alpha = ArdttAlpha.Muted)
    val highlight = colors.surface.copy(alpha = 0.95f)
    val shimmer = rememberInfiniteTransition(label = "apps_loading")
    val shift by shimmer.animateFloat(
        initialValue = -280f,
        targetValue = 920f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
        ),
        label = "apps_loading_shift",
    )
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val subtitleStyle = MaterialTheme.typography.labelSmall

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = ArdttSpacing.Small,
            bottom = ArdttBottomChrome.scrollContentPadding(extra = ArdttSpacing.Small),
        ),
    ) {
        items(count = 9) {
            val shimmerBrush = Brush.horizontalGradient(
                colors = listOf(base, highlight, base),
                startX = shift,
                endX = shift + 380f,
            )
            AppExceptionRowFrame(
                icon = {
                    Box(
                        modifier = Modifier
                            .size(AppRowIconSize)
                            .clip(AppRowIconCorner)
                            .background(shimmerBrush),
                    )
                },
                title = {
                    AppRowShimmerBar(
                        brush = shimmerBrush,
                        widthFraction = AppRowTitleBarWidth,
                        textStyle = titleStyle,
                    )
                },
                subtitle = {
                    AppRowShimmerBar(
                        brush = shimmerBrush,
                        widthFraction = AppRowSubtitleBarWidth,
                        textStyle = subtitleStyle,
                    )
                },
                trailing = {
                    Box(modifier = Modifier.clearAndSetSemantics {}) {
                        Switch(checked = false, onCheckedChange = null)
                    }
                },
            )
        }
    }
}

@Composable
private fun AppRowShimmerBar(
    brush: Brush,
    widthFraction: Float,
    textStyle: TextStyle,
) {
    val density = LocalDensity.current
    val slotHeight = with(density) { textStyle.lineHeight.toDp() }
    val barHeight = with(density) { textStyle.fontSize.toDp() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(slotHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(barHeight)
                .clip(ArdttShapes.Pill)
                .background(brush),
        )
    }
}

@Composable
private fun BypassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val elevation = ArdttFloatingShell.shadowElevation
    val fill = if (keyboardVisible) {
        colors.surface
    } else {
        ArdttFloatingShell.shellColor()
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(ArdttBottomChrome.ButtonHeight)
            .semantics { contentDescription = "Поиск" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                focusRequester.requestFocus()
                keyboard?.show()
            },
        shape = ArdttShapes.Control,
        color = fill,
        border = ArdttFloatingShell.shellBorder(),
        shadowElevation = elevation,
        tonalElevation = ArdttElevation.None,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ArdttSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(ArdttSize.Icon),
                tint = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(ArdttSpacing.Medium))
            val fieldStyle = MaterialTheme.typography.titleMedium.copy(
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                background = Color.Transparent,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onSearch = { onImeAction?.invoke() ?: keyboard?.hide() },
                    onDone = { onImeAction?.invoke() ?: keyboard?.hide() },
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(Color.Transparent)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.background(Color.Transparent),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = fieldStyle.copy(
                                    color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                                    background = Color.Transparent,
                                ),
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                ArdttButton(
                    onClick = { onValueChange("") },
                    variant = ArdttButtonVariant.Icon,
                    icon = Icons.Filled.Close,
                    contentDescription = "Очистить",
                    contentColor = colors.onSurfaceVariant,
                )
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
            .heightIn(min = ArdttSize.IconHero)
            .padding(start = ArdttSpacing.LargePlus, end = ArdttSpacing.Tiny),
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
        ArdttButton(
            onClick = onRemove,
            enabled = enabled,
            variant = ArdttButtonVariant.Icon,
            icon = Icons.Filled.Close,
            contentDescription = "Удалить",
            contentColor = colors.onSurfaceVariant.copy(alpha = ArdttAlpha.Muted),
        )
    }
}

@Composable
private fun AppExceptionRow(
    app: ExceptionAppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AppExceptionRowFrame(
        onClick = onClick,
        icon = {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(AppRowIconSize)
                        .clip(AppRowIconCorner),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(AppRowIconSize)
                        .background(colors.surfaceVariant, AppRowIconCorner),
                )
            }
        },
        title = {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Switch(
                checked = isSelected,
                onCheckedChange = { onClick() },
            )
        },
    )
}

@Composable
private fun AppExceptionRowFrame(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val modifier = Modifier
        .fillMaxWidth()
        .padding(
            horizontal = AppRowHorizontalPadding,
            vertical = AppRowVerticalPadding,
        )
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppRowContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(modifier = Modifier.width(AppRowIconGap))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = ArdttSpacing.Small),
            ) {
                title()
                subtitle()
            }
            trailing()
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = AppCardShape,
            color = colors.surface,
            contentColor = colors.onSurface,
            shadowElevation = AppRowShadow,
            tonalElevation = ArdttElevation.None,
            border = sectionCardContourBorder(),
            content = { content() },
        )
    } else {
        Surface(
            modifier = modifier,
            shape = AppCardShape,
            color = colors.surface,
            contentColor = colors.onSurface,
            shadowElevation = AppRowShadow,
            tonalElevation = ArdttElevation.None,
            border = sectionCardContourBorder(),
            content = { content() },
        )
    }
}
