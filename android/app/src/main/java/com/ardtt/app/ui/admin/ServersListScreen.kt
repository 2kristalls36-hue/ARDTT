package com.ardtt.app.ui.admin

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.R
import com.ardtt.app.core.needsNotificationPermission
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.DeployJobKind
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
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttOverflowMenu
import com.ardtt.app.ui.components.control.ArdttOverflowMenuItem
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.feedback.ArdttEmptyState
import com.ardtt.app.ui.components.feedback.ArdttIpChip
import com.ardtt.app.ui.components.feedback.ArdttIpHostRow
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
import com.ardtt.app.ui.theme.ArdttAlpha
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.util.readClipboardText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


@Composable
internal fun rememberEnqueueDeploy(
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
        // One state write for the whole batch instead of one list recomposition per server.
        val results = coroutineScope {
            snapshot.map { target ->
                async { target.id to probeServerHealthUi(target, serversRepo) }
            }.awaitAll()
        }
        healthById = healthById + results
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
internal fun ServerCard(
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
private object ServerOsBadgeDefaults {
    /** Long distro names + version still leave room for the server title. */
    val MaxWidth = 200.dp
    /** Same vertical padding as ArdttStatusChip: below the spacing scale on purpose. */
    val VerticalPadding = 3.dp
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
        modifier = Modifier.widthIn(max = ServerOsBadgeDefaults.MaxWidth),
        shape = ArdttShapes.Badge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = ArdttAlpha.FillSoft),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Tiny),
            modifier = Modifier.padding(
                horizontal = ArdttSpacing.Small,
                vertical = ServerOsBadgeDefaults.VerticalPadding,
            ),
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
