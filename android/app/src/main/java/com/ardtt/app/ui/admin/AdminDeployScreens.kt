package com.ardtt.app.ui.admin

import android.Manifest
import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.R
import com.ardtt.app.core.needsNotificationPermission
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.ServerOsMark
import com.ardtt.app.deploy.ServerOsProbe
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.deploy.serverOsBadgeLabel
import com.ardtt.app.deploy.serverOsMark
import com.ardtt.app.profile.NetworkEndpoint
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.components.TabFeedHeader
import com.ardtt.app.ui.components.TabHeaderMetrics
import com.ardtt.app.ui.components.AppSectionCard
import com.ardtt.app.ui.components.CompactListCard
import com.ardtt.app.ui.components.CompactListLeadingIcon
import com.ardtt.app.ui.components.OverflowMenu
import com.ardtt.app.ui.components.OverflowMenuItem
import com.ardtt.app.ui.components.ArdttBottomChrome
import com.ardtt.app.ui.components.ArdttDialog
import com.ardtt.app.ui.components.ArdttDialogAction
import com.ardtt.app.ui.components.PullRefreshHost
import com.ardtt.app.ui.components.StickyPrimaryButton
import com.ardtt.app.ui.components.TerminalLogCard
import com.ardtt.app.ui.components.rememberPullRefresh
import com.ardtt.app.ui.theme.ArdttColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Composable
private fun rememberStartDeploy(engine: DeployEngine): (DeployTarget, Boolean) -> Boolean {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<Pair<DeployTarget, Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val job = pending.value ?: return@rememberLauncherForActivityResult
        pending.value = null
        engine.enqueue(job.first, job.second)
    }
    return { target, isUpdate ->
        if (needsNotificationPermission(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            pending.value = target to isUpdate
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            true
        } else {
            engine.enqueue(target, isUpdate)
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

private fun healthStatusLine(
    health: HealthUi?,
    lastDeployedAtMs: Long,
    expectedVersion: String,
): Pair<String, Color?> {
    val text = healthStatusLabel(health, lastDeployedAtMs)
    val hint = when (health) {
        is HealthUi.Online ->
            if (DeployBundle.isCurrent(health.deployVersion, expectedVersion)) {
                ArdttColors.connected
            } else {
                ArdttColors.warning
            }
        else -> null
    }
    return text to hint
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
                    val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target))
                        .getOrNull()
                    val status = healthUiOf(info)
                    ServerOsProbe.refreshStored(serversRepo, target)
                    healthById = healthById + (target.id to status)
                }
            }.awaitAll()
        }
    }

    val serverIds = remember(servers) { servers.map { it.id }.joinToString(",") }
    LaunchedEffect(serverIds) {
        probeAll()
    }

    val pull = rememberPullRefresh {
        if (servers.isNotEmpty()) probeAll()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullRefreshHost(
            refreshing = pull.refreshing,
            onRefresh = pull.onRefresh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                TabFeedHeader(
                    title = "Управление серверами",
                )

                if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 340.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Filled.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.height(26.dp))
                            Text(
                                "Добавьте первый сервер, чтобы установить стек и управлять пользователями",
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = ArdttBottomChrome.scrollContentPadding()),
                        verticalArrangement = Arrangement.spacedBy(CompactListCard.ListSpacing),
                    ) {
                        items(servers, key = { it.id }) { server ->
                            ServerCard(
                                server = server,
                                health = healthById[server.id],
                                expectedVersion = expectedVersion,
                                onOpenServer = { onOpenServer(server.id) },
                            )
                        }
                    }
                }
            }
        }

        StickyPrimaryButton(
            text = "Добавить сервер",
            onClick = onAddServer,
            icon = Icons.Filled.Add,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = ArdttBottomChrome.stickyBottomPadding()),
        )
    }
}

@Composable
private fun ServerCard(
    server: DeployTarget,
    health: HealthUi?,
    expectedVersion: String,
    onOpenServer: () -> Unit,
) {
    AppSectionCard(
        modifier = Modifier.clickable(onClick = onOpenServer),
        contentPadding = CompactListCard.ContentPadding,
        verticalArrangement = Arrangement.spacedBy(CompactListCard.ItemSpacing),
        shape = CompactListCard.Shape,
        shadowElevation = CompactListCard.ShadowElevation,
        tonalElevation = 0.dp,
        showBorder = false,
    ) {
        ServerIdentityBody(
            server = server,
            health = health,
            expectedVersion = expectedVersion,
        )
    }
}

@Composable
private fun ServerIdentityBody(
    server: DeployTarget,
    health: HealthUi?,
    expectedVersion: String,
    extraLines: List<String> = emptyList(),
) {
    val (statusText, statusColorHint) = healthStatusLine(health, server.lastDeployedAtMs, expectedVersion)
    val statusColor = when {
        statusColorHint != null -> statusColorHint
        health == HealthUi.Offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val title = serverCardTitle(server.name, server.host)
    val meta = serverCardMetaLine(server.name, server.host, server.sshPort, server.publicHost)
    val freshnessChip = deployFreshnessChipText(health, expectedVersion)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactListLeadingIcon(
            imageVector = Icons.Filled.Dns,
            contentDescription = "Сервер",
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CompactListCard.ItemSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ServerOsBadge(
                    osId = server.osId,
                    osVersion = server.osVersion,
                )
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            extraLines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            freshnessChip?.let { chip ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ArdttColors.warning.copy(alpha = 0.18f),
                ) {
                    Text(
                        chip,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ArdttColors.warning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
) {
    ArdttDialog(
        title = deployProgressSheetTitle(busy, isUpdate, status),
        onDismissRequest = {},
        confirmAction = if (busy) {
            ArdttDialogAction("Отменить", onCancel, destructive = true)
        } else {
            ArdttDialogAction("Закрыть", onClose)
        },
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
    ) {
        Text(
            step.ifBlank { "…" },
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            Text(
                it,
                color = if (it.startsWith("Ошибка")) {
                    MaterialTheme.colorScheme.error
                } else {
                    ArdttColors.connected
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Лог",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        TerminalLogCard(
            text = log.takeLast(24).joinToString("\n"),
            maxHeight = 240.dp,
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRedeployConfirm by remember { mutableStateOf(false) }
    var showRedeployProgress by remember { mutableStateOf(false) }
    var redeployStatus by remember { mutableStateOf<String?>(null) }
    var health by remember { mutableStateOf<HealthUi?>(HealthUi.Checking) }
    val context = LocalContext.current
    val expectedVersion = remember(context) { DeployBundle.expectedVersion(context) }
    val startDeploy = rememberStartDeploy(engine)
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val deployLog by engine.log.collectAsStateWithLifecycle()
    val outcome by engine.outcome.collectAsStateWithLifecycle()
    val activeTargetId by engine.activeTargetId.collectAsStateWithLifecycle()

    LaunchedEffect(servers, serverId) {
        if (servers.isNotEmpty() && server == null) onBack()
    }

    LaunchedEffect(serverId, server?.host, server?.publicHost) {
        val target = server ?: return@LaunchedEffect
        health = HealthUi.Checking
        val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target)).getOrNull()
        health = healthUiOf(info)
        ServerOsProbe.refreshStored(serversRepo, target)
    }

    LaunchedEffect(busy, activeTargetId, serverId) {
        if (busy && activeTargetId == serverId) {
            showRedeployProgress = true
            redeployStatus = null
        }
    }

    LaunchedEffect(busy, outcome, serverId, activeTargetId) {
        if (busy || outcome == null) return@LaunchedEffect
        if (activeTargetId != null && activeTargetId != serverId) return@LaunchedEffect
        if (!showRedeployProgress) return@LaunchedEffect
        redeployStatus = outcome
        val target = server ?: return@LaunchedEffect
        health = HealthUi.Checking
        val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target)).getOrNull()
        health = healthUiOf(info)
    }

    fun startRedeploy(target: DeployTarget) {
        showRedeployConfirm = false
        showRedeployProgress = true
        redeployStatus = null
        if (!startDeploy(target, true)) {
            redeployStatus = "Ошибка: деплой уже идёт"
        }
    }

    val pull = rememberPullRefresh {
        val target = server ?: return@rememberPullRefresh
        val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target)).getOrNull()
        health = healthUiOf(info)
        ServerOsProbe.refreshStored(serversRepo, target)
    }

    if (server == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        ServerOverviewScreen(
            server = server,
            health = health,
            expectedVersion = expectedVersion,
            onOpenClients = onOpenClients,
            onUpdateDeploy = { showRedeployConfirm = true },
            onOpenDeploySettings = onOpenDeploySettings,
            onBack = onBack,
            showActions = showActions,
            onShowActions = { showActions = it },
            onRename = { showRename = true },
            onDelete = { showDeleteConfirm = true },
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
        if (showDeleteConfirm) {
            ArdttDialog(
                title = "Удалить сервер?",
                onDismissRequest = { showDeleteConfirm = false },
                confirmAction = ArdttDialogAction(
                    text = "Удалить",
                    onClick = {
                        serversRepo.delete(server.id)
                        showDeleteConfirm = false
                        onBack()
                    },
                    destructive = true,
                ),
                dismissAction = ArdttDialogAction("Отмена", { showDeleteConfirm = false }),
            ) {
                Text(
                    "Из приложения будут удалены только данные подключения. " +
                        "Сервер и пользователи на VPS останутся без изменений.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showRedeployConfirm) {
            ArdttDialog(
                title = "Обновить деплой?",
                onDismissRequest = { if (!busy) showRedeployConfirm = false },
                confirmAction = ArdttDialogAction(
                    text = "Обновить",
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
                    "Стек версии $expectedVersion будет заново залит на ${server.host} " +
                        "по сохранённым SSH-данным. Параметры подключения менять не нужно.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showRedeployProgress) {
            DeployProgressSheet(
                busy = busy,
                isUpdate = true,
                status = redeployStatus,
                step = step,
                progress = progress,
                log = deployLog,
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
    onOpenDeploySettings: () -> Unit,
    onBack: () -> Unit,
    showActions: Boolean,
    onShowActions: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val showUpdateButton = shouldShowUpdateDeployButton(health, expectedVersion)

    Box(modifier = Modifier.fillMaxSize()) {
        PullRefreshHost(
            refreshing = refreshing,
            onRefresh = onRefresh,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = TabHeaderMetrics.HorizontalPadding)) {
                    TabFeedHeader(
                        title = server.name.ifBlank { server.host },
                        subtitle = "Управление сервером",
                        onBack = onBack,
                    actions = {
                        Box {
                            IconButton(onClick = { onShowActions(true) }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Действия с сервером",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            OverflowMenu(
                                expanded = showActions,
                                onDismissRequest = { onShowActions(false) },
                            ) {
                                OverflowMenuItem(
                                    text = "Обновить деплой",
                                    leadingIcon = Icons.Filled.CloudUpload,
                                    onClick = {
                                        onShowActions(false)
                                        onUpdateDeploy()
                                    },
                                )
                                OverflowMenuItem(
                                    text = "Переименовать",
                                    leadingIcon = Icons.Filled.Edit,
                                    onClick = {
                                        onShowActions(false)
                                        onRename()
                                    },
                                )
                                OverflowMenuItem(
                                    text = "Удалить",
                                    leadingIcon = Icons.Filled.Delete,
                                    destructive = true,
                                    onClick = {
                                        onShowActions(false)
                                        onDelete()
                                    },
                                )
                            }
                        }
                    },
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = if (showUpdateButton) {
                            ArdttBottomChrome.scrollContentPadding()
                        } else {
                            ArdttBottomChrome.navigationReserve() + 16.dp
                        },
                    ),
                    verticalArrangement = Arrangement.spacedBy(CompactListCard.ListSpacing),
                ) {
            item {
                AppSectionCard(
                    contentPadding = CompactListCard.ContentPadding,
                    verticalArrangement = Arrangement.spacedBy(CompactListCard.ItemSpacing),
                    shape = CompactListCard.Shape,
                    shadowElevation = CompactListCard.ShadowElevation,
                    tonalElevation = 0.dp,
                    showBorder = false,
                ) {
                    ServerIdentityBody(
                        server = server,
                        health = health,
                        expectedVersion = expectedVersion,
                        extraLines = listOf(
                            "Direct ${server.directPort}  ·  Bypass ${server.bypassPort}",
                        ),
                    )
                }
            }
            item {
                Text(
                    "Действия",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
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

        if (showUpdateButton) {
            StickyPrimaryButton(
                text = "Обновить деплой",
                onClick = onUpdateDeploy,
                containerColor = ArdttColors.warning,
                icon = Icons.Filled.CloudUpload,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = ArdttBottomChrome.stickyBottomPadding()),
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
    AppSectionCard(
        modifier = Modifier.clickable(onClick = onClick),
        contentPadding = CompactListCard.ContentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        shape = CompactListCard.Shape,
        shadowElevation = CompactListCard.ShadowElevation,
        tonalElevation = 0.dp,
        showBorder = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
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
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя сервера") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
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
    val startDeploy = rememberStartDeploy(engine)
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()
    val outcome by engine.outcome.collectAsStateWithLifecycle()

    var id by remember { mutableStateOf(initial?.id ?: serversRepo.newId()) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var sshPort by remember { mutableStateOf((initial?.sshPort ?: 22).toString()) }
    var sshUser by remember { mutableStateOf(initial?.sshUser ?: "root") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember { mutableStateOf(initial?.privateKeyPem ?: "") }
    var keyPass by remember { mutableStateOf(initial?.keyPassphrase ?: "") }
    var publicHost by remember { mutableStateOf(initial?.publicHost ?: "") }
    var directPort by remember { mutableStateOf((initial?.directPort ?: 51820).toString()) }
    var bypassPort by remember { mutableStateOf((initial?.bypassPort ?: 56003).toString()) }
    var osId by remember { mutableStateOf(initial?.osId ?: "") }
    var osVersion by remember { mutableStateOf(initial?.osVersion ?: "") }
    var lastDeployedAtMs by remember { mutableStateOf(initial?.lastDeployedAtMs ?: 0L) }
    var cascadeEnabled by remember { mutableStateOf(initial?.cascadeEnabled == true) }
    var cascadeHost by remember { mutableStateOf(initial?.cascadeHost ?: "") }
    var cascadePort by remember { mutableStateOf((initial?.cascadePort ?: 22).toString()) }
    var cascadeUser by remember { mutableStateOf(initial?.cascadeUser ?: "") }
    var cascadePassword by remember { mutableStateOf(initial?.cascadePassword ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var deployStatus by remember { mutableStateOf<String?>(null) }
    var showReinstallConfirm by remember { mutableStateOf(false) }
    var showDeployProgress by remember { mutableStateOf(false) }
    val activeTargetId by engine.activeTargetId.collectAsStateWithLifecycle()
    val engineIsUpdate by engine.isUpdate.collectAsStateWithLifecycle()
    val saved = initial != null

    LaunchedEffect(initial?.id) {
        val t = initial ?: return@LaunchedEffect
        id = t.id
        name = t.name
        host = t.host
        sshPort = t.sshPort.toString()
        sshUser = t.sshUser
        password = t.password
        privateKey = t.privateKeyPem
        keyPass = t.keyPassphrase
        publicHost = t.publicHost
        directPort = t.directPort.toString()
        bypassPort = t.bypassPort.toString()
        osId = t.osId
        osVersion = t.osVersion
        lastDeployedAtMs = t.lastDeployedAtMs
        cascadeEnabled = t.cascadeEnabled
        cascadeHost = t.cascadeHost
        cascadePort = t.cascadePort.toString()
        cascadeUser = t.cascadeUser
        cascadePassword = t.cascadePassword
    }

    LaunchedEffect(busy, activeTargetId, id) {
        if (busy && activeTargetId == id) {
            showDeployProgress = true
            deployStatus = null
        }
    }

    LaunchedEffect(busy, outcome, id, activeTargetId) {
        if (busy || outcome == null) return@LaunchedEffect
        if (activeTargetId != null && activeTargetId != id) return@LaunchedEffect
        if (!showDeployProgress) return@LaunchedEffect
        deployStatus = outcome
        val deployedAt = serversRepo.snapshot().find { it.id == id }?.lastDeployedAtMs
        if (deployedAt != null && deployedAt > 0L) lastDeployedAtMs = deployedAt
    }

    fun buildTarget(deployedAt: Long = lastDeployedAtMs): DeployTarget = DeployTarget(
        id = id,
        name = name.ifBlank { host },
        host = host.trim(),
        sshPort = sshPort.toIntOrNull() ?: 22,
        sshUser = sshUser.trim().ifBlank { "root" },
        password = password,
        privateKeyPem = privateKey.trim(),
        keyPassphrase = keyPass,
        sudoPassword = password,
        publicHost = publicHost.trim().ifBlank { host.trim() },
        directPort = directPort.toIntOrNull() ?: 51820,
        bypassPort = bypassPort.toIntOrNull() ?: 56003,
        cascadeEnabled = cascadeEnabled,
        cascadeHost = cascadeHost.trim(),
        cascadePort = cascadePort.toIntOrNull() ?: 22,
        cascadeUser = cascadeUser.trim(),
        cascadePassword = cascadePassword,
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
        if (password.isBlank() && privateKey.isBlank()) return "Нужен пароль или SSH-ключ"
        if (cascadeEnabled) {
            if (cascadeHost.isBlank()) return "Укажите host второго сервера"
            if (cascadeUser.isBlank()) return "Укажите SSH user второго сервера"
            if (cascadePassword.isBlank()) return "Укажите пароль второго сервера"
        }
        return null
    }

    fun startServerDeploy() {
        formValidationError()?.let {
            status = it
            return
        }
        val target = buildTarget()
        serversRepo.upsert(target)
        status = null
        showDeployProgress = true
        deployStatus = null
        if (!startDeploy(target, isUpdate)) {
            deployStatus = "Ошибка: деплой уже идёт"
        }
    }

    BackHandler(enabled = !canLeave) { }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Column(Modifier.padding(horizontal = TabHeaderMetrics.HorizontalPadding)) {
            TabFeedHeader(
                title = serverDeployScreenTitle(saved),
                subtitle = if (saved) {
                    "Стек $expectedDeployVersion · SSH · Compose"
                } else {
                    "SSH · установка Compose-стека ARDTT"
                },
                onBack = if (canLeave) onBack else null,
            )
        }
        Text(
            serverDeployFormHelp(saved, cascadeEnabled, expectedDeployVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя сервера") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("SSH host / IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("SSH порт") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                label = { Text("SSH user") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !busy,
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль (или sudo)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = privateKey,
            onValueChange = { privateKey = it },
            label = { Text("SSH private key PEM (опционально)") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp),
            minLines = 3,
            enabled = !busy,
        )
        if (privateKey.isNotBlank()) {
            OutlinedTextField(
                value = keyPass,
                onValueChange = { keyPass = it },
                label = { Text("Passphrase ключа") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
            )
        }
        OutlinedTextField(
            value = publicHost,
            onValueChange = { publicHost = it },
            label = { Text("Публичный host для профиля") },
            placeholder = { Text("Как в ARDTT_PUBLIC_HOST, обычно = IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = directPort,
                onValueChange = { directPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Direct UDP") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = bypassPort,
                onValueChange = { bypassPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Bypass UDP") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
        }

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Каскадное подключение",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Второй сервер — выход в интернет и WARP. Телефон ставит его отдельно; клиенты живут на первом.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = cascadeEnabled,
                    onCheckedChange = { cascadeEnabled = it },
                    enabled = !busy,
                )
            }
            if (cascadeEnabled) {
                OutlinedTextField(
                    value = cascadeHost,
                    onValueChange = { cascadeHost = it },
                    label = { Text("Выход host / IP") },
                    placeholder = { Text("Второй VPS, WARP") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = cascadePort,
                        onValueChange = { cascadePort = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("SSH порт") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cascadeUser,
                        onValueChange = { cascadeUser = it },
                        label = { Text("SSH user") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = cascadePassword,
                    onValueChange = { cascadePassword = it },
                    label = { Text("SSH пароль выхода") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        OutlinedButton(
            onClick = {
                formValidationError()?.let {
                    status = it
                    return@OutlinedButton
                }
                val target = buildTarget()
                serversRepo.upsert(target)
                status = "Сервер сохранён"
                onSaved(target.id)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить сервер")
        }

        Button(
            onClick = {
                formValidationError()?.let {
                    status = it
                    return@Button
                }
                if (saved) {
                    status = null
                    showReinstallConfirm = true
                } else {
                    startServerDeploy()
                }
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(
                Icons.Filled.CloudUpload,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                serverDeployActionLabel(saved, cascadeEnabled),
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (canLeave) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Назад")
            }
        }

        status?.let {
            Text(
                it,
                color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
            } // form column
        }

        if (showReinstallConfirm) {
            ArdttDialog(
                title = "Переустановить сервер?",
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
                status = deployStatus,
                step = step,
                progress = progress,
                log = log,
                onCancel = { engine.cancel() },
                onClose = { showDeployProgress = false },
            )
        }
    }
}

private fun serverOsMarkDrawable(mark: ServerOsMark): Int = when (mark) {
    ServerOsMark.Ubuntu -> R.drawable.ic_os_ubuntu
    ServerOsMark.Debian -> R.drawable.ic_os_debian
    ServerOsMark.Fedora -> R.drawable.ic_os_fedora
    ServerOsMark.Alpine -> R.drawable.ic_os_alpine
    ServerOsMark.Arch -> R.drawable.ic_os_arch
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
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Image(
                painter = painterResource(serverOsMarkDrawable(mark)),
                contentDescription = description,
                modifier = Modifier.size(16.dp),
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
