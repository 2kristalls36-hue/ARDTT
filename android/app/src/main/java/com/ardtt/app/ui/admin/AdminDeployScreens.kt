package com.ardtt.app.ui.admin

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.R
import com.ardtt.app.core.needsNotificationPermission
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.DeployHopTrack
import com.ardtt.app.deploy.DeployIssue
import com.ardtt.app.deploy.DeployJobKind
import com.ardtt.app.deploy.DeployProgressCopy
import com.ardtt.app.deploy.DeployRuntimeBundle
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.PendingServerImport
import com.ardtt.app.deploy.ServerLinkCodec
import com.ardtt.app.deploy.ServerOsMark
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.deploy.serverOsBadgeLabel
import com.ardtt.app.deploy.serverOsMark
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttDigitsField
import com.ardtt.app.ui.components.control.ArdttOverflowMenu
import com.ardtt.app.ui.components.control.ArdttOverflowMenuItem
import com.ardtt.app.ui.components.control.ArdttPasswordField
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.control.ArdttSwitchRow
import com.ardtt.app.ui.components.control.ArdttTextField
import com.ardtt.app.ui.components.feedback.ArdttEmptyState
import com.ardtt.app.ui.components.feedback.ArdttIpChip
import com.ardtt.app.ui.components.feedback.ArdttIpHostRow
import com.ardtt.app.ui.components.feedback.ArdttLinearProgress
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttPullRefresh
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttStickyBottomBar
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttCompactCard
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttLeadingIcon
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.components.surface.ArdttTerminalCard
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttElevation
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.theme.connectedStatusColor
import com.ardtt.app.ui.theme.warningStatusColor
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.readClipboardText
import com.ardtt.app.ui.util.shareText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun rememberEnqueueDeploy(
    engine: DeployEngine,
): (DeployTarget, DeployJobKind, Boolean) -> Boolean {
    val context = LocalContext.current
    data class Pending(val target: DeployTarget, val kind: DeployJobKind, val diskCleanup: Boolean)
    val pending = remember { mutableStateOf<Pending?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val job = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        engine.enqueue(job.target, job.kind, diskCleanup = job.diskCleanup)
    }
    return { target, kind, diskCleanup ->
        if (needsNotificationPermission(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            pending.value = Pending(target, kind, diskCleanup)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            true
        } else {
            engine.enqueue(target, kind, diskCleanup = diskCleanup)
        }
    }
}

private sealed class ServersNavScreen {
    data object List : ServersNavScreen()
    data class Overview(val serverId: String) : ServersNavScreen()
    data class Clients(val serverId: String) : ServersNavScreen()
    data class Deploy(val serverId: String?) : ServersNavScreen()
}

/** Host of the currently applied VPN profile (direct endpoint, else bypass peer). */
internal fun activeProfileHost(profile: VpnProfile?): String? {
    if (profile == null) return null
    return NetworkEndpoint.hostOf(profile.direct.endpoint)
        ?: NetworkEndpoint.hostOf(profile.bypass.peer)
}

/** Leave the deploy form only when SSH is idle — collapsing it mid-run trapped a modal scrim. */
internal fun deployFormCanLeave(busy: Boolean): Boolean = !busy

/**
 * Server that backs the current profile/deploy in use.
 * Prefer exact [DeployTarget.publicHost], then [DeployTarget.host]; if no profile match,
 * fall back to the most recently successfully deployed server.
 */
internal fun findActiveDeployServerId(
    servers: List<DeployTarget>,
    profileHost: String?,
): String? {
    if (servers.isEmpty()) return null
    val host = profileHost?.trim()?.takeIf { it.isNotEmpty() }
    if (host != null) {
        val byPublic = servers.filter {
            it.publicHost.isNotBlank() && it.publicHost.equals(host, ignoreCase = true)
        }
        if (byPublic.isNotEmpty()) {
            return byPublic.maxByOrNull { it.lastDeployedAtMs }?.id
        }
        val byHost = servers.filter { it.host.equals(host, ignoreCase = true) }
        if (byHost.isNotEmpty()) {
            return byHost.maxByOrNull { it.lastDeployedAtMs }?.id
        }
    }
    return servers
        .filter { it.lastDeployedAtMs > 0L }
        .maxByOrNull { it.lastDeployedAtMs }
        ?.id
}

private val ServersNavScreenSaver = Saver<ServersNavScreen, List<String>>(
    save = { state ->
        when (state) {
            is ServersNavScreen.List -> listOf("list")
            is ServersNavScreen.Overview -> listOf("overview", state.serverId)
            is ServersNavScreen.Clients -> listOf("clients", state.serverId)
            is ServersNavScreen.Deploy -> listOf("deploy", state.serverId ?: "")
        }
    },
    restore = { saved ->
        when (saved.getOrNull(0)) {
            "overview" -> ServersNavScreen.Overview(saved.getOrElse(1) { "" })
            "clients" -> ServersNavScreen.Clients(saved.getOrElse(1) { "" })
            "deploy" -> ServersNavScreen.Deploy(saved.getOrNull(1)?.ifEmpty { null })
            else -> ServersNavScreen.List
        }
    },
)

@Composable
private fun pingLatencyContentColor(
    pingMs: Long,
    poor: Color,
): Color? = when (pingLatencyTier(pingMs)) {
    PingLatencyTier.Good -> connectedStatusColor()
    PingLatencyTier.Fair -> warningStatusColor()
    PingLatencyTier.Poor -> poor
    null -> null
}

@Composable
private fun serverPresenceColor(health: HealthUi?): Color = when (health) {
    is HealthUi.Online -> connectedStatusColor()
    HealthUi.Unreachable, HealthUi.NotInstalled -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun ServerPresenceLabel(health: HealthUi?) {
    val parts = healthStatusParts(health)
    Text(
        parts.presence,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = serverPresenceColor(health),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ServerHealthStatusRow(
    health: HealthUi?,
    expectedVersion: String,
) {
    val parts = healthStatusParts(health, expectedVersion)
    if (parts.deploy.isNullOrEmpty() && parts.pingLabel.isEmpty()) return
    val expected = effectiveExpectedVersion(health, expectedVersion)
    val deployColor = when (health) {
        is HealthUi.Online ->
            if (DeployBundle.isCurrent(health.deployVersion, expected)) {
                connectedStatusColor()
            } else {
                warningStatusColor()
            }
        else -> null
    }
    val pingColor = pingLatencyContentColor(parts.pingMs, MaterialTheme.colorScheme.error)
    val labelStyle = MaterialTheme.typography.labelSmall
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
    ) {
        Text(
            parts.deploy.orEmpty(),
            style = labelStyle,
            fontWeight = FontWeight.SemiBold,
            color = deployColor ?: Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        Text(
            parts.pingLabel,
            style = labelStyle,
            fontWeight = FontWeight.SemiBold,
            color = pingColor ?: Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun ServersScreen(
    serversRepo: ServersRepository,
    engine: DeployEngine,
    profiles: ProfileRepository,
    reselectSignal: Int = 0,
) {
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by engine.busy.collectAsStateWithLifecycle()
    var screen by rememberSaveable(stateSaver = ServersNavScreenSaver) {
        mutableStateOf<ServersNavScreen>(ServersNavScreen.List)
    }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal > 0) {
            screen = ServersNavScreen.List
        }
    }

    val canPopServers = screen !is ServersNavScreen.List &&
        !(screen is ServersNavScreen.Deploy && !deployFormCanLeave(busy))
    BackHandler(enabled = canPopServers) {
        screen = when (val current = screen) {
            is ServersNavScreen.Clients -> ServersNavScreen.Overview(current.serverId)
            is ServersNavScreen.Deploy -> current.serverId
                ?.let { ServersNavScreen.Overview(it) }
                ?: ServersNavScreen.List
            is ServersNavScreen.Overview -> ServersNavScreen.List
            is ServersNavScreen.List -> ServersNavScreen.List
        }
    }

    val openDeployId by PendingUiAction.openDeployServerId.collectAsStateWithLifecycle()
    LaunchedEffect(openDeployId) {
        val id = PendingUiAction.consumeOpenDeploy() ?: return@LaunchedEffect
        screen = ServersNavScreen.Overview(id)
    }

    Crossfade(targetState = screen, label = "servers_nav") { current ->
        when (val s = current) {
            is ServersNavScreen.List -> ServerListScreen(
                servers = servers,
                serversRepo = serversRepo,
                onOpenServer = { id -> screen = ServersNavScreen.Overview(id) },
                onAddServer = { screen = ServersNavScreen.Deploy(null) },
            )
            is ServersNavScreen.Overview -> ServerOverviewHost(
                servers = servers,
                serversRepo = serversRepo,
                engine = engine,
                serverId = s.serverId,
                onOpenClients = { screen = ServersNavScreen.Clients(s.serverId) },
                onOpenDeploySettings = { screen = ServersNavScreen.Deploy(s.serverId) },
                onBack = { screen = ServersNavScreen.List },
            )
            is ServersNavScreen.Clients -> ClientsHost(
                servers = servers,
                serverId = s.serverId,
                profiles = profiles,
                onBack = { screen = ServersNavScreen.Overview(s.serverId) },
            )
            is ServersNavScreen.Deploy -> {
                val initial = s.serverId?.let { id -> servers.find { it.id == id } }
                DeployScreen(
                    serversRepo = serversRepo,
                    engine = engine,
                    initial = initial,
                    onSaved = { savedId ->
                        screen = ServersNavScreen.Overview(savedId)
                    },
                    onBack = {
                        screen = s.serverId
                            ?.let { ServersNavScreen.Overview(it) }
                            ?: ServersNavScreen.List
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerListScreen(
    servers: List<DeployTarget>,
    serversRepo: ServersRepository,
    onOpenServer: (String) -> Unit,
    onAddServer: () -> Unit,
) {
    val context = LocalContext.current
    val expectedVersion = remember(context) { DeployBundle.expectedVersion(context) }
    var healthById by remember { mutableStateOf<Map<String, HealthUi>>(emptyMap()) }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var shareTargets by remember { mutableStateOf<List<DeployTarget>?>(null) }
    var pendingImport by remember { mutableStateOf<List<DeployTarget>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun exitSelectMode() {
        selectMode = false
        selectedIds = emptySet()
    }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun tryParseImport(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            importError = "Буфер обмена пуст"
            return false
        }
        return runCatching {
            pendingImport = ServerLinkCodec.parseLink(trimmed)
            importError = null
            true
        }.getOrElse { t ->
            importError = t.message ?: "Не удалось разобрать ссылку"
            false
        }
    }

    suspend fun probeAll() {
        val snapshot = serversRepo.snapshot()
        if (snapshot.isEmpty()) {
            healthById = emptyMap()
            return
        }
        healthById = snapshot.associate { it.id to HealthUi.Checking }
        coroutineScope {
            snapshot.map { target ->
                async {
                    val status = probeServerHealthUi(target, serversRepo)
                    healthById = healthById + (target.id to status)
                }
            }.awaitAll()
        }
    }

    val serverIds = remember(servers) { servers.map { it.id }.joinToString(",") }
    LaunchedEffect(serverIds) {
        probeAll()
        val known = servers.map { it.id }.toSet()
        selectedIds = selectedIds.filter { it in known }.toSet()
    }

    val pendingImportLink by PendingServerImport.link.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImportLink) {
        val link = PendingServerImport.take() ?: return@LaunchedEffect
        tryParseImport(link)
    }

    BackHandler(enabled = selectMode) {
        exitSelectMode()
    }

    val pull = rememberPullRefresh {
        if (servers.isNotEmpty()) probeAll()
    }

    val selectedServers = remember(servers, selectedIds) {
        servers.filter { it.id in selectedIds }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArdttScrollChrome(
            header = {
                ArdttTabHeader(
                    title = if (selectMode) "Экспорт серверов" else "Управление серверами",
                    subtitle = if (selectMode) "Выбрано: ${selectedIds.size}" else null,
                    actions = {
                        if (selectMode) {
                            ArdttButton(
                                onClick = { exitSelectMode() },
                                variant = ArdttButtonVariant.Icon,
                                icon = Icons.Filled.Close,
                                contentDescription = "Отменить экспорт",
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Box {
                                ArdttButton(
                                    onClick = { menuExpanded = true },
                                    variant = ArdttButtonVariant.Icon,
                                    icon = Icons.Filled.MoreVert,
                                    contentDescription = "Меню серверов",
                                    contentColor = MaterialTheme.colorScheme.primary,
                                )
                                ArdttOverflowMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    ArdttOverflowMenuItem(
                                        text = "Экспорт",
                                        leadingIcon = Icons.Filled.FileUpload,
                                        enabled = servers.isNotEmpty(),
                                        onClick = {
                                            menuExpanded = false
                                            selectMode = true
                                            selectedIds = emptySet()
                                        },
                                    )
                                    ArdttOverflowMenuItem(
                                        text = "Импорт из буфера",
                                        leadingIcon = Icons.Filled.FileDownload,
                                        onClick = {
                                            menuExpanded = false
                                            val clip = readClipboardText(context).orEmpty()
                                            if (tryParseImport(clip)) {
                                                Toast.makeText(
                                                    context,
                                                    "Найдено серверов: ${pendingImport?.size ?: 0}",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    importError ?: "Импорт не удался",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
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

                if (servers.isEmpty()) {
                    ArdttEmptyState(
                        title = "Нет серверов",
                        description = "Добавьте первый сервер, чтобы установить стек " +
                            "и управлять пользователями.",
                        icon = Icons.Filled.Dns,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = ArdttSpacing.Small,
                            bottom = ArdttBottomChrome.scrollContentPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(ArdttLayout.ListSpacing),
                    ) {
                        items(servers, key = { it.id }) { server ->
                            val selected = server.id in selectedIds
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
                            ) {
                                if (selectMode) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { toggleSelected(server.id) },
                                    )
                                }
                                ServerCard(
                                    server = server,
                                    health = healthById[server.id],
                                    expectedVersion = expectedVersion,
                                    modifier = Modifier.weight(1f),
                                    onOpenServer = {
                                        if (selectMode) toggleSelected(server.id)
                                        else onOpenServer(server.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        ArdttStickyBottomBar {
            ArdttPrimaryButton(
                text = if (selectMode) "Экспортировать" else "Добавить сервер",
                onClick = {
                    if (selectMode) shareTargets = selectedServers
                    else onAddServer()
                },
                enabled = !selectMode || selectedServers.isNotEmpty(),
                icon = if (selectMode) Icons.Filled.FileUpload else Icons.Filled.Add,
            )
        }
    }

    shareTargets?.let { targets ->
        ServerShareDialog(
            servers = targets,
            onDismissRequest = {
                shareTargets = null
                exitSelectMode()
            },
        )
    }

    pendingImport?.let { imported ->
        ArdttDialog(
            title = "Импорт серверов",
            onDismissRequest = { pendingImport = null },
            dismissAction = ArdttDialogAction("Отмена", onClick = { pendingImport = null }),
            confirmAction = ArdttDialogAction(
                "Импортировать",
                onClick = {
                    serversRepo.upsertAll(imported)
                    Toast.makeText(context, "Импортировано: ${imported.size}", Toast.LENGTH_SHORT).show()
                    pendingImport = null
                },
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small)) {
                Text(
                    "Будут добавлены или обновлены ${imported.size} сервер(ов). SSH-секреты входят в закрытую ссылку.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                imported.take(8).forEach { s ->
                    Text(
                        "• ${s.name.ifBlank { s.host }} (${s.host})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (imported.size > 8) {
                    Text(
                        "…и ещё ${imported.size - 8}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: DeployTarget,
    health: HealthUi?,
    expectedVersion: String,
    onOpenServer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ArdttCompactCard(
        modifier = if (onOpenServer != null) {
            modifier.clickable(
                onClick = onOpenServer,
                onClickLabel = "Открыть подробности",
            )
        } else {
            modifier
        },
    ) {
        ServerIdentityBody(
            server = server,
            health = health,
            expectedVersion = expectedVersion,
            showOpenHint = onOpenServer != null,
        )
    }
}

@Composable
private fun ServerIdentityBody(
    server: DeployTarget,
    health: HealthUi?,
    expectedVersion: String,
    showOpenHint: Boolean = false,
) {
    val cascadeHosts = serverCardCascadeHosts(
        host = server.host,
        publicHost = server.publicHost,
        cascadeEnabled = server.cascadeEnabled,
        cascadeHost = server.cascadeHost,
    )
    val cascadeSpan = cascadeHosts.takeIf { it.size >= 2 }?.joinToString(" → ")
    val title = serverCardTitle(server.name, server.host, cascadeSpan)
    val titleHosts = serverCardTitleHosts(server.name, server.host, cascadeHosts)
    val meta = serverCardMetaParts(
        name = server.name,
        host = server.host,
        publicHost = server.publicHost,
        cascadeEnabled = server.cascadeEnabled,
        cascadeHost = server.cascadeHost,
    )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
    ) {
        ArdttLeadingIcon(
            imageVector = Icons.Filled.Dns,
            contentDescription = "Сервер",
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ArdttLayout.CompactCardSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
            ) {
                if (titleHosts.isNotEmpty()) {
                    ArdttIpHostRow(
                        hosts = titleHosts,
                        modifier = Modifier.weight(1f),
                        muted = muted,
                    )
                } else {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ServerOsBadge(
                    osId = server.osId,
                    osVersion = server.osVersion,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.TinyPlus),
                ) {
                    if (meta.hosts.isNotEmpty()) {
                        ArdttIpHostRow(hosts = meta.hosts, muted = muted)
                    }
                    meta.pubHost?.let { pub ->
                        if (meta.hosts.isNotEmpty()) {
                            Text("·", style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                        ArdttIpChip(pub)
                    }
                }
                ServerPresenceLabel(health)
            }
            ServerHealthStatusRow(
                health = health,
                expectedVersion = expectedVersion,
            )
        }
        if (showOpenHint) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Открыть подробности",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeployProgressSheet(
    busy: Boolean,
    isUpdate: Boolean,
    status: String?,
    step: String,
    progress: Float,
    log: List<String>,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    isUninstall: Boolean = false,
    isPreflight: Boolean = false,
    hopTrack: DeployHopTrack = DeployHopTrack(),
    failure: DeployIssue? = null,
    onRetryPreflight: (() -> Unit)? = null,
    onRetryInstall: (() -> Unit)? = null,
    onDiskCleanupRetry: (() -> Unit)? = null,
    retryInstallEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val failed = deployProgressFailed(busy, status, failure)
    val finishedOk = deployProgressFinishedSuccess(busy, status, failure)
    val slots = cascadeDeploySlots(
        hopTrack,
        failed = failed,
        finishedSuccess = finishedOk,
    )
    var showLog by remember { mutableStateOf(false) }
    var showDiskCleanupConfirm by remember { mutableStateOf(false) }
    val redacted = remember(log) { DeployIssue.redactLog(log.joinToString("\n")) }
    val headline = when {
        failure != null -> failure.summary
        !status.isNullOrBlank() -> status
        else -> null
    }
    val dockerMissing = failure?.code == DeployIssue.DOCKER_MISSING
    val offerDiskCleanup = !busy && !isUninstall &&
        DeployIssue.offersDiskCleanup(failure) &&
        onDiskCleanupRetry != null
    val prepareLabel = if (failure?.hopRole == "exit") "Подготовить VPS2" else "Подготовить VPS"
    ArdttDialog(
        title = deployProgressSheetTitle(
            busy = busy,
            isUpdate = isUpdate,
            status = status,
            isUninstall = isUninstall,
            failure = failure,
            isPreflight = isPreflight,
        ),
        onDismissRequest = {},
        confirmAction = if (busy) {
            ArdttDialogAction("Отменить", onCancel, destructive = true)
        } else {
            ArdttDialogAction("Закрыть", onClose)
        },
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
    ) {
        if (slots.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Small)) {
                slots.forEach { slot ->
                    DeployHopSlotCard(
                        slot = slot,
                        isUpdate = isUpdate,
                        isUninstall = isUninstall,
                    )
                }
            }
        }
        if (failed) {
            headline?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (step.isNotBlank()) {
                Text(
                    "Этап: $step",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                step.ifBlank { headline ?: "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (finishedOk) connectedStatusColor() else MaterialTheme.colorScheme.onSurface,
            )
            if (busy) {
                ArdttLinearProgress(progress = progress)
                Text(
                    DeployProgressCopy.percentLabel(progress),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!busy && dockerMissing && !DeployRuntimeBundle.INCLUDED) {
            Text(
                DeployRuntimeBundle.missingRuntimeMessage(
                    osId = "",
                    osVersion = "",
                    arch = "",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttButton(
                text = prepareLabel,
                onClick = {},
                enabled = false,
                variant = ArdttButtonVariant.Tonal,
                fillMaxWidth = true,
            )
        }
        if (offerDiskCleanup) {
            Text(
                "Можно освободить место на VPS (логи Docker, кэш apt, лишние headers, хвосты ARDTT) и сразу повторить установку. Данные ARDTT и чужие контейнеры не удаляются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttButton(
                text = "Очистить место и повторить",
                onClick = { showDiskCleanupConfirm = true },
                fillMaxWidth = true,
            )
        }
        if (!busy && !isUninstall) {
            if (onRetryPreflight != null) {
                ArdttButton(
                    text = "Повторить проверку",
                    onClick = onRetryPreflight,
                    variant = ArdttButtonVariant.Outlined,
                    fillMaxWidth = true,
                )
            }
            if (onRetryInstall != null) {
                ArdttButton(
                    text = "Повторить установку",
                    onClick = onRetryInstall,
                    enabled = retryInstallEnabled,
                    fillMaxWidth = true,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        ) {
            ArdttButton(
                text = if (showLog) "Скрыть журнал" else "Журнал",
                onClick = { showLog = !showLog },
                variant = ArdttButtonVariant.Text,
                modifier = Modifier.weight(1f),
            )
            ArdttButton(
                text = "Скопировать лог",
                onClick = { copyToClipboard(context, redacted, "ARDTT deploy") },
                enabled = redacted.isNotBlank(),
                variant = ArdttButtonVariant.Text,
                modifier = Modifier.weight(1f),
            )
        }
        ArdttButton(
            text = "Поделиться логом",
            onClick = { shareText(context, redacted, "ARDTT deploy", "Поделиться логом") },
            enabled = redacted.isNotBlank(),
            variant = ArdttButtonVariant.Text,
            fillMaxWidth = true,
        )
        if (showLog) {
            ArdttTerminalCard(
                text = redacted.ifBlank { log.takeLast(24).joinToString("\n") },
                maxHeight = 200.dp,
            )
        }
    }
    if (showDiskCleanupConfirm && onDiskCleanupRetry != null) {
        ArdttDialog(
            title = "Очистить место на VPS?",
            onDismissRequest = { showDiskCleanupConfirm = false },
            confirmAction = ArdttDialogAction(
                text = "Очистить и установить",
                onClick = {
                    showDiskCleanupConfirm = false
                    onDiskCleanupRetry()
                },
            ),
            dismissAction = ArdttDialogAction(
                text = "Отмена",
                onClick = { showDiskCleanupConfirm = false },
            ),
        ) {
            Text(
                "Будут очищены: большие логи Docker, кэш apt, неиспользуемые linux-headers, хвосты неудачной установки ARDTT. Не трогаем: /opt/ardtt/data, работающие контейнеры, образы в использовании.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeployHopSlotCard(
    slot: DeploySlotView,
    isUpdate: Boolean,
    isUninstall: Boolean,
) {
    val outline = MaterialTheme.colorScheme.outline
    val borderColor = when (slot.phase) {
        DeploySlotPhase.Done -> ArdttColors.Connected
        DeploySlotPhase.Failed -> MaterialTheme.colorScheme.error
        DeploySlotPhase.Skipped,
        DeploySlotPhase.Pending,
        DeploySlotPhase.Active,
        -> hopMapGrayStroke(outline)
    }
    val statusColor = when (slot.phase) {
        DeploySlotPhase.Done -> connectedStatusColor()
        DeploySlotPhase.Failed -> MaterialTheme.colorScheme.error
        DeploySlotPhase.Active -> MaterialTheme.colorScheme.onSurface
        DeploySlotPhase.Pending, DeploySlotPhase.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ArdttSectionCard(
        contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.SmallPlus),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
        shape = ArdttShapes.Chip,
        shadowElevation = ArdttElevation.None,
        tonalElevation = ArdttElevation.None,
        border = BorderStroke(ArdttSectionCardDefaults.ContourWidth, borderColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                slot.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                deploySlotStatusText(slot.phase, isUpdate, isUninstall),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
            )
        }
        Text(
            slot.host,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ServerOverviewHost(
    servers: List<DeployTarget>,
    serversRepo: ServersRepository,
    engine: DeployEngine,
    serverId: String,
    onOpenClients: () -> Unit,
    onOpenDeploySettings: () -> Unit,
    onBack: () -> Unit,
) {
    val server = servers.find { it.id == serverId }
    var showActions by remember { mutableStateOf(false) }
    var removeConfirmKind by remember { mutableStateOf<ServerRemoveKind?>(null) }
    var showDeleteProgress by remember { mutableStateOf(false) }
    var deleteStatus by remember { mutableStateOf<String?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showRedeployConfirm by remember { mutableStateOf(false) }
    var showRedeployProgress by remember { mutableStateOf(false) }
    var redeployStatus by remember { mutableStateOf<String?>(null) }
    var health by remember { mutableStateOf<HealthUi?>(HealthUi.Checking) }
    val context = LocalContext.current
    val expectedVersion = remember(context) { DeployBundle.expectedVersion(context) }
    val enqueueJob = rememberEnqueueDeploy(engine)
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val deployLog by engine.log.collectAsStateWithLifecycle()
    val outcome by engine.outcome.collectAsStateWithLifecycle()
    val activeTargetId by engine.activeTargetId.collectAsStateWithLifecycle()
    val engineIsUpdate by engine.isUpdate.collectAsStateWithLifecycle()
    val engineIsUninstall by engine.isUninstall.collectAsStateWithLifecycle()
    val engineIsPreflight by engine.isPreflight.collectAsStateWithLifecycle()
    val hopTrack by engine.hopTrack.collectAsStateWithLifecycle()
    val engineFailure by engine.failure.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var localFailure by remember { mutableStateOf<DeployIssue?>(null) }
    var lastPreflightOk by remember { mutableStateOf(false) }
    val failure = localFailure ?: engineFailure

    LaunchedEffect(servers, serverId, showDeleteProgress) {
        if (showDeleteProgress) return@LaunchedEffect
        if (servers.isNotEmpty() && server == null) onBack()
    }

    LaunchedEffect(serverId, server?.host, server?.publicHost) {
        val target = server ?: return@LaunchedEffect
        health = HealthUi.Checking
        health = probeServerHealthUi(target, serversRepo)
    }

    LaunchedEffect(busy, activeTargetId, serverId, engineIsUninstall) {
        if (busy && activeTargetId == serverId) {
            if (engineIsUninstall) {
                showDeleteProgress = true
                deleteStatus = null
                localFailure = null
            } else {
                showRedeployProgress = true
                redeployStatus = null
                localFailure = null
            }
        }
    }

    LaunchedEffect(busy, outcome, serverId, activeTargetId, showDeleteProgress, showRedeployProgress, engineFailure, engineIsPreflight) {
        if (busy || outcome == null) return@LaunchedEffect
        if (activeTargetId != null && activeTargetId != serverId) return@LaunchedEffect
        if (showDeleteProgress) {
            deleteStatus = outcome
            return@LaunchedEffect
        }
        if (!showRedeployProgress) return@LaunchedEffect
        redeployStatus = outcome
        localFailure = engineFailure
        val fail = engineFailure
        if (engineIsPreflight && fail == null) {
            lastPreflightOk = true
        } else if (fail != null && !fail.isCancelled) {
            lastPreflightOk = false
        }
        val target = server ?: return@LaunchedEffect
        health = HealthUi.Checking
        health = probeServerHealthUi(target, serversRepo)
    }

    fun startRedeploy(target: DeployTarget, diskCleanup: Boolean = false) {
        showRedeployConfirm = false
        showRedeployProgress = true
        redeployStatus = null
        localFailure = null
        val kind = if (serverOverviewDeployIsUpdate(health)) {
            DeployJobKind.Update
        } else {
            DeployJobKind.Install
        }
        if (!enqueueJob(target, kind, diskCleanup)) {
            localFailure = deployBusyIssue()
            redeployStatus = localFailure?.summary
        }
    }

    fun startPreflight(target: DeployTarget) {
        showRedeployProgress = true
        redeployStatus = null
        localFailure = null
        if (!enqueueJob(target, DeployJobKind.Preflight, false)) {
            localFailure = deployBusyIssue()
            redeployStatus = localFailure?.summary
        }
    }

    fun startUninstall(target: DeployTarget) {
        removeConfirmKind = null
        showDeleteProgress = true
        deleteStatus = null
        localFailure = null
        if (!enqueueJob(target, DeployJobKind.Uninstall, false)) {
            localFailure = deployBusyIssue()
            deleteStatus = localFailure?.summary
        }
    }

    fun startLocalCardDelete(target: DeployTarget) {
        removeConfirmKind = null
        serversRepo.delete(target.id)
        onBack()
    }

    val pull = rememberPullRefresh {
        val target = server ?: return@rememberPullRefresh
        health = probeServerHealthUi(target, serversRepo)
    }

    // Live host gauges while the overview is open (also refreshed by pull-to-refresh).
    LaunchedEffect(serverId, server?.host, server?.publicHost) {
        val target = server ?: return@LaunchedEffect
        while (true) {
            delay(10_000)
            if (busy) continue
            health = probeServerHealthUi(target, serversRepo)
        }
    }

    if (server == null && !showDeleteProgress) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        if (server != null) {
            ServerOverviewScreen(
                server = server,
                health = health,
                expectedVersion = expectedVersion,
                onOpenClients = onOpenClients,
                onUpdateDeploy = { showRedeployConfirm = true },
                onPrimaryAction = {
                    when (serverOverviewPrimaryAction(health, expectedVersion)) {
                        ServerOverviewPrimaryAction.Check -> scope.launch {
                            health = HealthUi.Checking
                            health = probeServerHealthUi(server, serversRepo)
                        }
                        ServerOverviewPrimaryAction.Install,
                        ServerOverviewPrimaryAction.Update,
                        -> showRedeployConfirm = true
                    }
                },
                primaryAction = serverOverviewPrimaryAction(health, expectedVersion),
                onOpenDeploySettings = onOpenDeploySettings,
                onBack = onBack,
                showActions = showActions,
                onShowActions = { showActions = it },
                onRename = { showRename = true },
                onDeleteCard = { removeConfirmKind = ServerRemoveKind.Card },
                onUninstallServer = { removeConfirmKind = ServerRemoveKind.Uninstall },
                refreshing = pull.refreshing,
                onRefresh = pull.onRefresh,
            )
            if (showRename) {
                RenameServerDialog(
                    initialName = server.name.ifBlank { server.host },
                    onDismiss = { showRename = false },
                    onConfirm = { name ->
                        serversRepo.upsert(server.copy(name = name))
                        showRename = false
                    },
                )
            }
            val removeKind = removeConfirmKind
            if (removeKind != null) {
                ArdttDialog(
                    title = serverDeleteConfirmTitle(removeKind),
                    onDismissRequest = { if (!busy) removeConfirmKind = null },
                    confirmAction = ArdttDialogAction(
                        text = serverDeleteConfirmAction(removeKind),
                        onClick = {
                            when (removeKind) {
                                ServerRemoveKind.Card -> startLocalCardDelete(server)
                                ServerRemoveKind.Uninstall -> startUninstall(server)
                            }
                        },
                        destructive = true,
                        enabled = !busy,
                    ),
                    dismissAction = ArdttDialogAction(
                        text = "Отмена",
                        onClick = { removeConfirmKind = null },
                        enabled = !busy,
                    ),
                    dismissOnBackPress = !busy,
                    dismissOnClickOutside = !busy,
                ) {
                    Text(
                        serverDeleteConfirmBody(
                            host = server.host,
                            cascadeEnabled = server.cascadeEnabled,
                            cascadeHost = server.cascadeHost,
                            kind = removeKind,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showRedeployConfirm) {
                ArdttDialog(
                    title = serverOverviewDeployConfirmTitle(health),
                    onDismissRequest = { if (!busy) showRedeployConfirm = false },
                    confirmAction = ArdttDialogAction(
                        text = serverOverviewDeployConfirmAction(health),
                        onClick = { startRedeploy(server) },
                        enabled = !busy,
                    ),
                    dismissAction = ArdttDialogAction(
                        text = "Отмена",
                        onClick = { showRedeployConfirm = false },
                        enabled = !busy,
                    ),
                    dismissOnBackPress = !busy,
                    dismissOnClickOutside = !busy,
                ) {
                    Text(
                        serverReinstallConfirmBody(server.host, expectedVersion) +
                            " Параметры подключения менять не нужно.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showDeleteProgress) {
            if (server == null) {
                Box(modifier = Modifier.fillMaxSize())
            }
            DeployProgressSheet(
                busy = busy,
                isUpdate = false,
                isUninstall = true,
                status = deleteStatus,
                step = step,
                progress = progress,
                log = deployLog,
                hopTrack = hopTrack,
                failure = failure,
                onCancel = { engine.cancel() },
                onClose = {
                    val leave = serverDeleteFinishedShouldLeave(busy, deleteStatus)
                    showDeleteProgress = false
                    if (leave) onBack()
                },
            )
        } else if (server != null && showRedeployProgress) {
            DeployProgressSheet(
                busy = busy,
                isUpdate = if (busy) engineIsUpdate else serverOverviewDeployIsUpdate(health),
                isPreflight = if (busy) engineIsPreflight else false,
                status = redeployStatus,
                step = step,
                progress = progress,
                log = deployLog,
                hopTrack = hopTrack,
                failure = failure,
                onRetryPreflight = { startPreflight(server) },
                onRetryInstall = { startRedeploy(server) },
                onDiskCleanupRetry = { startRedeploy(server, diskCleanup = true) },
                retryInstallEnabled = !busy,
                onCancel = { engine.cancel() },
                onClose = { showRedeployProgress = false },
            )
        }
    }
}

@Composable
private fun ServerOverviewScreen(
    server: DeployTarget,
    health: HealthUi?,
    expectedVersion: String,
    onOpenClients: () -> Unit,
    onUpdateDeploy: () -> Unit,
    onPrimaryAction: () -> Unit,
    primaryAction: ServerOverviewPrimaryAction,
    onOpenDeploySettings: () -> Unit,
    onBack: () -> Unit,
    showActions: Boolean,
    onShowActions: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDeleteCard: () -> Unit,
    onUninstallServer: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ArdttScrollChrome(
            header = {
                ArdttTabHeader(
                    title = server.name.ifBlank { server.host },
                    subtitle = "Управление сервером",
                    onBack = onBack,
                    actions = {
                        Box {
                            ArdttButton(
                                onClick = { onShowActions(true) },
                                variant = ArdttButtonVariant.Icon,
                                icon = Icons.Filled.MoreVert,
                                contentDescription = "Действия с сервером",
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                            ArdttOverflowMenu(
                                expanded = showActions,
                                onDismissRequest = { onShowActions(false) },
                            ) {
                                ArdttOverflowMenuItem(
                                    text = serverOverviewDeployActionLabel(health),
                                    leadingIcon = Icons.Filled.CloudUpload,
                                    onClick = {
                                        onShowActions(false)
                                        onUpdateDeploy()
                                    },
                                )
                                ArdttOverflowMenuItem(
                                    text = "Переименовать",
                                    leadingIcon = Icons.Filled.Edit,
                                    onClick = {
                                        onShowActions(false)
                                        onRename()
                                    },
                                )
                                ArdttOverflowMenuItem(
                                    text = serverRemoveMenuLabel(ServerRemoveKind.Card),
                                    leadingIcon = Icons.Filled.Delete,
                                    destructive = true,
                                    onClick = {
                                        onShowActions(false)
                                        onDeleteCard()
                                    },
                                )
                                ArdttOverflowMenuItem(
                                    text = serverRemoveMenuLabel(ServerRemoveKind.Uninstall),
                                    leadingIcon = Icons.Filled.DeleteForever,
                                    destructive = true,
                                    onClick = {
                                        onShowActions(false)
                                        onUninstallServer()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { topPad ->
        ArdttPullRefresh(
            refreshing = refreshing,
            onRefresh = onRefresh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            ) {

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = ArdttSpacing.Large,
                        end = ArdttSpacing.Large,
                        top = ArdttSpacing.Small,
                        bottom = ArdttBottomChrome.scrollContentPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(ArdttLayout.ListSpacing),
                ) {
            item {
                val online = health as? HealthUi.Online
                val host = online?.host
                if (host != null) {
                    ServerHostMetricsCard(host = host)
                }
            }
            item {
                ServerCard(
                    server = server,
                    health = health,
                    expectedVersion = expectedVersion,
                )
            }
            item {
                Text(
                    "Действия",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = ArdttSpacing.Tiny, top = ArdttSpacing.Tiny, bottom = ArdttSpacing.Hairline),
                )
            }
            item {
                ServerActionCard(
                    icon = Icons.Filled.People,
                    title = "Клиенты",
                    description = "Создание профилей через provision",
                    onClick = onOpenClients,
                )
            }
            item {
                ServerActionCard(
                    icon = Icons.Filled.Settings,
                    title = "Параметры сервера",
                    description = "SSH, порты и учётные данные",
                    onClick = onOpenDeploySettings,
                )
            }
                }
            }
        }
        }

        ArdttStickyBottomBar {
            ArdttPrimaryButton(
                text = serverOverviewPrimaryLabel(primaryAction),
                onClick = onPrimaryAction,
                busy = health is HealthUi.Checking && primaryAction == ServerOverviewPrimaryAction.Check,
                containerColor = if (primaryAction == ServerOverviewPrimaryAction.Update) {
                    ArdttColors.Warning
                } else {
                    null
                },
                contentColor = if (primaryAction == ServerOverviewPrimaryAction.Update) {
                    ArdttColors.OnWarning
                } else {
                    null
                },
                icon = if (primaryAction == ServerOverviewPrimaryAction.Check) {
                    Icons.Filled.Refresh
                } else {
                    Icons.Filled.CloudUpload
                },
            )
        }
    }
}

@Composable
private fun ServerActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    ArdttCompactCard(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.None),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = ArdttShapes.Icon,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(ArdttSpacing.Small)
                        .size(ArdttSize.IconCompact),
                )
            }
            Spacer(modifier = Modifier.width(ArdttSpacing.Medium))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Hairline),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameServerDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    ArdttDialog(
        title = "Переименовать",
        onDismissRequest = onDismiss,
        confirmAction = ArdttDialogAction(
            "Сохранить",
            { onConfirm(name.trim().ifBlank { initialName }) },
        ),
        dismissAction = ArdttDialogAction("Отмена", onDismiss),
    ) {
        ArdttTextField(
            value = name,
            onValueChange = { name = it },
            label = "Имя сервера",
        )
    }
}

@Composable
fun DeployScreen(
    serversRepo: ServersRepository,
    engine: DeployEngine,
    initial: DeployTarget? = null,
    onSaved: (serverId: String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val enqueueJob = rememberEnqueueDeploy(engine)
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()
    val outcome by engine.outcome.collectAsStateWithLifecycle()

    var id by remember { mutableStateOf(initial?.id ?: serversRepo.newId()) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var sshPort by remember { mutableStateOf((initial?.sshPort ?: 22).toString()) }
    var sshUser by remember { mutableStateOf(deploySshUserOrRoot(initial?.sshUser.orEmpty())) }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember { mutableStateOf(initial?.privateKeyPem ?: "") }
    var keyPass by remember { mutableStateOf(initial?.keyPassphrase ?: "") }
    var publicHost by remember { mutableStateOf(initial?.publicHost ?: "") }
    var autoPorts by remember { mutableStateOf(initial?.autoPorts != false) }
    var directPort by remember { mutableStateOf((initial?.directPort ?: 51820).toString()) }
    var bypassPort by remember { mutableStateOf((initial?.bypassPort ?: 56003).toString()) }
    var osId by remember { mutableStateOf(initial?.osId ?: "") }
    var osVersion by remember { mutableStateOf(initial?.osVersion ?: "") }
    var lastDeployedAtMs by remember { mutableStateOf(initial?.lastDeployedAtMs ?: 0L) }
    var cascadeEnabled by remember { mutableStateOf(initial?.cascadeEnabled == true) }
    var cascadeHost by remember { mutableStateOf(initial?.cascadeHost ?: "") }
    var cascadePort by remember { mutableStateOf((initial?.cascadePort ?: 22).toString()) }
    var cascadeUser by remember { mutableStateOf(deploySshUserOrRoot(initial?.cascadeUser.orEmpty())) }
    var cascadePassword by remember { mutableStateOf(initial?.cascadePassword ?: "") }
    var cascadePrivateKey by remember { mutableStateOf(initial?.cascadePrivateKeyPem ?: "") }
    var cascadeKeyPass by remember { mutableStateOf(initial?.cascadeKeyPassphrase ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var deployStatus by remember { mutableStateOf<String?>(null) }
    var showReinstallConfirm by remember { mutableStateOf(false) }
    var showDeployProgress by remember { mutableStateOf(false) }
    val activeTargetId by engine.activeTargetId.collectAsStateWithLifecycle()
    val engineIsUpdate by engine.isUpdate.collectAsStateWithLifecycle()
    val engineIsPreflight by engine.isPreflight.collectAsStateWithLifecycle()
    val hopTrack by engine.hopTrack.collectAsStateWithLifecycle()
    val engineFailure by engine.failure.collectAsStateWithLifecycle()
    var localFailure by remember { mutableStateOf<DeployIssue?>(null) }
    var lastPreflightOk by remember { mutableStateOf(false) }
    val failure = localFailure ?: engineFailure
    val saved = initial != null

    LaunchedEffect(initial?.id) {
        val t = initial ?: return@LaunchedEffect
        id = t.id
        name = t.name
        host = t.host
        sshPort = t.sshPort.toString()
        sshUser = deploySshUserOrRoot(t.sshUser)
        password = t.password
        privateKey = t.privateKeyPem
        keyPass = t.keyPassphrase
        publicHost = t.publicHost
        autoPorts = t.autoPorts
        directPort = t.directPort.toString()
        bypassPort = t.bypassPort.toString()
        osId = t.osId
        osVersion = t.osVersion
        lastDeployedAtMs = t.lastDeployedAtMs
        cascadeEnabled = t.cascadeEnabled
        cascadeHost = t.cascadeHost
        cascadePort = t.cascadePort.toString()
        cascadeUser = deploySshUserOrRoot(t.cascadeUser)
        cascadePassword = t.cascadePassword
        cascadePrivateKey = t.cascadePrivateKeyPem
        cascadeKeyPass = t.cascadeKeyPassphrase
    }

    LaunchedEffect(busy, activeTargetId, id) {
        if (busy && activeTargetId == id) {
            showDeployProgress = true
            deployStatus = null
            localFailure = null
        }
    }

    LaunchedEffect(busy, outcome, id, activeTargetId, engineFailure, engineIsPreflight) {
        if (busy || outcome == null) return@LaunchedEffect
        if (activeTargetId != null && activeTargetId != id) return@LaunchedEffect
        if (!showDeployProgress) return@LaunchedEffect
        deployStatus = outcome
        localFailure = engineFailure
        val fail = engineFailure
        if (engineIsPreflight && fail == null) {
            lastPreflightOk = true
        } else if (fail != null && !fail.isCancelled) {
            lastPreflightOk = false
        }
        val stored = serversRepo.snapshot().find { it.id == id }
        if (stored != null) {
            if (stored.lastDeployedAtMs > 0L) lastDeployedAtMs = stored.lastDeployedAtMs
            directPort = stored.directPort.toString()
            bypassPort = stored.bypassPort.toString()
        }
    }

    fun buildTarget(deployedAt: Long = lastDeployedAtMs): DeployTarget = DeployTarget(
        id = id,
        name = name.ifBlank { host },
        host = host.trim(),
        sshPort = sshPort.toIntOrNull() ?: 22,
        sshUser = deploySshUserOrRoot(sshUser),
        password = password,
        privateKeyPem = privateKey.trim(),
        keyPassphrase = keyPass,
        sudoPassword = password,
        publicHost = publicHost.trim().ifBlank { host.trim() },
        autoPorts = autoPorts,
        directPort = directPort.toIntOrNull() ?: 51820,
        bypassPort = bypassPort.toIntOrNull() ?: 56003,
        cascadeEnabled = cascadeEnabled,
        cascadeHost = cascadeHost.trim(),
        cascadePort = cascadePort.toIntOrNull() ?: 22,
        cascadeUser = deploySshUserOrRoot(cascadeUser),
        cascadePassword = cascadePassword,
        cascadePrivateKeyPem = cascadePrivateKey.trim(),
        cascadeKeyPassphrase = cascadeKeyPass,
        provisionPort = initial?.provisionPort ?: 9100,
        telemetryPort = initial?.telemetryPort ?: 9200,
        arch = initial?.arch.orEmpty(),
        cascadeProvisionPort = initial?.cascadeProvisionPort ?: 9100,
        cascadeTelemetryPort = initial?.cascadeTelemetryPort ?: 9200,
        cascadeArch = initial?.cascadeArch.orEmpty(),
        osId = osId.trim(),
        osVersion = osVersion.trim(),
        lastDeployedAtMs = deployedAt,
    )

    val context = LocalContext.current
    val expectedDeployVersion = remember(context) { DeployBundle.expectedVersion(context) }
    val isUpdate = saved && lastDeployedAtMs > 0L
    val canLeave = deployFormCanLeave(busy)

    fun formValidationError(): String? {
        if (host.isBlank()) return "Укажите host"
        if (deploySshSecretMissing(password, privateKey)) return "Нужен пароль или SSH-ключ"
        if (cascadeEnabled) {
            if (cascadeHost.isBlank()) return "Укажите host второго сервера"
            if (deploySshSecretMissing(cascadePassword, cascadePrivateKey)) {
                return "Нужен пароль или SSH-ключ второго сервера"
            }
        }
        return null
    }

    fun startServerDeploy(diskCleanup: Boolean = false) {
        formValidationError()?.let {
            status = it
            return
        }
        val target = buildTarget()
        serversRepo.upsert(target)
        status = null
        showDeployProgress = true
        deployStatus = null
        localFailure = null
        val kind = if (isUpdate) DeployJobKind.Update else DeployJobKind.Install
        if (!enqueueJob(target, kind, diskCleanup)) {
            localFailure = deployBusyIssue()
            deployStatus = localFailure?.summary
        }
    }

    fun startFormPreflight() {
        formValidationError()?.let {
            status = it
            return
        }
        val target = buildTarget()
        serversRepo.upsert(target)
        status = null
        showDeployProgress = true
        deployStatus = null
        localFailure = null
        if (!enqueueJob(target, DeployJobKind.Preflight, false)) {
            localFailure = deployBusyIssue()
            deployStatus = localFailure?.summary
        }
    }

    BackHandler(enabled = !canLeave) { }

    Box(modifier = Modifier.fillMaxSize()) {
        ArdttScrollChrome(
            header = {
                ArdttTabHeader(
                    title = serverDeployScreenTitle(saved),
                    subtitle = if (saved) {
                        "Стек $expectedDeployVersion · SSH · Compose"
                    } else {
                        "SSH · установка Compose-стека ARDTT"
                    },
                    onBack = if (canLeave) onBack else null,
                )
            },
        ) { topPad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(top = topPad)
                .padding(bottom = ArdttBottomChrome.navigationReserve() + ArdttSpacing.XXLarge),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
        ) {
        Text(
            serverDeployFormHelp(saved, cascadeEnabled, expectedDeployVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ArdttSpacing.Large),
        )

        Column(
            modifier = Modifier.padding(horizontal = ArdttSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
        ) {
        ArdttTextField(
            value = name,
            onValueChange = { name = it },
            label = "Имя сервера",
            enabled = !busy,
        )
        ArdttTextField(
            value = host,
            onValueChange = { host = it },
            label = "SSH host / IP",
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small), modifier = Modifier.fillMaxWidth()) {
            ArdttDigitsField(
                value = sshPort,
                onValueChange = { sshPort = it },
                label = "SSH порт",
                modifier = Modifier.weight(1f),
                maxLength = 5,
                enabled = !busy,
            )
            ArdttTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                label = "SSH user",
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
        ArdttPasswordField(
            value = password,
            onValueChange = { password = it },
            label = "Пароль (или sudo)",
            enabled = !busy,
        )
        ArdttTextField(
            value = privateKey,
            onValueChange = { privateKey = it },
            label = "SSH private key PEM (опционально)",
            modifier = Modifier.heightIn(min = 80.dp),
            singleLine = false,
            minLines = 3,
            enabled = !busy,
        )
        if (privateKey.isNotBlank()) {
            ArdttPasswordField(
                value = keyPass,
                onValueChange = { keyPass = it },
                label = "Passphrase ключа",
                enabled = !busy,
            )
        }
        ArdttTextField(
            value = publicHost,
            onValueChange = { publicHost = it },
            label = "Публичный host для профиля",
            placeholder = "Как в ARDTT_PUBLIC_HOST, обычно = IP",
            enabled = !busy,
        )
        ArdttSectionCard(
            contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus),
            shape = ArdttShapes.Chip,
        ) {
            ArdttSwitchRow(
                title = "Автовыбор портов",
                subtitle = "Свободные UDP Direct / Bypass на VPS. Выключите, чтобы задать порты вручную.",
                checked = autoPorts,
                onCheckedChange = { autoPorts = it },
                enabled = !busy,
            )
            if (!autoPorts) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ArdttDigitsField(
                        value = directPort,
                        onValueChange = { directPort = it },
                        label = "Direct UDP",
                        modifier = Modifier.weight(1f),
                        maxLength = 5,
                        enabled = !busy,
                    )
                    ArdttDigitsField(
                        value = bypassPort,
                        onValueChange = { bypassPort = it },
                        label = "Bypass UDP",
                        modifier = Modifier.weight(1f),
                        maxLength = 5,
                        enabled = !busy,
                    )
                }
            }
        }

        ArdttSectionCard(
            contentPadding = PaddingValues(horizontal = ArdttSpacing.MediumPlus, vertical = ArdttSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus),
            shape = ArdttShapes.Chip,
        ) {
            ArdttSwitchRow(
                title = "Каскадное подключение",
                subtitle = cascadeDeploySwitchSubtitle(),
                checked = cascadeEnabled,
                onCheckedChange = { on ->
                    cascadeEnabled = on
                    if (on) cascadeUser = deploySshUserOrRoot(cascadeUser)
                },
                enabled = !busy,
            )
            if (cascadeEnabled) {
                ArdttTextField(
                    value = cascadeHost,
                    onValueChange = { cascadeHost = it },
                    label = "Выход host / IP",
                    placeholder = cascadeExitHostPlaceholder(),
                    enabled = !busy,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ArdttDigitsField(
                        value = cascadePort,
                        onValueChange = { cascadePort = it },
                        label = "SSH порт",
                        modifier = Modifier.weight(1f),
                        maxLength = 5,
                        enabled = !busy,
                    )
                    ArdttTextField(
                        value = cascadeUser,
                        onValueChange = { cascadeUser = it },
                        label = "SSH user",
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
                ArdttPasswordField(
                    value = cascadePassword,
                    onValueChange = { cascadePassword = it },
                    label = "Пароль (или sudo)",
                    enabled = !busy,
                )
                ArdttTextField(
                    value = cascadePrivateKey,
                    onValueChange = { cascadePrivateKey = it },
                    label = "SSH private key PEM (опционально)",
                    modifier = Modifier.heightIn(min = 80.dp),
                    singleLine = false,
                    minLines = 3,
                    enabled = !busy,
                )
                if (cascadePrivateKey.isNotBlank()) {
                    ArdttPasswordField(
                        value = cascadeKeyPass,
                        onValueChange = { cascadeKeyPass = it },
                        label = "Passphrase ключа",
                        enabled = !busy,
                    )
                }
            }
        }

        ArdttButton(
            text = "Сохранить сервер",
            onClick = {
                formValidationError()?.let {
                    status = it
                    return@ArdttButton
                }
                val target = buildTarget()
                serversRepo.upsert(target)
                status = "Сервер сохранён"
                onSaved(target.id)
            },
            enabled = !busy,
            variant = ArdttButtonVariant.Outlined,
            fillMaxWidth = true,
        )

        ArdttButton(
            text = serverDeployActionLabel(saved, cascadeEnabled),
            onClick = {
                formValidationError()?.let {
                    status = it
                    return@ArdttButton
                }
                if (saved) {
                    status = null
                    showReinstallConfirm = true
                } else {
                    startServerDeploy()
                }
            },
            enabled = !busy,
            variant = ArdttButtonVariant.Primary,
            fillMaxWidth = true,
            icon = Icons.Filled.CloudUpload,
        )

        if (canLeave) {
            ArdttButton(
                text = "Назад",
                onClick = onBack,
                variant = ArdttButtonVariant.Outlined,
                fillMaxWidth = true,
            )
        }

        status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
            } // form column
        }
        }

        if (showReinstallConfirm) {
            ArdttDialog(
                title = "Переустановить деплой?",
                onDismissRequest = { if (!busy) showReinstallConfirm = false },
                confirmAction = ArdttDialogAction(
                    text = "Переустановить",
                    onClick = {
                        showReinstallConfirm = false
                        startServerDeploy()
                    },
                    enabled = !busy,
                ),
                dismissAction = ArdttDialogAction(
                    text = "Отмена",
                    onClick = { showReinstallConfirm = false },
                    enabled = !busy,
                ),
                dismissOnBackPress = !busy,
                dismissOnClickOutside = !busy,
            ) {
                Text(
                    serverReinstallConfirmBody(host, expectedDeployVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDeployProgress) {
            DeployProgressSheet(
                busy = busy,
                isUpdate = if (busy) engineIsUpdate else isUpdate,
                isPreflight = if (busy) engineIsPreflight else false,
                status = deployStatus,
                step = step,
                progress = progress,
                log = log,
                hopTrack = hopTrack,
                failure = failure,
                onRetryPreflight = { startFormPreflight() },
                onRetryInstall = { startServerDeploy() },
                onDiskCleanupRetry = { startServerDeploy(diskCleanup = true) },
                retryInstallEnabled = !busy,
                onCancel = { engine.cancel() },
                onClose = { showDeployProgress = false },
            )
        }
    }
}

private fun serverOsMarkDrawable(mark: ServerOsMark): Int = when (mark) {
    // Named OS art stays on its own mark. Do not reuse these files for another distro.
    ServerOsMark.Ubuntu -> R.drawable.ic_os_ubuntu
    ServerOsMark.Debian -> R.drawable.ic_os_debian
    ServerOsMark.Fedora -> R.drawable.ic_os_fedora
    ServerOsMark.Alpine -> R.drawable.ic_os_alpine
    ServerOsMark.Arch -> R.drawable.ic_os_arch
    ServerOsMark.Centos -> R.drawable.ic_os_centos
    ServerOsMark.Rhel -> R.drawable.ic_os_rhel
    ServerOsMark.Suse -> R.drawable.ic_os_suse
    ServerOsMark.Linux -> R.drawable.ic_os_linux
    ServerOsMark.Unknown -> R.drawable.ic_os_unknown
}

@Composable
private fun ServerOsBadge(
    osId: String,
    osVersion: String,
) {
    val mark = serverOsMark(osId)
    val label = serverOsBadgeLabel(osId)
    val version = serverOsBadgeVersionText(osId, osVersion)
    val description = listOfNotNull(label, version).joinToString(" ")
    Surface(
        modifier = Modifier.widthIn(max = 200.dp),
        shape = ArdttShapes.Badge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = ArdttSpacing.Small, vertical = 3.dp),
        ) {
            Image(
                painter = painterResource(serverOsMarkDrawable(mark)),
                contentDescription = description,
                modifier = Modifier.size(ArdttSize.IconSmall),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (version != null) {
                Text(
                    version,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}
