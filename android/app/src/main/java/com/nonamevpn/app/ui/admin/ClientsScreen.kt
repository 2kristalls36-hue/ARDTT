package com.nonamevpn.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.ui.components.AppPageHeader
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
    var profilePreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busyUser by remember { mutableStateOf<String?>(null) }
    var editUser by remember { mutableStateOf<ProvisionAdminApi.UserSummary?>(null) }
    var editMaxDevices by remember { mutableStateOf("1") }
    var editDays by remember { mutableStateOf("") }
    var editTrafficGb by remember { mutableStateOf("0") }
    var editing by remember { mutableStateOf(false) }
    var deleteUser by remember { mutableStateOf<ProvisionAdminApi.UserSummary?>(null) }
    var deleting by remember { mutableStateOf(false) }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun shareJson(name: String, json: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, name)
        }
        context.startActivity(Intent.createChooser(send, "Поделиться профилем"))
    }

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

    fun loadProfile(name: String, after: (String) -> Unit) {
        busyUser = name
        scope.launch {
            val result = ProvisionAdminApi.profileJson(base, name)
            busyUser = null
            result.fold(
                onSuccess = after,
                onFailure = { toast(it.message ?: "Ошибка профиля") },
            )
        }
    }

    fun applyUser(updated: ProvisionAdminApi.UserSummary) {
        users = users.map { if (it.name == updated.name) updated else it }
    }

    LaunchedEffect(base) { refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EdgeFeedTopInset()
            AppPageHeader(
                applyStatusBarsPadding = false,
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
                                bottom = NvpnBottomChrome.scrollContentPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(users, key = { "${it.name}-${it.hostId}" }) { user ->
                                ClientCard(
                                    user = user,
                                    busy = busyUser != null,
                                    onCopyPassword = {
                                        loadProfile(user.name) { json ->
                                            val password = runCatching {
                                                org.json.JSONObject(json)
                                                    .getJSONObject("bypass")
                                                    .optString("password")
                                            }.getOrDefault("")
                                            if (password.isBlank()) {
                                                toast("В профиле нет пароля")
                                            } else {
                                                copyText("ARDTT password", password)
                                            }
                                        }
                                    },
                                    onShareProfile = {
                                        loadProfile(user.name) { json ->
                                            profilePreview = user.name to json
                                        }
                                    },
                                    onUnbindAll = {
                                        busyUser = user.name
                                        scope.launch {
                                            val result = ProvisionAdminApi.updateUser(
                                                base,
                                                user.name,
                                                clearDevices = true,
                                            )
                                            result.fold(
                                                onSuccess = {
                                                    applyUser(it)
                                                    Toast.makeText(
                                                        context,
                                                        "Устройства отвязаны",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                                onFailure = { toast(it.message ?: "Не удалось отвязать") },
                                            )
                                            busyUser = null
                                        }
                                    },
                                    onBan = {
                                        busyUser = user.name
                                        scope.launch {
                                            val result = ProvisionAdminApi.updateUser(
                                                base,
                                                user.name,
                                                deactivated = !user.deactivated,
                                            )
                                            result.fold(
                                                onSuccess = ::applyUser,
                                                onFailure = { toast(it.message ?: "Ошибка") },
                                            )
                                            busyUser = null
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
                                    onUnbindOne = { deviceId ->
                                        busyUser = user.name
                                        scope.launch {
                                            val result = ProvisionAdminApi.unbindDevice(
                                                base,
                                                user.name,
                                                deviceId,
                                            )
                                            result.fold(
                                                onSuccess = ::applyUser,
                                                onFailure = { toast(it.message ?: "Не удалось открепить") },
                                            )
                                            busyUser = null
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
                .padding(bottom = NvpnBottomChrome.stickyBottomPadding()),
        )
    }

    if (showCreate) {
        NvpnDialog(
            title = "Новый клиент",
            onDismissRequest = { if (!creating) showCreate = false },
            confirmAction = NvpnDialogAction(
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
                                showCreate = false
                                profilePreview = name to body
                                refresh()
                            },
                            onFailure = { toast(it.message ?: "Ошибка создания") },
                        )
                    }
                },
                enabled = !creating && createName.isNotBlank(),
            ),
            dismissAction = NvpnDialogAction(
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
        NvpnDialog(
            title = "Лимиты · ${target.name}",
            onDismissRequest = { if (!editing) editUser = null },
            confirmAction = NvpnDialogAction(
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
            dismissAction = NvpnDialogAction("Отмена", { editUser = null }, enabled = !editing),
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
        NvpnDialog(
            title = "Удалить ${target.name}?",
            onDismissRequest = { if (!deleting) deleteUser = null },
            confirmAction = NvpnDialogAction(
                text = if (deleting) "Удаление…" else "Удалить",
                onClick = {
                    deleting = true
                    scope.launch {
                        val result = ProvisionAdminApi.deleteUser(base, target.name)
                        deleting = false
                        result.fold(
                            onSuccess = {
                                users = users.filterNot { it.name == target.name }
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
            dismissAction = NvpnDialogAction("Отмена", { deleteUser = null }, enabled = !deleting),
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

    profilePreview?.let { (name, json) ->
        NvpnDialog(
            title = "Профиль · $name",
            onDismissRequest = { profilePreview = null },
            confirmAction = NvpnDialogAction(
                "Поделиться",
                {
                    shareJson(name, json)
                    profilePreview = null
                },
            ),
            dismissAction = NvpnDialogAction("Закрыть", { profilePreview = null }),
            secondaryAction = NvpnDialogAction(
                "Копировать",
                { copyText("ARDTT profile", json) },
            ),
        ) {
            Text(
                "Раздайте JSON клиенту или добавьте на этот телефон.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                json.take(1200),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
            OutlinedButton(
                onClick = { addToPhone(json) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Добавить на этот телефон")
            }
        }
    }
}

@Composable
private fun ClientCard(
    user: ProvisionAdminApi.UserSummary,
    busy: Boolean,
    onCopyPassword: () -> Unit,
    onShareProfile: () -> Unit,
    onUnbindAll: () -> Unit,
    onBan: () -> Unit,
    onEditLimits: () -> Unit,
    onUnbindOne: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val subActive = !user.deactivated &&
        (user.expiresAt <= 0L || user.expiresAt * 1000L > System.currentTimeMillis())
    val used = user.usedBytes
    val limit = user.trafficLimitBytes
    val progress = when {
        limit <= 0L -> 0f
        else -> (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    }
    val trafficColor = when {
        limit <= 0L -> NvpnColors.connected
        progress >= 0.85f -> MaterialTheme.colorScheme.error
        progress >= 0.55f -> NvpnColors.warning
        else -> NvpnColors.connected
    }
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            2.dp,
            if (subActive) NvpnColors.connected else MaterialTheme.colorScheme.error,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                user.name.ifBlank { "user" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = if (user.online) NvpnColors.connected else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(10.dp),
            ) {}
            Text(
                if (user.online) "онлайн" else "оффлайн",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Трафик",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (limit > 0L) {
                        "${formatClientBytes(used)} / ${formatClientBytes(limit)}"
                    } else {
                        formatClientBytes(used)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = trafficColor,
                )
            }
            if (limit > 0L) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = trafficColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        Text(
            "hostId ${user.hostId} · устройств ${user.deviceIds.size}/${user.maxDevices} · до ${formatClientExpires(user.expiresAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when {
                user.deactivated -> "Забанен"
                !subActive -> "Подписка истекла"
                user.online -> "Последнее подключение: сейчас"
                user.lastSeenAt > 0L ->
                    "Последнее: ${formatClientRelative(user.lastSeenAt * 1000L)}"
                else -> "Ещё не подключался"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                user.deactivated || !subActive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (user.lastExternalIp.isNotBlank()) {
            Text(
                user.lastExternalIp,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (user.deviceIds.isNotEmpty()) {
            user.deviceIds.forEach { deviceId ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        deviceId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { onUnbindOne(deviceId) },
                        enabled = !busy,
                    ) { Text("Открепить") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClientActionButton("Пароль", busy, Modifier.weight(1f), onCopyPassword)
            ClientActionButton("Профиль", busy, Modifier.weight(1f), onShareProfile)
            ClientActionButton("Лимит", busy, Modifier.weight(1f), onEditLimits)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClientActionButton("Отвязать", busy, Modifier.weight(1f), onUnbindAll)
            ClientActionButton(
                if (user.deactivated) "Разбан" else "Бан",
                busy,
                Modifier.weight(1f),
                onBan,
            )
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
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun formatClientBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b < 1024L -> "$b Б"
        b < 1024L * 1024L -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f МБ", b / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f ГБ", b / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatClientExpires(expiresAt: Long): String {
    if (expiresAt <= 0L) return "без срока"
    return SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(expiresAt * 1000L))
}

private fun formatClientRelative(ms: Long): String {
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

