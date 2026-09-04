package com.ardtt.app.ui.admin

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.BuildConfig
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.deviceDisplayLabels
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.profile.VpnProfileJson
import com.ardtt.app.ui.latestAppVersionCode
import com.ardtt.app.update.AppUpdateController
import com.ardtt.app.ui.components.AppSectionCard
import com.ardtt.app.ui.components.CompactListCard
import com.ardtt.app.ui.components.TabFeedHeader
import com.ardtt.app.ui.components.TabHeaderMetrics
import com.ardtt.app.ui.components.ArdttBottomChrome
import com.ardtt.app.ui.components.ArdttDialog
import com.ardtt.app.ui.components.ArdttDialogAction
import com.ardtt.app.ui.components.PullRefreshHost
import com.ardtt.app.ui.components.StickyPrimaryButton
import com.ardtt.app.ui.components.rememberPullRefresh
import com.ardtt.app.ui.theme.ArdttColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val createDayOptions = listOf(0 to "∞", 7 to "7 дн", 30 to "30 дн", 90 to "90 дн")
private val createDeviceOptions = listOf(1, 2, 3, 5)

@Composable
internal fun ClientsHost(
    servers: List<DeployTarget>,
    serverId: String,
    profiles: ProfileRepository,
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
        ClientsScreen(server = server, profiles = profiles, onBack = onBack)
    }
}

@Composable
private fun ClientsScreen(
    server: DeployTarget,
    profiles: ProfileRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updates = remember { AppUpdateController.get(context) }
    val updateUi by updates.ui.collectAsStateWithLifecycle()
    val latestVersionCode = latestAppVersionCode(
        installedCode = BuildConfig.VERSION_CODE,
        catalogCode = updateUi.available?.versionCode ?: 0,
    )
    val base = remember(server.id, server.host, server.publicHost) {
        ProvisionAdminApi.provisionBase(server)
    }

    var users by remember { mutableStateOf<List<ProvisionAdminApi.UserSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createDays by remember { mutableIntStateOf(30) }
    var createMaxDevices by remember { mutableIntStateOf(1) }
    var creating by remember { mutableStateOf(false) }
    var sheetUser by remember { mutableStateOf<ProvisionAdminApi.UserSummary?>(null) }
    var sheetProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var sheetLoadingProfile by remember { mutableStateOf(false) }
    var renameUser by remember { mutableStateOf<ProvisionAdminApi.UserSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var busyUser by remember { mutableStateOf<String?>(null) }
    var editUser by remember { mutableStateOf<ProvisionAdminApi.UserSummary?>(null) }
    var editMaxDevices by remember { mutableStateOf("1") }
    var editDays by remember { mutableStateOf("") }
    var editTrafficGb by remember { mutableStateOf("0") }
    var editing by remember { mutableStateOf(false) }
    var deleteUser by remember { mutableStateOf<ProvisionAdminApi.UserSummary?>(null) }
    var deleting by remember { mutableStateOf(false) }

    fun addToPhone(json: String) {
        scope.launch {
            runCatching { profiles.importJson(json, activate = false) }
                .onSuccess {
                    Toast.makeText(context, "Добавлен в профили", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, it.message ?: "Не удалось добавить", Toast.LENGTH_LONG).show()
                }
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun applyUsersResult(result: Result<List<ProvisionAdminApi.UserSummary>>) {
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
    }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            applyUsersResult(ProvisionAdminApi.listUsers(base))
            loading = false
        }
    }

    fun refreshQuiet() {
        scope.launch {
            applyUsersResult(ProvisionAdminApi.listUsers(base))
        }
    }

    val pull = rememberPullRefresh {
        error = null
        applyUsersResult(ProvisionAdminApi.listUsers(base))
    }

    fun replaceUser(previousName: String, updated: ProvisionAdminApi.UserSummary) {
        users = users.map { if (it.name == previousName) updated else it }
        if (sheetUser?.name == previousName) sheetUser = updated
        if (renameUser?.name == previousName) renameUser = updated
        if (editUser?.name == previousName) editUser = updated
    }

    fun loadProfile(name: String, after: (String) -> Unit) {
        busyUser = name
        scope.launch {
            val result = ProvisionAdminApi.profileJson(base, name)
            busyUser = null
            result.fold(
                onSuccess = after,
                onFailure = {
                    sheetLoadingProfile = false
                    toast(it.message ?: "Ошибка профиля")
                },
            )
        }
    }

    fun applyUser(updated: ProvisionAdminApi.UserSummary) {
        replaceUser(updated.name, updated)
    }

    LaunchedEffect(users, sheetUser?.name) {
        val openName = sheetUser?.name ?: return@LaunchedEffect
        users.find { it.name == openName }?.let { latest ->
            if (latest != sheetUser) sheetUser = latest
        }
    }

    LaunchedEffect(base) {
        refresh()
        while (true) {
            delay(15_000L)
            applyUsersResult(ProvisionAdminApi.listUsers(base))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullRefreshHost(
            refreshing = pull.refreshing,
            onRefresh = { if (!loading) pull.onRefresh() },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = TabHeaderMetrics.HorizontalPadding)) {
                    TabFeedHeader(
                        title = "Клиенты",
                        subtitle = when {
                            loading -> "Загрузка…"
                            error != null -> server.host
                            else -> "${users.size} · ${server.name.ifBlank { server.host }}"
                        },
                        onBack = onBack,
                    )
                }

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
                    if (users.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Пока нет клиентов. Создайте пользователя — приложение сразу выдаст JSON профиля.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = ArdttBottomChrome.scrollContentPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(CompactListCard.ListSpacing),
                        ) {
                            items(users, key = { "${it.name}-${it.hostId}" }) { user ->
                                ClientCard(
                                    user = user,
                                    latestVersionCode = latestVersionCode,
                                    busy = busyUser != null,
                                    onOpenProfile = {
                                        sheetUser = user
                                        sheetProfile = null
                                        sheetLoadingProfile = true
                                        refreshQuiet()
                                        loadProfile(user.name) { json ->
                                            runCatching { VpnProfileJson.parse(json) }
                                                .onSuccess {
                                                    sheetProfile = it
                                                    sheetLoadingProfile = false
                                                }
                                                .onFailure {
                                                    sheetLoadingProfile = false
                                                    toast(it.message ?: "Ошибка профиля")
                                                }
                                        }
                                    },
                                    onEditLimits = {
                                        editUser = user
                                        editMaxDevices = user.maxDevices.toString()
                                        editDays = ""
                                        editTrafficGb = if (user.trafficLimitBytes <= 0L) {
                                            "0"
                                        } else {
                                            ((user.trafficLimitBytes + 1024L * 1024L * 1024L - 1) /
                                                (1024L * 1024L * 1024L)).toString()
                                        }
                                    },
                                    onDelete = { deleteUser = user },
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        StickyPrimaryButton(
            text = "Создать клиента",
            onClick = {
                createName = ""
                createDays = 30
                createMaxDevices = 1
                showCreate = true
            },
            icon = Icons.Filled.Add,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = ArdttBottomChrome.stickyBottomPadding()),
        )
    }

    if (showCreate) {
        ArdttDialog(
            title = "Новый клиент",
            onDismissRequest = { if (!creating) showCreate = false },
            confirmAction = ArdttDialogAction(
                text = if (creating) "Создание…" else "Создать",
                onClick = create@{
                    val name = createName.trim()
                    if (name.isBlank()) return@create
                    creating = true
                    scope.launch {
                        val result = ProvisionAdminApi.createUser(
                            base,
                            name,
                            days = createDays,
                            maxDevices = createMaxDevices,
                        )
                        creating = false
                        result.fold(
                            onSuccess = { body ->
                                val profile = runCatching { VpnProfileJson.parse(body) }.getOrElse {
                                    toast(it.message ?: "Ответ сервера не разобран")
                                    refresh()
                                    return@fold
                                }
                                showCreate = false
                                sheetProfile = profile
                                sheetLoadingProfile = false
                                sheetUser = userStubFromProfile(profile)
                                refresh()
                            },
                            onFailure = { toast(it.message ?: "Ошибка создания") },
                        )
                    }
                },
                enabled = !creating && createName.isNotBlank(),
            ),
            dismissAction = ArdttDialogAction(
                "Отмена",
                { showCreate = false },
                enabled = !creating,
            ),
            dismissOnBackPress = !creating,
            dismissOnClickOutside = !creating,
        ) {
            OutlinedTextField(
                value = createName,
                onValueChange = { createName = it },
                label = { Text("Имя") },
                singleLine = true,
                enabled = !creating,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Срок",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                createDayOptions.forEach { (days, label) ->
                    FilterChip(
                        selected = createDays == days,
                        onClick = { createDays = days },
                        enabled = !creating,
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Устройств",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                createDeviceOptions.forEach { count ->
                    FilterChip(
                        selected = createMaxDevices == count,
                        onClick = { createMaxDevices = count },
                        enabled = !creating,
                        label = { Text("$count") },
                    )
                }
            }
        }
    }

    editUser?.let { target ->
        ArdttDialog(
            title = "Лимиты · ${target.name}",
            onDismissRequest = { if (!editing) editUser = null },
            confirmAction = ArdttDialogAction(
                text = if (editing) "Сохранение…" else "Сохранить",
                onClick = {
                    editing = true
                    scope.launch {
                        val result = ProvisionAdminApi.updateUser(
                            base,
                            target.name,
                            maxDevices = editMaxDevices.toIntOrNull()?.coerceAtLeast(1),
                            days = editDays.toIntOrNull()?.takeIf { it > 0 },
                            trafficLimitGb = editTrafficGb.toIntOrNull()?.coerceAtLeast(0),
                        )
                        editing = false
                        result.fold(
                            onSuccess = {
                                applyUser(it)
                                editUser = null
                            },
                            onFailure = { toast(it.message ?: "Ошибка") },
                        )
                    }
                },
                enabled = !editing,
            ),
            dismissAction = ArdttDialogAction("Отмена", { editUser = null }, enabled = !editing),
            dismissOnBackPress = !editing,
            dismissOnClickOutside = !editing,
        ) {
            Text(
                "Сейчас ${target.deviceIds.size}/${target.maxDevices}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = editMaxDevices,
                onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 2) editMaxDevices = v },
                label = { Text("Лимит устройств") },
                singleLine = true,
                enabled = !editing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editDays,
                onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 4) editDays = v },
                label = { Text("Продлить на N дней (опц.)") },
                singleLine = true,
                enabled = !editing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editTrafficGb,
                onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 4) editTrafficGb = v },
                label = { Text("Лимит трафика, ГБ (0 = без лимита)") },
                singleLine = true,
                enabled = !editing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    deleteUser?.let { target ->
        ArdttDialog(
            title = "Удалить ${target.name}?",
            onDismissRequest = { if (!deleting) deleteUser = null },
            confirmAction = ArdttDialogAction(
                text = if (deleting) "Удаление…" else "Удалить",
                onClick = {
                    deleting = true
                    scope.launch {
                        val result = ProvisionAdminApi.deleteUser(base, target.name)
                        deleting = false
                        result.fold(
                            onSuccess = {
                                users = users.filterNot { it.name == target.name }
                                if (sheetUser?.name == target.name) {
                                    sheetUser = null
                                    sheetProfile = null
                                }
                                deleteUser = null
                                Toast.makeText(context, "Клиент удалён", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { toast(it.message ?: "Не удалось удалить") },
                        )
                    }
                },
                enabled = !deleting,
                destructive = true,
            ),
            dismissAction = ArdttDialogAction("Отмена", { deleteUser = null }, enabled = !deleting),
            dismissOnBackPress = !deleting,
            dismissOnClickOutside = !deleting,
        ) {
            Text(
                "Профиль и доступ на сервере будут удалены. Уже выданные JSON на телефонах перестанут работать после обновления стека.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    renameUser?.let { target ->
        ArdttDialog(
            title = "Имя клиента",
            onDismissRequest = { if (!renaming) renameUser = null },
            confirmAction = ArdttDialogAction(
                text = if (renaming) "Сохранение…" else "Сохранить",
                onClick = click@{
                    val next = renameDraft.trim()
                    if (next.isBlank()) return@click
                    if (next == target.name) {
                        renameUser = null
                        return@click
                    }
                    renaming = true
                    scope.launch {
                        val result = ProvisionAdminApi.updateUser(
                            base,
                            target.name,
                            newName = next,
                        )
                        renaming = false
                        result.fold(
                            onSuccess = { updated ->
                                if (updated.name != next) {
                                    toast("Сервер не сменил имя")
                                    return@fold
                                }
                                replaceUser(target.name, updated)
                                renameUser = null
                                loadProfile(updated.name) { json ->
                                    runCatching { VpnProfileJson.parse(json) }
                                        .onSuccess {
                                            sheetProfile = it
                                            sheetLoadingProfile = false
                                        }
                                        .onFailure {
                                            sheetLoadingProfile = false
                                            toast(it.message ?: "Ошибка профиля")
                                        }
                                }
                            },
                            onFailure = { toast(it.message ?: "Не удалось изменить имя") },
                        )
                    }
                },
                enabled = !renaming && renameDraft.trim().isNotBlank(),
            ),
            dismissAction = ArdttDialogAction("Отмена", { renameUser = null }, enabled = !renaming),
            dismissOnBackPress = !renaming,
            dismissOnClickOutside = !renaming,
        ) {
            OutlinedTextField(
                value = renameDraft,
                onValueChange = { renameDraft = it },
                label = { Text("Имя") },
                singleLine = true,
                enabled = !renaming,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    sheetUser?.let { user ->
        ClientSettingsSheet(
            user = user,
            latestVersionCode = latestVersionCode,
            profile = sheetProfile,
            loadingProfile = sheetLoadingProfile,
            busy = busyUser != null || renaming,
            onDismissRequest = {
                sheetUser = null
                sheetProfile = null
                sheetLoadingProfile = false
            },
            onEditName = {
                renameDraft = user.name
                renameUser = user
            },
            onUnbindDevice = { deviceId ->
                busyUser = user.name
                scope.launch {
                    val result = ProvisionAdminApi.unbindDevice(base, user.name, deviceId)
                    busyUser = null
                    result.fold(
                        onSuccess = { replaceUser(user.name, it) },
                        onFailure = { toast(it.message ?: "Не удалось отвязать") },
                    )
                }
            },
            onAddToPhone = {
                sheetProfile?.let { addToPhone(VpnProfileJson.encode(it)) }
            },
        )
    }
}

@Composable
private fun ClientCard(
    user: ProvisionAdminApi.UserSummary,
    latestVersionCode: Int,
    busy: Boolean,
    onOpenProfile: () -> Unit,
    onEditLimits: () -> Unit,
    onDelete: () -> Unit,
) {
    val subActive = clientSubscriptionActive(user)
    val used = user.usedBytes
    val limit = user.trafficLimitBytes
    val progress = when {
        limit <= 0L -> 0f
        else -> (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    }
    val trafficColor = when {
        limit <= 0L -> ArdttColors.connected
        progress >= 0.85f -> MaterialTheme.colorScheme.error
        progress >= 0.55f -> ArdttColors.warning
        else -> ArdttColors.connected
    }
    val deviceLine = deviceDisplayLabels(user.deviceIds, user.deviceModels)
        .joinToString(" · ")
        .ifBlank { "" }
    val usedDevices = user.deviceIds.size
    val availableDevices = (user.maxDevices - usedDevices).coerceAtLeast(0)
    val presence = when {
        user.deactivated -> "отключён"
        !subActive -> "истекла"
        user.online -> "онлайн"
        user.lastSeenAt > 0L -> formatClientRelative(user.lastSeenAt * 1000L)
        else -> "оффлайн"
    }
    val presenceColor = when {
        user.deactivated || !subActive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val expiresTone = clientExpiresTone(user.expiresAt)
    val expiresColor = when (expiresTone) {
        ClientExpiresTone.Unlimited, ClientExpiresTone.Active -> ArdttColors.connected
        ClientExpiresTone.ExpiringSoon -> ArdttColors.warning
        ClientExpiresTone.Expired -> MaterialTheme.colorScheme.error
    }
    AppSectionCard(
        contentPadding = CompactListCard.ContentPadding,
        verticalArrangement = Arrangement.spacedBy(CompactListCard.ItemSpacing),
        shape = CompactListCard.Shape,
        shadowElevation = CompactListCard.ShadowElevation,
        tonalElevation = 0.dp,
        showBorder = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, onClick = onOpenProfile),
            verticalArrangement = Arrangement.spacedBy(CompactListCard.ItemSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    user.name.ifBlank { "user" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (user.online) ArdttColors.connected else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(8.dp),
                ) {}
                Text(
                    presence,
                    style = MaterialTheme.typography.labelSmall,
                    color = presenceColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (limit > 0L) {
                        "${formatClientBytes(used)} / ${formatClientBytes(limit)}"
                    } else {
                        formatClientBytes(used)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = trafficColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val appVer = clientAppVersionView(user, latestVersionCode)
                    val appVerColor = when (appVer.tone) {
                        ClientAppVersionTone.Current -> ArdttColors.connected
                        ClientAppVersionTone.Outdated -> MaterialTheme.colorScheme.error
                        ClientAppVersionTone.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = appVerColor.copy(alpha = 0.18f),
                    ) {
                        Text(
                            appVer.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = appVerColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = expiresColor.copy(alpha = 0.18f),
                    ) {
                        Text(
                            formatClientExpires(user.expiresAt),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = expiresColor,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                "Устройства: занято $usedDevices · доступно $availableDevices",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (limit > 0L) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = trafficColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            val externalIp = user.lastExternalIp.trim()
            val detail = listOfNotNull(
                externalIp.takeIf { it.isNotBlank() },
                deviceLine.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClientActionButton("Лимит", busy, Modifier.weight(1f), onEditLimits)
            ClientActionButton("Удалить", busy, Modifier.weight(1f), onDelete)
        }
    }
}

@Composable
private fun ClientActionButton(
    label: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
