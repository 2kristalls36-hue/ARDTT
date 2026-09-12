package com.ardtt.app.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployEngine
import com.ardtt.app.deploy.DeployIssue
import com.ardtt.app.deploy.DeployJobKind
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttDigitsField
import com.ardtt.app.ui.components.control.ArdttPasswordField
import com.ardtt.app.ui.components.control.ArdttSwitchRow
import com.ardtt.app.ui.components.control.ArdttTextField
import com.ardtt.app.ui.components.control.ArdttTextFieldDefaults
import com.ardtt.app.ui.components.layout.ArdttBottomChrome
import com.ardtt.app.ui.components.layout.ArdttScrollChrome
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttSectionCard
import com.ardtt.app.ui.theme.ArdttShapes
import com.ardtt.app.ui.theme.ArdttSpacing


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
            modifier = Modifier.heightIn(min = ArdttTextFieldDefaults.MultilineMinHeight),
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
                    modifier = Modifier.heightIn(min = ArdttTextFieldDefaults.MultilineMinHeight),
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
                // `status` is the red validation line and this screen is left right
                // away, so success goes through a toast that survives navigation.
                status = null
                Toast.makeText(context, "Сервер сохранён", Toast.LENGTH_SHORT).show()
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
