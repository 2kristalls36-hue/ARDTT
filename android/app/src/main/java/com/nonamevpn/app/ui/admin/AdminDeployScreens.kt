package com.nonamevpn.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import com.nonamevpn.app.deploy.DeployEngine
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.deploy.ServersRepository
import com.nonamevpn.app.profile.NetworkEndpoint
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.ui.components.AppPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
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

private enum class HealthUi { Checking, Online, Offline }

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

private fun healthStatusLine(health: HealthUi?, lastDeployedAtMs: Long): Pair<String, Color?> {
    return when (health) {
        null, HealthUi.Checking -> "● Проверка…" to null
        HealthUi.Online -> {
            val base = "● Онлайн"
            val text = if (lastDeployedAtMs > 0L) {
                "$base · деплой ${formatDeployRelative(lastDeployedAtMs)}"
            } else {
                base
            }
            text to NvpnColors.connected
        }
        HealthUi.Offline -> {
            val text = if (lastDeployedAtMs == 0L) {
                "● Не установлен / нет связи"
            } else {
                "● Нет связи"
            }
            text to null // error color applied by caller when null + offline
        }
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
                serverId = s.serverId,
                isActiveDeploy = s.serverId == activeDeployServerId,
                onOpenClients = { screen = ServersNavScreen.Clients(s.serverId) },
                onOpenDeploy = { screen = ServersNavScreen.Deploy(s.serverId) },
                onBack = { screen = ServersNavScreen.List },
            )
            is ServersNavScreen.Clients -> ClientsHost(
                servers = servers,
                serverId = s.serverId,
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
                        val online = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target))
                            .getOrNull() == true
                        val status = if (online) HealthUi.Online else HealthUi.Offline
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
        Column(modifier = Modifier.fillMaxSize()) {
            AppPageHeader(
                applyStatusBarsPadding = true,
                contentHorizontalPadding = true,
                title = "Серверы",
                subtitle = "Управление вашими VPS",
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

            if (servers.isNotEmpty()) {
                OutlinedButton(
                    onClick = { probeAll() },
                    enabled = !probing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (probing) "Проверка…" else "Обновить статус всех",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
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
                            "Добавьте первый VPS, чтобы установить сервер и управлять пользователями",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(30.dp))
                        Button(
                            onClick = onAddServer,
                            modifier = Modifier
                                .widthIn(max = 304.dp)
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить сервер")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            health = healthById[server.id],
                            isActiveDeploy = server.id == activeDeployServerId,
                            onOpenServer = { onOpenServer(server.id) },
                        )
                    }
                }
            }
        }

        if (servers.isNotEmpty()) {
            FloatingActionButton(
                onClick = onAddServer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 22.dp)
                    .size(58.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить сервер")
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: DeployTarget,
    health: HealthUi?,
    isActiveDeploy: Boolean,
    onOpenServer: () -> Unit,
) {
    val (statusText, statusColorHint) = healthStatusLine(health, server.lastDeployedAtMs)
    val statusColor = when {
        statusColorHint != null -> statusColorHint
        health == HealthUi.Offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val activeBorder = if (isActiveDeploy) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    serverId: String,
    isActiveDeploy: Boolean,
    onOpenClients: () -> Unit,
    onOpenDeploy: () -> Unit,
    onBack: () -> Unit,
) {
    val server = servers.find { it.id == serverId }
    var showActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<HealthUi?>(HealthUi.Checking) }

    LaunchedEffect(servers, serverId) {
        if (servers.isNotEmpty() && server == null) onBack()
    }

    LaunchedEffect(serverId, server?.host, server?.publicHost) {
        val target = server ?: return@LaunchedEffect
        health = HealthUi.Checking
        val online = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target))
            .getOrNull() == true
        health = if (online) HealthUi.Online else HealthUi.Offline
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
            onOpenClients = onOpenClients,
            onOpenDeploy = onOpenDeploy,
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
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Удалить сервер?") },
                text = {
                    Text(
                        "Из приложения будут удалены только данные подключения. " +
                            "Сервер и пользователи на VPS останутся без изменений.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            serversRepo.delete(server.id)
                            showDeleteConfirm = false
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Удалить") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
                },
            )
        }
    }
}

@Composable
private fun ServerOverviewScreen(
    server: DeployTarget,
    health: HealthUi?,
    isActiveDeploy: Boolean,
    onOpenClients: () -> Unit,
    onOpenDeploy: () -> Unit,
    onBack: () -> Unit,
    showActions: Boolean,
    onShowActions: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val (statusText, statusColorHint) = healthStatusLine(health, server.lastDeployedAtMs)
    val statusColor = when {
        statusColorHint != null -> statusColorHint
        health == HealthUi.Offline -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val activeBorder = if (isActiveDeploy) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppPageHeader(
            applyStatusBarsPadding = true,
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                    Text(
                        "Публичный host: ${server.publicHost.ifBlank { server.host }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    if (isActiveDeploy) {
                        Text(
                            "Текущий профиль",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                    title = "Управление сервером",
                    description = "SSH, порты, обновление и переустановка",
                    onClick = onOpenDeploy,
                )
            }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя сервера") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim().ifBlank { initialName }) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun ClientsHost(
    servers: List<DeployTarget>,
    serverId: String,
    onBack: () -> Unit,
) {
    val server = servers.find { it.id == serverId }
    LaunchedEffect(servers, serverId) {
        if (servers.isNotEmpty() && server == null) onBack()
    }
    if (server == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        ClientsScreen(server = server, onBack = onBack)
    }
}

@Composable
private fun ClientsScreen(
    server: DeployTarget,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = remember(server.id, server.host, server.publicHost) {
        ProvisionAdminApi.provisionBase(server)
    }

    var users by remember { mutableStateOf<List<ProvisionAdminApi.UserSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var profilePreview by remember { mutableStateOf<String?>(null) }
    var busyUser by remember { mutableStateOf<String?>(null) }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val result = ProvisionAdminApi.listUsers(base)
            result.fold(
                onSuccess = {
                    users = it
                    error = null
                },
                onFailure = {
                    users = emptyList()
                    error = it.message ?: "Provision недоступен"
                },
            )
            loading = false
        }
    }

    LaunchedEffect(base) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        AppPageHeader(
            applyStatusBarsPadding = true,
            contentHorizontalPadding = true,
            title = "Клиенты",
            subtitle = when {
                loading -> "Загрузка…"
                error != null -> server.host
                else -> "${users.size} · ${server.name.ifBlank { server.host }}"
            },
            onBack = onBack,
            actions = {
                IconButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Обновить",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.widthIn(max = 360.dp),
                    ) {
                        Text(
                            "Provision недоступен",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Нужен установленный стек (health на :9100).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(onClick = { refresh() }) { Text("Повторить") }
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (users.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Пока нет пользователей. Создайте первого через provision.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(users, key = { "${it.name}-${it.deviceId}-${it.hostId}" }) { user ->
                                AppSectionCard(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    shape = RoundedCornerShape(24.dp),
                                ) {
                                    Text(
                                        user.name.ifBlank { "user" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "hostId ${user.hostId} · device ${user.deviceId.ifBlank { "—" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        if (user.hideIp) "hideIp: да" else "hideIp: нет",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            busyUser = user.name
                                            scope.launch {
                                                val result = ProvisionAdminApi.profileJson(base, user.name)
                                                result.fold(
                                                    onSuccess = { json ->
                                                        copyText("ARDTT profile", json)
                                                    },
                                                    onFailure = {
                                                        Toast.makeText(
                                                            context,
                                                            it.message ?: "Ошибка профиля",
                                                            Toast.LENGTH_LONG,
                                                        ).show()
                                                    },
                                                )
                                                busyUser = null
                                            }
                                        },
                                        enabled = busyUser == null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp),
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        Text(
                                            if (busyUser == user.name) "Загрузка…" else "Профиль",
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = {
                            createName = ""
                            showCreate = true
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 24.dp, bottom = 22.dp)
                            .size(58.dp),
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Создать пользователя")
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { if (!creating) showCreate = false },
            title = { Text("Новый пользователь") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("Имя") },
                    singleLine = true,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = createName.trim()
                        if (name.isBlank()) return@Button
                        creating = true
                        scope.launch {
                            val result = ProvisionAdminApi.createUser(base, name)
                            creating = false
                            result.fold(
                                onSuccess = { body ->
                                    showCreate = false
                                    profilePreview = body
                                    refresh()
                                },
                                onFailure = {
                                    Toast.makeText(
                                        context,
                                        it.message ?: "Ошибка создания",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        }
                    },
                    enabled = !creating && createName.isNotBlank(),
                ) {
                    Text(if (creating) "Создание…" else "Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }, enabled = !creating) {
                    Text("Отмена")
                }
            },
        )
    }

    profilePreview?.let { json ->
        AlertDialog(
            onDismissRequest = { profilePreview = null },
            title = { Text("Профиль создан") },
            text = {
                Text(
                    json.take(1200),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyText("ARDTT profile", json)
                        profilePreview = null
                    },
                ) { Text("Копировать") }
            },
            dismissButton = {
                TextButton(onClick = { profilePreview = null }) { Text("Закрыть") }
            },
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppPageHeader(
            applyStatusBarsPadding = true,
            contentHorizontalPadding = true,
            title = "Деплой",
            subtitle = "SSH · установка Compose-стека ARDTT",
            onBack = onBack,
        )
        Text(
            "«Сохранить» только добавляет VPS в список. Установка стека — кнопка «Установить на VPS».",
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Установка…" else "Установить на VPS")
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
