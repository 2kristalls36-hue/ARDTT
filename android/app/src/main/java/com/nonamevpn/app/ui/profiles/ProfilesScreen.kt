package com.nonamevpn.app.ui.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.profile.DEFAULT_PROFILE_FOLDER
import com.nonamevpn.app.profile.ProfileCatalog
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.profile.StoredProfile
import com.nonamevpn.app.profile.ProfileImportResolver
import com.nonamevpn.app.profile.PendingProfileImport
import com.nonamevpn.app.profile.VpnProfile
import com.nonamevpn.app.profile.VpnProfileJson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nonamevpn.app.settings.AppSettingsRepository
import com.nonamevpn.app.ui.components.AppTabPageHeader
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import com.nonamevpn.app.ui.components.StickyBottomScaffold
import com.nonamevpn.app.ui.components.StickyPrimaryButton
import com.nonamevpn.app.ui.theme.NvpnColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ProfilesScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val catalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val scope = rememberCoroutineScope()
    var selectedFolder by remember { mutableStateOf(DEFAULT_PROFILE_FOLDER) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var shareProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var showFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var showFolderManage by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StoredProfile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var moveTarget by remember { mutableStateOf<StoredProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val folders = catalog.folders.ifEmpty { listOf(DEFAULT_PROFILE_FOLDER) }
    LaunchedEffect(folders) {
        if (selectedFolder !in folders) selectedFolder = folders.first()
    }
    val visible = catalog.inFolder(selectedFolder)

    fun afterChange(message: String? = null) {
        busy = false
        error = null
        showPaste = false
        pasteText = ""
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    fun applyProfile(item: StoredProfile, openTunnel: Boolean = false) {
        scope.launch {
            profiles.setActive(item.id)
            settings.setProfileName(item.profile.name)
            conn.updateProfile(item.profile)
            AppLog.i("Profiles", "active=${item.profile.name}")
            afterChange("Профиль выбран")
            if (openTunnel) onApplied()
        }
    }

    fun importResolved(raw: String, message: String? = null) {
        scope.launch {
            busy = true
            error = null
            runCatching {
                val imported = ProfileImportResolver.resolve(raw)
                imported.forEachIndexed { index, profile ->
                    profiles.upsert(profile, selectedFolder, activate = index == imported.lastIndex)
                }
                val last = imported.last()
                settings.setProfileName(last.name)
                conn.updateProfile(last)
                AppLog.i("Profiles", "imported ${imported.size} via link/url/json")
                afterChange(message ?: "Импортировано: ${imported.size}")
            }.onFailure { t ->
                busy = false
                error = t.message ?: "Ошибка импорта"
                AppLog.e("Profiles", "import failed: ${t.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        PendingProfileImport.take()?.let { importResolved(it, "Профиль из ссылки импортирован") }
    }

    val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { importResolved(it, "Профиль из QR импортирован") }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            runCatching {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val imported = profiles.importUri(uri, selectedFolder)
                settings.setProfileName(imported.name)
                conn.updateProfile(imported)
                AppLog.i("Profiles", "imported ${imported.name}")
                afterChange("Импортировано: ${imported.name}")
            }.onFailure { t ->
                busy = false
                error = t.message ?: "Ошибка импорта"
                AppLog.e("Profiles", "import file failed: ${t.message}")
            }
        }
    }

    StickyBottomScaffold(
        stickyContent = {
            StickyPrimaryButton(
                text = if (busy) "Импорт…" else "Добавить",
                onClick = { showAddSheet = true },
                enabled = !busy,
                icon = Icons.Default.Add,
            )
        },
    ) {
        AppTabPageHeader(
            title = "Профили подключения",
            subtitle = if (catalog.items.isEmpty()) {
                "Импортируйте JSON с сервера. Можно несколько профилей и папки."
            } else {
                "${catalog.items.size} профилей · активен: ${catalog.active?.name ?: "—"}"
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            folders.forEach { folder ->
                FilterChip(
                    selected = selectedFolder == folder,
                    onClick = { selectedFolder = folder },
                    label = { Text(folder) },
                )
            }
            IconButton(onClick = { folderName = ""; showFolder = true }) {
                Icon(Icons.Default.Add, contentDescription = "Новая папка")
            }
            IconButton(onClick = { showFolderManage = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Папки")
            }
        }

        error?.let {
            AppSectionCard(contentPadding = PaddingValues(16.dp)) {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (visible.isEmpty()) {
            AppSectionCard(contentPadding = PaddingValues(16.dp)) {
                Text(
                    if (catalog.items.isEmpty()) "Профили не загружены" else "В этой папке пока пусто",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Импортируйте JSON пользователя или создайте клиента на вкладке «Сервера».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            visible.forEach { item ->
                ProfileCard(
                    item = item,
                    active = item.id == catalog.activeId,
                    folders = folders,
                    onSelect = { applyProfile(item) },
                    onOpen = { applyProfile(item, openTunnel = true) },
                    onCopy = {
                        val json = VpnProfileJson.encode(item.profile)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ARDTT profile", json))
                        Toast.makeText(context, "JSON скопирован", Toast.LENGTH_SHORT).show()
                    },
                    onShare = { shareProfile = item.profile },
                    onRename = {
                        renameTarget = item
                        renameText = item.profile.name
                    },
                    onMove = { moveTarget = item },
                    onDelete = {
                        scope.launch {
                            val wasActive = item.id == catalog.activeId
                            profiles.delete(item.id)
                            if (wasActive) {
                                val next = profiles.snapshot().active
                                settings.setProfileName(next?.name.orEmpty())
                                conn.updateProfile(next)
                            }
                            AppLog.i("Profiles", "deleted ${item.profile.name}")
                        }
                    },
                )
            }
        }
    }

    if (showAddSheet) {
        ProfileAddSheet(
            onDismissRequest = { showAddSheet = false },
            onSubscription = { showSubscription = true },
            onManual = { pasteText = ""; showPaste = true },
            onFromFile = { pickFile.launch(arrayOf("application/json", "text/*", "*/*")) },
            onFromClipboard = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (clip.isBlank()) {
                    Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                } else {
                    importResolved(clip)
                }
            },
            onScanQr = {
                scanQr.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Наведите на QR-код профиля ARDTT")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    },
                )
            },
        )
    }

    shareProfile?.let { profile ->
        ProfileShareDialog(
            profile = profile,
            onDismissRequest = { shareProfile = null },
        )
    }

    if (showSubscription) {
        NvpnDialog(
            title = "Подписка",
            onDismissRequest = { if (!busy) showSubscription = false },
            confirmAction = NvpnDialogAction(
                text = "Загрузить",
                onClick = {
                    showSubscription = false
                    importResolved(subscriptionUrl.trim(), "Подписка обновлена")
                },
                enabled = subscriptionUrl.isNotBlank() && !busy,
            ),
            dismissAction = NvpnDialogAction("Отмена", { showSubscription = false }, enabled = !busy),
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ) {
            Text(
                "URL JSON на сервере, например http://VPS:9100/v1/profile/имя или список профилей.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = subscriptionUrl,
                onValueChange = { subscriptionUrl = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("https://…/profile.json") },
                singleLine = true,
            )
        }
    }

    if (showPaste) {
        NvpnDialog(
            title = "Импорт вручную",
            onDismissRequest = { if (!busy) showPaste = false },
            confirmAction = NvpnDialogAction(
                text = "Импорт",
                onClick = {
                    showPaste = false
                    importResolved(pasteText)
                },
                enabled = pasteText.isNotBlank() && !busy,
            ),
            dismissAction = NvpnDialogAction("Отмена", { showPaste = false }, enabled = !busy),
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ) {
            Text(
                "JSON, ссылка ardtt:// или URL подписки. Импорт в папку «$selectedFolder».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("ardtt://config?… или { \"name\": … }") },
            )
        }
    }

    if (showFolder) {
        NvpnDialog(
            title = "Новая папка",
            onDismissRequest = { showFolder = false },
            confirmAction = NvpnDialogAction(
                "Создать",
                {
                    scope.launch {
                        profiles.addFolder(folderName)
                        selectedFolder = folderName.trim().ifBlank { selectedFolder }
                        showFolder = false
                    }
                },
                enabled = folderName.isNotBlank(),
            ),
            dismissAction = NvpnDialogAction("Отмена", { showFolder = false }),
        ) {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Имя папки") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    renameTarget?.let { target ->
        NvpnDialog(
            title = "Переименовать",
            onDismissRequest = { renameTarget = null },
            confirmAction = NvpnDialogAction(
                "Сохранить",
                {
                    scope.launch {
                        profiles.rename(target.id, renameText)
                        if (target.id == catalog.activeId) settings.setProfileName(renameText.trim())
                        renameTarget = null
                    }
                },
                enabled = renameText.isNotBlank(),
            ),
            dismissAction = NvpnDialogAction("Отмена", { renameTarget = null }),
        ) {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text("Имя профиля") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    moveTarget?.let { target ->
        NvpnDialog(
            title = "Переместить",
            onDismissRequest = { moveTarget = null },
            dismissAction = NvpnDialogAction("Закрыть", { moveTarget = null }),
        ) {
            folders.forEach { folder ->
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            profiles.moveToFolder(target.id, folder)
                            selectedFolder = folder
                            moveTarget = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(folder) }
            }
        }
    }

    if (showFolderManage) {
        NvpnDialog(
            title = "Папки",
            onDismissRequest = { showFolderManage = false },
            dismissAction = NvpnDialogAction("Закрыть", { showFolderManage = false }),
        ) {
            folders.forEach { folder ->
                val count = catalog.inFolder(folder).size
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "$folder · $count",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val arr = JSONArray()
                                catalog.inFolder(folder).forEach { item ->
                                    arr.put(JSONObject(VpnProfileJson.encode(item.profile)))
                                }
                                val payload = JSONObject()
                                    .put("profiles", arr)
                                    .toString(2)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_TEXT, payload)
                                    putExtra(Intent.EXTRA_SUBJECT, folder)
                                }
                                context.startActivity(Intent.createChooser(send, "Экспорт папки"))
                            },
                            enabled = count > 0,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Экспорт") }
                        if (folder != DEFAULT_PROFILE_FOLDER) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        profiles.deleteFolder(folder)
                                        selectedFolder = DEFAULT_PROFILE_FOLDER
                                        showFolderManage = false
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    item: StoredProfile,
    active: Boolean,
    folders: List<String>,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    AppSectionCard(
        modifier = Modifier.clickable(onClick = onSelect),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        border = if (active) BorderStroke(2.dp, NvpnColors.connected) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.profile.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) NvpnColors.connected else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Активен",
                    tint = NvpnColors.connected,
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Действия")
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(18.dp),
            ) {
                DropdownMenuItem(text = { Text("Подключить") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Копировать JSON") }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text("Ссылка / QR") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                if (folders.size > 1) {
                    DropdownMenuItem(text = { Text("В папку…") }, onClick = { menu = false; onMove() })
                }
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
            }
        }
        Text(
            item.profile.direct.endpoint.ifBlank { item.profile.bypass.peer },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(item.folder)
                if (item.profile.hostId > 0) append(" · host ${item.profile.hostId}")
                if (active) append(" · выбран")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
