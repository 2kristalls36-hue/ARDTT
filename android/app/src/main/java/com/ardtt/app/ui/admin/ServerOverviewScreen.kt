package com.ardtt.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.DeployIssue
import com.ardtt.app.deploy.DeployJobKind
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttOverflowMenu
import com.ardtt.app.ui.components.control.ArdttOverflowMenuItem
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.control.ArdttTextField
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttPullRefresh
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttStickyBottomBar
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.layout.rememberPullRefresh
import com.ardtt.app.ui.components.surface.ArdttCompactCard
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
internal fun ServerOverviewHost(
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
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(serverId, server?.host, server?.publicHost, lifecycleOwner) {
        val target = server ?: return@LaunchedEffect
        // Health re-probe only while the overview is on screen.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(ServerOverviewDefaults.HealthPollMs)
                if (busy) continue
                health = probeServerHealthUi(target, serversRepo)
            }
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
private object ServerOverviewDefaults {
    /** Health re-probe cadence while the overview is visible. */
    const val HealthPollMs = 10_000L
}
