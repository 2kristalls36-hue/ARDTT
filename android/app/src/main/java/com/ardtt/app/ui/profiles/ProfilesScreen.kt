package com.ardtt.app.ui.profiles

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.R
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.profile.PendingProfileImport
import com.ardtt.app.profile.ProfileCatalog
import com.ardtt.app.profile.ProfileImportResolver
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.profile.StoredProfile
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.profile.VpnProfileJson
import com.ardtt.app.settings.AppSettingsRepository
import com.ardtt.app.ui.PROFILE_SWITCH_LOCKED_MESSAGE
import com.ardtt.app.ui.PendingUiAction
import com.ardtt.app.ui.admin.ClientExpiresTone
import com.ardtt.app.ui.admin.clientExpiresTone
import com.ardtt.app.ui.admin.formatClientExpires
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttOverflowMenu
import com.ardtt.app.ui.components.control.ArdttOverflowMenuItem
import com.ardtt.app.ui.components.control.ArdttPrimaryButton
import com.ardtt.app.ui.components.control.ArdttTextField
import com.ardtt.app.ui.components.feedback.ArdttIpHostRow
import com.ardtt.app.ui.components.feedback.ArdttStatusChip
import com.ardtt.app.ui.components.layout.ArdttLazyFeedScaffold
import com.ardtt.app.ui.components.layout.ArdttTabHeader
import com.ardtt.app.ui.components.surface.ArdttCompactCard
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttLeadingIcon
import com.ardtt.app.ui.components.surface.ArdttSectionCardDefaults
import com.ardtt.app.ui.theme.ArdttColors
import com.ardtt.app.ui.theme.connectedStatusColor
import com.ardtt.app.ui.theme.warningStatusColor
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
import com.ardtt.app.ui.util.copyToClipboard
import com.ardtt.app.ui.util.readClipboardText
import com.ardtt.app.ui.vpnSessionBlocksProfileSwitch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(
    settings: AppSettingsRepository,
    profiles: ProfileRepository,
    serversRepo: ServersRepository,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val conn = remember { ConnectionManager.get(context) }
    val connUi by conn.ui.collectAsStateWithLifecycle()
    val catalog by profiles.catalog.collectAsStateWithLifecycle(initialValue = ProfileCatalog())
    val servers by serversRepo.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    var liveFacts by remember { mutableStateOf<Map<String, ProfileLiveFacts>>(emptyMap()) }
    val profileSwitchLocked = vpnSessionBlocksProfileSwitch(connUi.state)
    val switchLocked = rememberUpdatedState(profileSwitchLocked)
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var shareProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<StoredProfile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val visible = catalog.items
    val openProfileAdd by PendingUiAction.openProfileAdd.collectAsStateWithLifecycle()

    fun afterChange(message: String? = null) {
        busy = false
        error = null
        showPaste = false
        pasteText = ""
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    fun applyProfile(item: StoredProfile, openTunnel: Boolean = false) {
        if (switchLocked.value && item.id != catalog.activeId) {
            Toast.makeText(context, PROFILE_SWITCH_LOCKED_MESSAGE, Toast.LENGTH_SHORT).show()
            return
        }
        if (switchLocked.value && item.id == catalog.activeId) {
            if (openTunnel) onApplied()
            return
        }
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
            val activate = !switchLocked.value
            runCatching {
                val imported = ProfileImportResolver.resolve(raw)
                imported.forEachIndexed { index, profile ->
                    profiles.upsert(profile, activate = activate && index == imported.lastIndex)
                }
                if (activate) {
                    val last = imported.last()
                    settings.setProfileName(last.name)
                    conn.updateProfile(last)
                }
                AppLog.i("Profiles", "imported ${imported.size} via link/url/json activate=$activate")
                afterChange(
                    when {
                        !activate -> "Импортировано без смены активного профиля: ${imported.size}"
                        else -> message ?: "Импортировано: ${imported.size}"
                    },
                )
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

    LaunchedEffect(openProfileAdd) {
        if (PendingUiAction.consumeOpenProfileAdd()) {
            showAddSheet = true
        }
    }

    LaunchedEffect(catalog.items) {
        val next = linkedMapOf<String, ProfileLiveFacts>()
        catalog.items.groupBy { it.profile.provisionBaseUrl }.forEach { (base, items) ->
            if (base.isNullOrBlank()) return@forEach
            val users = ProvisionAdminApi.listUsers(base).getOrNull() ?: return@forEach
            items.forEach { item ->
                profileLiveFactsFromUsers(item.profile.name, users)?.let { next[item.id] = it }
            }
        }
        liveFacts = next
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
                val imported = profiles.importUri(uri, activate = !switchLocked.value)
                if (!switchLocked.value) {
                    settings.setProfileName(imported.name)
                    conn.updateProfile(imported)
                }
                AppLog.i("Profiles", "imported ${imported.name}")
                afterChange(
                    if (switchLocked.value) {
                        "Импортировано без смены активного профиля: ${imported.name}"
                    } else {
                        "Импортировано: ${imported.name}"
                    },
                )
            }.onFailure { t ->
                busy = false
                error = t.message ?: "Ошибка импорта"
                AppLog.e("Profiles", "import file failed: ${t.message}")
            }
        }
    }

    ArdttLazyFeedScaffold(
        stickyContent = {
            ArdttPrimaryButton(
                text = if (busy) "Импорт…" else stringResource(R.string.profiles_add),
                onClick = { showAddSheet = true },
                enabled = !busy,
                icon = Icons.Default.Add,
            )
        },
        header = {
            ArdttTabHeader(
                title = "Профили",
                subtitle = profilesCountSubtitle(
                    count = catalog.items.size,
                    countLabel = pluralStringResource(
                        R.plurals.profiles_count,
                        catalog.items.size,
                        catalog.items.size,
                    ),
                    activeName = catalog.active?.name,
                    locked = profileSwitchLocked,
                ),
            )
        },
    ) {
        error?.let { message ->
            item(key = "error") {
                ArdttCompactCard {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                ArdttCompactCard {
                    Text(
                        "Профили не загружены",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Импортируйте JSON пользователя или создайте клиента на вкладке VPS.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(visible, key = { it.id }) { stored ->
                ProfileCard(
                    item = stored,
                    active = stored.id == catalog.activeId,
                    selectionLocked = profileSwitchLocked,
                    servers = servers,
                    liveFacts = liveFacts[stored.id],
                    onSelect = { applyProfile(stored) },
                    onOpen = { applyProfile(stored, openTunnel = true) },
                    onCopy = {
                        copyToClipboard(
                            context = context,
                            text = VpnProfileJson.encode(stored.profile),
                            clipLabel = "ARDTT profile",
                            toast = "JSON скопирован",
                        )
                    },
                    onShare = {
                        scope.launch {
                            delay(64)
                            shareProfile = stored.profile
                        }
                    },
                    onRename = {
                        renameTarget = stored
                        renameText = stored.profile.name
                    },
                    onDelete = {
                        if (profileSwitchLocked && stored.id == catalog.activeId) {
                            Toast.makeText(
                                context,
                                PROFILE_SWITCH_LOCKED_MESSAGE,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            scope.launch {
                                val wasActive = stored.id == catalog.activeId
                                profiles.delete(stored.id)
                                if (wasActive) {
                                    val next = profiles.snapshot().active
                                    settings.setProfileName(next?.name.orEmpty())
                                    conn.updateProfile(next)
                                }
                                AppLog.i("Profiles", "deleted ${stored.profile.name}")
                            }
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
                val clip = readClipboardText(context)
                if (clip == null) {
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
        ArdttDialog(
            title = "Подписка",
            onDismissRequest = { if (!busy) showSubscription = false },
            confirmAction = ArdttDialogAction(
                text = "Загрузить",
                onClick = {
                    showSubscription = false
                    importResolved(subscriptionUrl.trim(), "Подписка обновлена")
                },
                enabled = subscriptionUrl.isNotBlank() && !busy,
            ),
            dismissAction = ArdttDialogAction("Отмена", { showSubscription = false }, enabled = !busy),
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ) {
            Text(
                "URL JSON на сервере, например http://VPS:9100/v1/profile/имя или список профилей.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttTextField(
                value = subscriptionUrl,
                onValueChange = { subscriptionUrl = it },
                placeholder = "https://…/profile.json",
            )
        }
    }

    if (showPaste) {
        ArdttDialog(
            title = "Импорт вручную",
            onDismissRequest = { if (!busy) showPaste = false },
            confirmAction = ArdttDialogAction(
                text = "Импорт",
                onClick = {
                    showPaste = false
                    importResolved(pasteText)
                },
                enabled = pasteText.isNotBlank() && !busy,
            ),
            dismissAction = ArdttDialogAction("Отмена", { showPaste = false }, enabled = !busy),
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ) {
            Text(
                "JSON, ссылка ardtt:// или URL подписки.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                modifier = Modifier.height(180.dp),
                placeholder = "ardtt://config?… или { \"name\": … }",
                singleLine = false,
            )
        }
    }

    renameTarget?.let { target ->
        ArdttDialog(
            title = "Переименовать",
            onDismissRequest = { renameTarget = null },
            confirmAction = ArdttDialogAction(
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
            dismissAction = ArdttDialogAction("Отмена", { renameTarget = null }),
        ) {
            ArdttTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = "Имя профиля",
            )
        }
    }
}

@Composable
private fun ProfileCard(
    item: StoredProfile,
    active: Boolean,
    selectionLocked: Boolean,
    servers: List<DeployTarget>,
    liveFacts: ProfileLiveFacts?,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val connectEnabled = !selectionLocked || active
    val deleteEnabled = !selectionLocked || !active
    val muted = colors.onSurfaceVariant.copy(alpha = if (selectionLocked && !active) 0.72f else 1f)
    val titleColor = when {
        active -> connectedStatusColor()
        selectionLocked -> colors.onSurface.copy(alpha = 0.62f)
        else -> colors.onSurface
    }
    val facts = profileCardFacts(item.profile, liveFacts)
    val addressHosts = profileCardAddressHosts(item.profile, servers)
    val expiresTone = clientExpiresTone(facts.expiresAt)
    val expiresColor = when (expiresTone) {
        ClientExpiresTone.Unlimited, ClientExpiresTone.Active -> connectedStatusColor()
        ClientExpiresTone.ExpiringSoon -> warningStatusColor()
        ClientExpiresTone.Expired -> colors.error
    }
    ArdttCompactCard(
        modifier = Modifier.clickable(enabled = !selectionLocked, onClick = onSelect),
        border = if (active) {
            BorderStroke(ArdttSectionCardDefaults.ContourWidth, ArdttColors.Connected)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium),
        ) {
            ArdttLeadingIcon(
                painter = painterResource(R.drawable.ic_profile),
                contentDescription = "Профиль",
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ArdttLayout.CompactCardSpacing),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.TinyPlus),
                ) {
                    Text(
                        item.profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Активен",
                            tint = connectedStatusColor(),
                            modifier = Modifier.size(ArdttSize.IconCompact),
                        )
                    }
                    Box {
                        ArdttButton(
                            onClick = { menu = true },
                            variant = ArdttButtonVariant.Icon,
                            icon = Icons.Filled.MoreVert,
                            contentDescription = "Действия",
                            contentColor = if (selectionLocked) {
                                colors.onSurface.copy(alpha = 0.45f)
                            } else {
                                colors.onSurfaceVariant
                            },
                        )
                        ArdttOverflowMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                        ) {
                            ArdttOverflowMenuItem(
                                text = "Подключить",
                                enabled = connectEnabled,
                                leadingIcon = Icons.Filled.VpnKey,
                                onClick = { menu = false; onOpen() },
                            )
                            ArdttOverflowMenuItem(
                                text = "Копировать JSON",
                                leadingIcon = Icons.Filled.ContentCopy,
                                onClick = { menu = false; onCopy() },
                            )
                            ArdttOverflowMenuItem(
                                text = "Ссылка / QR",
                                leadingIcon = Icons.Filled.QrCode,
                                onClick = { menu = false; onShare() },
                            )
                            ArdttOverflowMenuItem(
                                text = "Переименовать",
                                leadingIcon = Icons.Filled.Edit,
                                onClick = { menu = false; onRename() },
                            )
                            ArdttOverflowMenuItem(
                                text = "Удалить",
                                enabled = deleteEnabled,
                                destructive = true,
                                leadingIcon = Icons.Filled.Delete,
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                }
                ArdttIpHostRow(
                    hosts = addressHosts,
                    modifier = Modifier.fillMaxWidth(),
                    muted = muted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
                ) {
                    Text(
                        profileTrafficRemainingLabel(facts.trafficLimitBytes, facts.usedBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ArdttStatusChip(
                        text = formatClientExpires(facts.expiresAt),
                        accent = expiresColor,
                    )
                }
            }
        }
    }
}
