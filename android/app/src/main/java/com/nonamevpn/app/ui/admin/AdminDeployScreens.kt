package com.nonamevpn.app.ui.admin

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.deploy.DeployBundle
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.NetworkEndpoint
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.EdgeFeedTopInset
import com.nonamevpn.app.ui.components.NvpnBottomChrome
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import com.nonamevpn.app.ui.components.StickyPrimaryButton
import com.nonamevpn.app.ui.theme.NvpnColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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

private fun formatDeployRelative(ms: Long): String {
    if (ms <= 0L) return ""
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        minutes < 1L -> "только что"
        minutes < 60L -> "$minutes мин назад"
        hours < 24L -> "$hours ч назад"
        days < 30L -> "$days дн назад"
        else -> SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(ms))
    }
}

private fun healthStatusLine(
    health: HealthUi?,
    lastDeployedAtMs: Long,
    expectedVersion: String,
): Pair<String, Color?> {
    val relative = if (lastDeployedAtMs > 0L) formatDeployRelative(lastDeployedAtMs) else ""
    val text = healthStatusLabel(health, lastDeployedAtMs, relative)
    val hint = when (health) {
        is HealthUi.Online ->
            if (DeployBundle.isCurrent(health.deployVersion, expectedVersion)) {
                NvpnColors.connected
            } else {
                NvpnColors.warning
            }
        else -> null
    }
    return text to hint
}

/** Green = current stack; orange = online but outdated; null = no special border. */
private fun deployFreshnessBorder(
    health: HealthUi?,
    isActiveDeploy: Boolean,
    expectedVersion: String,
): BorderStroke? {
    if (!isActiveDeploy) return null
    val online = health as? HealthUi.Online ?: return BorderStroke(2.dp, NvpnColors.warning)
    return if (DeployBundle.isCurrent(online.deployVersion, expectedVersion)) {
        BorderStroke(2.dp, NvpnColors.connected)
    } else {
        BorderStroke(2.dp, NvpnColors.warning)
    }
}

@Composable
fun ServersScreen(
    serversRepo: ServersRepository,
    engine: DeployEngine,
    profiles: ProfileRepository,
) {
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val activeDeployServerId = remember(servers, profile) {
        findActiveDeployServerId(servers, activeProfileHost(profile))
    }
    var screen by rememberSaveable(stateSaver = ServersNavScreenSaver) {
        mutableStateOf<ServersNavScreen>(ServersNavScreen.List)
    }

    BackHandler(enabled = screen !is ServersNavScreen.List) {
        screen = when (val current = screen) {
            is ServersNavScreen.Clients -> ServersNavScreen.Overview(current.serverId)
            is ServersNavScreen.Deploy -> current.serverId
                ?.let { ServersNavScreen.Overview(it) }
                ?: ServersNavScreen.List
            is ServersNavScreen.Overview -> ServersNavScreen.List
            is ServersNavScreen.List -> ServersNavScreen.List
        }
    }

    Crossfade(targetState = screen, label = "servers_nav") { current ->
        when (val s = current) {
            is ServersNavScreen.List -> ServerListScreen(
                servers = servers,
                serversRepo = serversRepo,
                activeDeployServerId = activeDeployServerId,
                onOpenServer = { id -> screen = ServersNavScreen.Overview(id) },
                onAddServer = { screen = ServersNavScreen.Deploy(null) },
            )
            is ServersNavScreen.Overview -> ServerOverviewHost(
                servers = servers,
                serversRepo = serversRepo,
                engine = engine,
                serverId = s.serverId,
                isActiveDeploy = s.serverId == activeDeployServerId,
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
    activeDeployServerId: String?,
    onOpenServer: (String) -> Unit,
    onAddServer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val expectedVersion = remember(context) { DeployBundle.expectedVersion(context) }
    var healthById by remember { mutableStateOf<Map<String, HealthUi>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }

    fun probeAll() {
        val snapshot = serversRepo.snapshot()
        if (snapshot.isEmpty()) {
            healthById = emptyMap()
            probing = false
            return
        }
        probing = true
        healthById = snapshot.associate { it.id to HealthUi.Checking }
        scope.launch {
            coroutineScope {
                snapshot.map { target ->
                    async {
                        val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target))
                            .getOrNull()
                        val status = if (info?.ok == true) {
                            HealthUi.Online(info.deployVersion)
                        } else {
                            HealthUi.Offline
                        }
                        healthById = healthById + (target.id to status)
                    }
                }.awaitAll()
            }
            probing = false
        }
    }

    val serverIds = remember(servers) { servers.map { it.id }.joinToString(",") }
    LaunchedEffect(serverIds) {
        probeAll()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            AppTabPageHeader(
                title = "Управление серверами",
                actions = {
                    IconButton(
                        onClick = { probeAll() },
                        enabled = !probing && servers.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Обновить статус",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = NvpnBottomChrome.scrollContentPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            health = healthById[server.id],
                            isActiveDeploy = server.id == activeDeployServerId,
                            expectedVersion = expectedVersion,
                            onOpenServer = { onOpenServer(server.id) },
                        )
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
                .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
        )

    }
}

@Composable
private fun ServerCard(
    server: DeployTarget,
    health: HealthUi?,
    isActiveDeploy: Boolean,
    expectedVersion: String,
    onOpenServer: () -> Unit,
) {
    val (statusText, statusColorHint) = healthStatusLine(health, server.lastDeployedAtMs, expectedVersion)
    val statusColor = when {
        statusColorHint != null -> statusColorHint
        health == HealthUi.Offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val activeBorder = deployFreshnessBorder(health, isActiveDeploy, expectedVersion)

    AppSectionCard(
        modifier = Modifier.clickable(onClick = onOpenServer),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        border = activeBorder,
        shadowElevation = 0.dp,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(11.dp)
                        .size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name.ifBlank { server.host },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.name.isNotBlank() && server.name != server.host) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        server.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "SSH ${server.sshPort}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                deployFreshnessChipText(health, expectedVersion)?.let { chip ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = NvpnColors.warning.copy(alpha = 0.18f),
                    ) {
                        Text(
                            chip,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = NvpnColors.warning,
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Открыть сервер",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerOverviewHost(
    servers: List<DeployTarget>,
    serversRepo: ServersRepository,
    engine: DeployEngine,
    serverId: String,
    isActiveDeploy: Boolean,
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
    val scope = rememberCoroutineScope()
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val deployLog by engine.log.collectAsStateWithLifecycle()

    LaunchedEffect(servers, serverId) {
        if (servers.isNotEmpty() && server == null) onBack()
    }

    LaunchedEffect(serverId, server?.host, server?.publicHost) {
        val target = server ?: return@LaunchedEffect
        health = HealthUi.Checking
        val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target)).getOrNull()
        health = if (info?.ok == true) HealthUi.Online(info.deployVersion) else HealthUi.Offline
    }

    fun refreshHealth(target: DeployTarget) {
        scope.launch {
            health = HealthUi.Checking
            val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target)).getOrNull()
            health = if (info?.ok == true) HealthUi.Online(info.deployVersion) else HealthUi.Offline
        }
    }

    fun startRedeploy(target: DeployTarget) {
        showRedeployConfirm = false
        showRedeployProgress = true
        redeployStatus = null
        scope.launch {
            val result = engine.deploy(target)
            result.fold(
                onSuccess = { msg ->
                    val deployedAt = System.currentTimeMillis()
                    serversRepo.upsert(target.copy(lastDeployedAtMs = deployedAt))
                    redeployStatus = msg
                    refreshHealth(target)
                },
                onFailure = { e ->
                    redeployStatus = "Ошибка: ${e.message}"
                },
            )
        }
    }

    if (server == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        ServerOverviewScreen(
            server = server,
            health = health,
            isActiveDeploy = isActiveDeploy,
            expectedVersion = expectedVersion,
            onOpenClients = onOpenClients,
            onUpdateDeploy = { showRedeployConfirm = true },
            onOpenDeploySettings = onOpenDeploySettings,
            onBack = onBack,
            showActions = showActions,
            onShowActions = { showActions = it },
            onRename = { showRename = true },
            onDelete = { showDeleteConfirm = true },
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
            NvpnDialog(
                title = "Удалить сервер?",
                onDismissRequest = { showDeleteConfirm = false },
                confirmAction = NvpnDialogAction(
                    text = "Удалить",
                    onClick = {
                        serversRepo.delete(server.id)
                        showDeleteConfirm = false
                        onBack()
                    },
                    destructive = true,
                ),
                dismissAction = NvpnDialogAction("Отмена", { showDeleteConfirm = false }),
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
            NvpnDialog(
                title = "Обновить деплой?",
                onDismissRequest = { if (!busy) showRedeployConfirm = false },
                confirmAction = NvpnDialogAction(
                    text = "Обновить",
                    onClick = { startRedeploy(server) },
                    enabled = !busy,
                ),
                dismissAction = NvpnDialogAction(
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
            NvpnDialog(
                title = when {
                    busy -> "Обновление деплоя…"
                    redeployStatus?.startsWith("Ошибка") == true -> "Ошибка"
                    else -> "Готово"
                },
                onDismissRequest = {
                    if (!busy) showRedeployProgress = false
                },
                confirmAction = if (busy) {
                    NvpnDialogAction("Отменить", { engine.cancel() }, destructive = true)
                } else {
                    NvpnDialogAction("Закрыть", { showRedeployProgress = false })
                },
                dismissOnBackPress = !busy,
                dismissOnClickOutside = !busy,
            ) {
                Text(
                    step.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                redeployStatus?.let {
                    Text(
                        it,
                        color = if (it.startsWith("Ошибка")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            NvpnColors.connected
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
                Text(
                    deployLog.takeLast(24).joinToString("\n").ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun ServerOverviewScreen(
    server: DeployTarget,
    health: HealthUi?,
    isActiveDeploy: Boolean,
    expectedVersion: String,
    onOpenClients: () -> Unit,
    onUpdateDeploy: () -> Unit,
    onOpenDeploySettings: () -> Unit,
    onBack: () -> Unit,
    showActions: Boolean,
    onShowActions: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val (statusText, statusColorHint) = healthStatusLine(health, server.lastDeployedAtMs, expectedVersion)
    val statusColor = when {
        statusColorHint != null -> statusColorHint
        health == HealthUi.Offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val activeBorder = deployFreshnessBorder(health, isActiveDeploy, expectedVersion)
    val showUpdateButton = shouldShowUpdateDeployButton(health, expectedVersion)
    val publicHostLine = distinctPublicHost(server.host, server.publicHost)
    val freshnessChip = deployFreshnessChipText(health, expectedVersion)

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        EdgeFeedTopInset()
        AppPageHeader(
            applyStatusBarsPadding = false,
            contentHorizontalPadding = true,
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
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { onShowActions(false) },
                        modifier = Modifier
                            .width(216.dp)
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(22.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 2.dp,
                        shadowElevation = 6.dp,
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier.heightIn(min = 54.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            text = { Text("Обновить деплой", fontWeight = FontWeight.Medium) },
                            leadingIcon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
                            onClick = {
                                onShowActions(false)
                                onUpdateDeploy()
                            },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.heightIn(min = 54.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            text = { Text("Переименовать", fontWeight = FontWeight.Medium) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                onShowActions(false)
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.heightIn(min = 54.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            text = {
                                Text(
                                    "Удалить",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                onShowActions(false)
                                onDelete()
                            },
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (showUpdateButton) {
                    NvpnBottomChrome.scrollContentPadding()
                } else {
                    NvpnBottomChrome.navigationReserve() + 16.dp
                },
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppSectionCard(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    border = activeBorder,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Filled.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                server.host,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "SSH ${server.sshPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (publicHostLine != null) {
                        Text(
                            "Публичный host: $publicHostLine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Direct ${server.directPort}  ·  Bypass ${server.bypassPort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                    if (freshnessChip != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = NvpnColors.warning.copy(alpha = 0.18f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                freshnessChip,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = NvpnColors.warning,
                            )
                        }
                    }
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

        if (showUpdateButton) {
            StickyPrimaryButton(
                text = "Обновить деплой",
                onClick = onUpdateDeploy,
                containerColor = NvpnColors.warning,
                icon = Icons.Filled.CloudUpload,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
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
    NvpnDialog(
        title = "Переименовать",
        onDismissRequest = onDismiss,
        confirmAction = NvpnDialogAction(
            "Сохранить",
            { onConfirm(name.trim().ifBlank { initialName }) },
        ),
        dismissAction = NvpnDialogAction("Отмена", onDismiss),
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
    val scope = rememberCoroutineScope()
    val busy by engine.busy.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val step by engine.step.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()

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
    var lastDeployedAtMs by remember { mutableStateOf(initial?.lastDeployedAtMs ?: 0L) }
    var status by remember { mutableStateOf<String?>(null) }

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
        lastDeployedAtMs = t.lastDeployedAtMs
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
        lastDeployedAtMs = deployedAt,
    )

    val context = LocalContext.current
    val expectedDeployVersion = remember(context) { DeployBundle.expectedVersion(context) }
    val isUpdate = initial != null && lastDeployedAtMs > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EdgeFeedTopInset()
        AppPageHeader(
            applyStatusBarsPadding = false,
            contentHorizontalPadding = true,
            title = if (isUpdate) "Обновить деплой" else "Деплой",
            subtitle = if (isUpdate) {
                "Стек $expectedDeployVersion · SSH · Compose"
            } else {
                "SSH · установка Compose-стека ARDTT"
            },
            onBack = onBack,
        )
        Text(
            if (isUpdate) {
                "Кнопка «Обновить деплой» заново зальёт стек версии $expectedDeployVersion на VPS."
            } else {
                "«Сохранить» только добавляет VPS в список. Установка стека — кнопка «Установить на VPS»."
            },
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
            placeholder = { Text("Как в NVPN_PUBLIC_HOST, обычно = IP") },
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

        OutlinedButton(
            onClick = {
                if (host.isBlank()) {
                    status = "Укажите host"
                    return@OutlinedButton
                }
                if (password.isBlank() && privateKey.isBlank()) {
                    status = "Нужен пароль или SSH-ключ"
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
                if (host.isBlank()) {
                    status = "Укажите host"
                    return@Button
                }
                if (password.isBlank() && privateKey.isBlank()) {
                    status = "Нужен пароль или SSH-ключ"
                    return@Button
                }
                val target = buildTarget()
                serversRepo.upsert(target)
                scope.launch {
                    status = null
                    val result = engine.deploy(target)
                    status = result.fold(
                        onSuccess = { msg ->
                            val deployedAt = System.currentTimeMillis()
                            lastDeployedAtMs = deployedAt
                            serversRepo.upsert(target.copy(lastDeployedAtMs = deployedAt))
                            msg
                        },
                        onFailure = { "Ошибка: ${it.message}" },
                    )
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
                when {
                    busy && isUpdate -> "Обновление…"
                    busy -> "Установка…"
                    isUpdate -> "Обновить деплой ($expectedDeployVersion)"
                    else -> "Установить на VPS"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedButton(
            onClick = onBack,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Назад")
        }

        if (busy) {
            OutlinedButton(
                onClick = { engine.cancel() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Отменить SSH") }
        }

        if (busy || progress > 0f) {
            Text(step.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        status?.let {
            Text(
                it,
                color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        if (log.isNotEmpty()) {
            Text(
                "Лог",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                log.takeLast(80).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        } // form column
    }
}
