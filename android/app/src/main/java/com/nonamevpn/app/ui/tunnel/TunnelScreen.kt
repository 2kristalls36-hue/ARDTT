package com.nonamevpn.app.ui.tunnel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.BuildConfig
import com.nonamevpn.app.bypass.VkCallHashGenerator
import com.nonamevpn.app.bypass.VkLoginActivity
import com.nonamevpn.app.bypass.VkSession
import com.nonamevpn.app.bypass.VkUrl
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnPathMode
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.core.EgressIpProbe
import com.nonamevpn.app.core.ProbeResult
import com.nonamevpn.app.core.VpnPath
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedTopInset
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import com.nonamevpn.app.ui.components.StickyPrimaryButton
import com.nonamevpn.app.ui.theme.NvpnColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun TunnelScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    onRequestConnect: () -> Unit,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showHash by remember { mutableStateOf(false) }
    var callBusy by remember { mutableStateOf(false) }
    var callMessage by remember { mutableStateOf<String?>(null) }
    var vkLoggedIn by remember { mutableStateOf(VkSession.hasSessionCookie()) }
    var publicIp by remember { mutableStateOf(EgressIpProbe.current()) }
    var ipError by remember { mutableStateOf(EgressIpProbe.lastError) }

    LaunchedEffect(profile) {
        conn.updateProfile(profile)
        if (profile != null) {
            settings.setProfileName(profile!!.name)
        } else {
            settings.setProfileName("")
        }
        conn.startInitialProbe()
    }

    val hideIp by settings.hideIpEnabled.collectAsStateWithLifecycle(initialValue = false)
    val pathMode by settings.pathModeName.collectAsStateWithLifecycle(initialValue = "auto")
    val admin by settings.isAdminUnlocked.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(profile?.deviceId) {
        if (profile == null) return@LaunchedEffect
        conn.setHideIp(hideIp)
    }

    val connecting = ui.state == ConnState.Connecting
    val pausedTrusted = ui.state == ConnState.PausedTrustedWifi
    val connected = ui.state == ConnState.Connected
    val sessionUp = connected || pausedTrusted
    val probing = ui.state == ConnState.Probing
    val disconnecting = ui.state == ConnState.Disconnecting
    val busy = probing || connecting || disconnecting
    val pathBusy = connecting || disconnecting

    LaunchedEffect(sessionUp, hideIp) {
        while (sessionUp) {
            publicIp = EgressIpProbe.current()
            ipError = EgressIpProbe.lastError
            delay(1_000)
        }
        publicIp = EgressIpProbe.current()
        ipError = EgressIpProbe.lastError
    }

    val buttonColor by animateColorAsState(
        targetValue = when {
            sessionUp -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "btn_color",
    )

    fun onVkAction() {
        scope.launch {
            if (!vkLoggedIn) {
                callBusy = true
                callMessage = "Открываем вход VK…"
                AppLog.i("VK", "Login button pressed")
                val activityCtx = context.findActivity() ?: context
                val r = runCatching { VkLoginActivity.login(activityCtx) }
                    .getOrElse { Result.failure(it) }
                callBusy = false
                vkLoggedIn = VkSession.hasSessionCookie()
                callMessage = when {
                    r.isSuccess && vkLoggedIn -> "Вход выполнен — можно создать звонок"
                    r.isSuccess -> "Сессия не подтвердилась — попробуйте ещё раз"
                    else -> r.exceptionOrNull()?.message ?: "Вход отменён"
                }
                AppLog.i("VK", "Login result success=${r.isSuccess} cookie=$vkLoggedIn")
                return@launch
            }
            callBusy = true
            callMessage = "Создаём звонок…"
            val r = VkCallHashGenerator.generateOne(context)
            callBusy = false
            r.onSuccess { hash ->
                conn.saveCallHash(hash)
                callMessage = "Звонок создан, hash сохранён"
            }.onFailure { e ->
                callMessage = e.message ?: "Не удалось создать звонок"
                vkLoggedIn = VkSession.hasSessionCookie()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.scrollContentPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EdgeFeedTopInset()

            AppPageHeader(
                title = "ARDTT",
                subtitle = "Туннель и быстрые настройки",
            )

            if (!admin && profile != null) {
                val active = profile!!.subscriptionActive
                val expiresText = when {
                    profile!!.expiresAt <= 0L -> "без срока"
                    else -> {
                        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru"))
                        fmt.format(java.util.Date(profile!!.expiresAt * 1000L))
                    }
                }
                AppSectionCard(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        2.dp,
                        if (active) NvpnColors.connected else MaterialTheme.colorScheme.error,
                    ),
                    shadowElevation = 0.dp,
                ) {
                    Text(
                        if (active) "Подписка активна" else "Подписка неактивна",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) NvpnColors.connected else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Действует до $expiresText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = when {
                    profile == null -> "Профиль не выбран"
                    profile!!.name.isBlank() -> "Профиль выбран"
                    else -> "Профиль: ${profile!!.name}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            // ═══ Быстрые настройки ═══
            AppSectionCard(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Быстрые настройки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                QuickSettingRow(
                    title = "Путь",
                    subtitle = when (pathMode) {
                        "direct" -> "Только AmneziaWG (AWG)"
                        "bypass" -> "Только обход RAW через звонок"
                        else -> "Авто: AWG, резерв обход"
                    },
                ) {
                    ChoiceChipButton(
                        label = "Авто",
                        selected = pathMode == "auto",
                        enabled = !pathBusy,
                        onClick = {
                            scope.launch {
                                settings.setPathMode("auto")
                                conn.setPathMode(ConnPathMode.Auto)
                                AppLog.i("PathMode", "auto")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Прямое",
                        selected = pathMode == "direct",
                        enabled = !pathBusy,
                        selectedContainer = NvpnColors.pathDirect,
                        onClick = {
                            scope.launch {
                                settings.setPathMode("direct")
                                conn.setPathMode(ConnPathMode.Direct)
                                AppLog.i("PathMode", "direct")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Обход",
                        selected = pathMode == "bypass",
                        enabled = !pathBusy,
                        selectedContainer = NvpnColors.pathBypass,
                        onClick = {
                            scope.launch {
                                settings.setPathMode("bypass")
                                conn.setPathMode(ConnPathMode.Bypass)
                                AppLog.i("PathMode", "bypass")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                QuickSettingRow(
                    title = "Хэш звонка",
                    subtitle = when {
                        callMessage != null -> callMessage
                        ui.hasCallHash -> "Hash сохранён на этом телефоне"
                        vkLoggedIn -> "VK: вход выполнен — нажмите ещё раз, чтобы создать звонок"
                        else -> "Нужен для обхода (Path B)"
                    },
                ) {
                    ChoiceChipButton(
                        label = "Вход в ВК",
                        selected = vkLoggedIn,
                        enabled = profile != null && !callBusy,
                        onClick = { onVkAction() },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "Ручное",
                        selected = ui.hasCallHash && !vkLoggedIn,
                        enabled = profile != null && !sessionUp && !callBusy,
                        onClick = { showHash = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                QuickSettingRow(
                    title = "Скрыть IP",
                    subtitle = if (hideIp) {
                        "Выход через Cloudflare WARP"
                    } else {
                        "Выход напрямую (IP VPS)"
                    },
                ) {
                    ChoiceChipButton(
                        label = "На прямую",
                        selected = !hideIp,
                        enabled = !pathBusy,
                        onClick = {
                            scope.launch {
                                settings.setHideIp(false)
                                conn.setHideIp(false)
                                AppLog.i("HideIP", "disabled (direct)")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceChipButton(
                        label = "WARP",
                        selected = hideIp,
                        enabled = !pathBusy,
                        onClick = {
                            scope.launch {
                                settings.setHideIp(true)
                                conn.setHideIp(true)
                                AppLog.i("HideIP", "enabled (WARP)")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ═══ Статус сессии — структурированная панель ═══
            TunnelStatusPanel(
                statusText = ui.statusText.ifBlank { "—" },
                statusColor = when {
                    connected || pausedTrusted -> NvpnColors.connected
                    ui.state == ConnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                pathModeLabel = when (pathMode) {
                    "direct" -> "Прямое"
                    "bypass" -> "Обход"
                    else -> "Авто"
                },
                activePathLabel = when (ui.activePath) {
                    VpnPath.Direct -> "Прямое"
                    VpnPath.Bypass -> "Обход"
                    null -> null
                },
                publicIp = when {
                    !publicIp.isNullOrBlank() -> publicIp!!
                    !ipError.isNullOrBlank() && sessionUp -> "не удалось · нажмите"
                    sessionUp -> "…"
                    else -> "—"
                },
                ipFailed = sessionUp && publicIp.isNullOrBlank() && !ipError.isNullOrBlank(),
                onIpClick = if (sessionUp) {
                    { conn.requestEgressIpRefresh() }
                } else {
                    null
                },
                profileName = profile?.name?.takeIf { it.isNotBlank() },
                version = BuildConfig.VERSION_NAME,
                directEndpoint = profile?.direct?.endpoint,
                bypassPeer = profile?.bypass?.peer,
                provisionLine = profile?.let { p ->
                    p.provisionBaseUrl?.let { base -> "$base · host ${p.hostId}" }
                },
                probe = ui.probe,
                softInfo = ui.softInfo?.takeIf { it.isNotBlank() },
                errorText = ui.lastError?.takeIf { ui.state == ConnState.Error && it.isNotBlank() },
                showVkLogout = vkLoggedIn,
                vkLogoutEnabled = !callBusy,
                onVkLogout = {
                    VkSession.clear()
                    vkLoggedIn = false
                    callMessage = "Сессия VK сброшена"
                },
            )
        }

        // Sticky «Подключить» / «Отменить» (same button) above tab bar
        val cancelMode = connecting || probing
        StickyPrimaryButton(
            text = when {
                cancelMode -> "Отменить"
                sessionUp && pausedTrusted -> "Остановить (пауза Wi‑Fi)"
                sessionUp -> "Остановить"
                else -> "Подключить"
            },
            onClick = {
                when {
                    cancelMode || sessionUp -> conn.disconnect()
                    else -> onRequestConnect()
                }
            },
            enabled = cancelMode || (!busy && (sessionUp || ui.connectEnabled)),
            containerColor = when {
                cancelMode || sessionUp -> MaterialTheme.colorScheme.error
                else -> buttonColor
            },
            icon = when {
                cancelMode -> Icons.Default.Stop
                sessionUp -> Icons.Default.Stop
                else -> Icons.Default.PowerSettingsNew
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(2f)
                .padding(horizontal = 16.dp)
                .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
        )
    }

    if (showHash) {
        HashDialog(
            onDismiss = { showHash = false },
            onSave = { hash ->
                val cleaned = VkUrl.strip(hash)
                if (VkUrl.isPlausibleHash(cleaned)) {
                    conn.saveCallHash(cleaned)
                    showHash = false
                    callMessage = "Hash сохранён вручную"
                }
            },
            onClear = {
                conn.clearCallHash()
                showHash = false
                callMessage = "Hash очищен"
            },
        )
    }
}

@Composable
private fun QuickSettingRow(
    title: String,
    subtitle: String?,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun ChoiceChipButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContainer: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            colors = if (selectedContainer != null) {
                ButtonDefaults.buttonColors(
                    containerColor = selectedContainer,
                    contentColor = Color.White,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                colors.outline.copy(alpha = 0.45f),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun TunnelStatusPanel(
    statusText: String,
    statusColor: Color,
    pathModeLabel: String,
    activePathLabel: String?,
    publicIp: String,
    ipFailed: Boolean = false,
    onIpClick: (() -> Unit)? = null,
    profileName: String?,
    version: String,
    directEndpoint: String?,
    bypassPeer: String?,
    provisionLine: String?,
    probe: ProbeResult?,
    softInfo: String?,
    errorText: String?,
    showVkLogout: Boolean,
    vkLogoutEnabled: Boolean,
    onVkLogout: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 0.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Статус",
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                fontWeight = FontWeight.Medium,
            )
            Text(
                statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
            )
        }

        HorizontalDivider(color = dividerColor)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusFactRow(label = "Режим", value = pathModeLabel)
            activePathLabel?.let {
                val pathColor = when (it) {
                    "Прямое" -> NvpnColors.pathDirect
                    "Обход" -> NvpnColors.pathBypass
                    else -> null
                }
                StatusFactRow(label = "Активный путь", value = it, valueColor = pathColor)
            }
            StatusFactRow(
                label = "IP",
                value = publicIp,
                valueColor = if (ipFailed) MaterialTheme.colorScheme.error else null,
                onClick = onIpClick,
            )
            profileName?.let { StatusFactRow(label = "Профиль", value = it) }
            StatusFactRow(label = "Версия", value = "v$version")
        }

        val hasEndpoints = !directEndpoint.isNullOrBlank() ||
            !bypassPeer.isNullOrBlank() ||
            !provisionLine.isNullOrBlank()
        if (hasEndpoints) {
            HorizontalDivider(color = dividerColor)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Эндпоинты",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    fontWeight = FontWeight.Medium,
                )
                directEndpoint?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Direct", value = it)
                }
                bypassPeer?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Bypass", value = it)
                }
                provisionLine?.takeIf { it.isNotBlank() }?.let {
                    StatusFactRow(label = "Provision", value = it)
                }
            }
        }

        probe?.let { p ->
            HorizontalDivider(color = dividerColor)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Проверка сети",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    fontWeight = FontWeight.Medium,
                )
                StatusFactRow(label = "Сеть", value = p.networkClass.name)
                StatusFactRow(label = "Yandex", value = if (p.yandexOk) "ok" else "—")
                StatusFactRow(label = "Bigtech", value = if (p.bigtechOk) "ok" else "—")
                StatusFactRow(label = "Health", value = if (p.provisionOk) "ok" else "—")
                if (p.elapsedMs > 0) {
                    StatusFactRow(label = "Время", value = "${p.elapsedMs} мс")
                }
            }
        }

        softInfo?.let { info ->
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        errorText?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (showVkLogout) {
            TextButton(
                onClick = onVkLogout,
                enabled = vkLogoutEnabled,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(
                    "Выйти из VK",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
        }
    }
}

@Composable
private fun StatusFactRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HashDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    NvpnDialog(
        title = "Hash звонка",
        onDismissRequest = onDismiss,
        confirmAction = NvpnDialogAction(
            text = "Сохранить",
            onClick = { onSave(text) },
            enabled = text.isNotBlank(),
        ),
        dismissAction = NvpnDialogAction("Отмена", onDismiss),
        secondaryAction = NvpnDialogAction(
            text = "Очистить",
            onClick = onClear,
            destructive = true,
        ),
    ) {
        Text(
            "Ссылка vk.com/call/join/… или сам hash.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
        )
    }
}
