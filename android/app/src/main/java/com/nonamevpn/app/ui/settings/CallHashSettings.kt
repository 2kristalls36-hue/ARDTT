package com.nonamevpn.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonamevpn.app.bypass.VkCallHashGenerator
import com.nonamevpn.app.bypass.VkLoginActivity
import com.nonamevpn.app.bypass.VkSession
import com.nonamevpn.app.bypass.VkUrl
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.core.ConnState
import com.nonamevpn.app.core.ConnectionManager
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import com.nonamevpn.app.ui.components.ChoiceChipButton
import com.nonamevpn.app.ui.components.NvpnDialog
import com.nonamevpn.app.ui.components.NvpnDialogAction
import kotlinx.coroutines.launch

@Composable
fun CallHashSettingsCard(
    modifier: Modifier = Modifier,
) {
    AppSectionCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CallHashSettingsContent(showHeader = true)
    }
}

@Composable
fun CallHashSettingsContent(
    showHeader: Boolean,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val conn = remember { ConnectionManager.get(context) }
    val profiles = remember { ProfileRepository(context) }
    val ui by conn.ui.collectAsStateWithLifecycle()
    val profile by profiles.profile.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showManual by remember { mutableStateOf(false) }
    var manualDraft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var vkLoggedIn by remember { mutableStateOf(VkSession.hasSessionCookie()) }
    var vkDisplayName by remember { mutableStateOf<String?>(null) }

    val vpnActive = ui.state == ConnState.Connecting ||
        ui.state == ConnState.Connected ||
        ui.state == ConnState.PausedTrustedWifi ||
        ui.state == ConnState.Disconnecting
    val canEdit = profile != null && !vpnActive && !busy
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(vkLoggedIn) {
        vkDisplayName = if (vkLoggedIn) VkSession.resolveDisplayName() else null
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHeader) {
            Text(
                "Код звонка",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "Нужен для обхода. Хранится на устройстве отдельно от профиля и не сбрасывается при обновлении.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vkLoggedIn) {
            Text(
                "Выполнен вход под: ${vkDisplayName?.takeIf { it.isNotBlank() } ?: "аккаунт ВКонтакте"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Выполняется создание кода…"
                        val r = VkCallHashGenerator.generateOne(context)
                        busy = false
                        vkLoggedIn = VkSession.hasSessionCookie()
                        r.onSuccess { hash ->
                            conn.saveCallHash(hash)
                            message = "Код звонка сохранён."
                        }.onFailure { e ->
                            message = e.message ?: "Не удалось создать код звонка."
                        }
                    }
                },
                enabled = canEdit && vkLoggedIn,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Создать код")
            }
            OutlinedButton(
                onClick = {
                    manualDraft = conn.callHashOrNull().orEmpty()
                    showManual = true
                },
                enabled = canEdit,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Ввести вручную")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceChipButton(
                label = "Авторизация",
                selected = !vkLoggedIn,
                enabled = canEdit,
                onClick = {
                    if (vkLoggedIn || !canEdit) return@ChoiceChipButton
                    scope.launch {
                        busy = true
                        message = "Открывается авторизация ВКонтакте…"
                        AppLog.i("VK", "Settings login")
                        val activityCtx = context.findActivity() ?: context
                        val r = runCatching { VkLoginActivity.login(activityCtx) }
                            .getOrElse { Result.failure(it) }
                        busy = false
                        vkLoggedIn = VkSession.hasSessionCookie()
                        message = when {
                            r.isSuccess && vkLoggedIn ->
                                "Вход выполнен. Создайте код звонка."
                            r.isSuccess -> "Сессия не подтверждена. Повторите вход."
                            else -> r.exceptionOrNull()?.message ?: "Авторизация отменена."
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ChoiceChipButton(
                label = "Завершить сессию",
                selected = vkLoggedIn,
                enabled = !vpnActive && !busy,
                selectedContainer = colors.error,
                onClick = {
                    if (!vkLoggedIn || vpnActive || busy) return@ChoiceChipButton
                    VkSession.clear()
                    vkLoggedIn = false
                    vkDisplayName = null
                    message = "Сессия ВКонтакте завершена."
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            when {
                vpnActive -> "Недоступно во время соединения."
                !message.isNullOrBlank() -> message.orEmpty()
                ui.hasCallHash -> "Код сохранён на этом устройстве."
                vkLoggedIn -> "Вход выполнен. Создайте код звонка."
                profile == null -> "Сначала выберите профиль."
                else -> "Код не задан."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (showManual) {
        CallHashDialog(
            value = manualDraft,
            onValueChange = { manualDraft = it },
            onDismiss = { showManual = false },
            onSave = { raw ->
                val cleaned = VkUrl.strip(raw)
                if (VkUrl.isPlausibleHash(cleaned)) {
                    conn.saveCallHash(cleaned)
                    showManual = false
                    message = "Код звонка сохранён."
                } else {
                    message = "Указано недопустимое значение кода."
                }
            },
            onClear = {
                conn.clearCallHash()
                showManual = false
                message = "Код звонка удалён."
            },
            onCopy = {
                if (manualDraft.isBlank()) return@CallHashDialog
                clipboard.setText(AnnotatedString(manualDraft))
                Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun CallHashDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    NvpnDialog(
        title = "Код звонка",
        onDismissRequest = onDismiss,
        confirmAction = NvpnDialogAction(
            text = "Сохранить",
            onClick = { onSave(value) },
            enabled = value.isNotBlank(),
        ),
        dismissAction = NvpnDialogAction("Отмена", onDismiss),
        secondaryAction = NvpnDialogAction(
            text = "Удалить",
            onClick = onClear,
            destructive = true,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Укажите ссылку вида vk.com/call/join/… либо сам код.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
            )
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                enabled = value.isNotBlank(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("Копировать", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
