package com.ardtt.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ardtt.app.bypass.VkCallHashGenerator
import com.ardtt.app.bypass.VkLoginActivity
import com.ardtt.app.bypass.VkSession
import com.ardtt.app.bypass.VkUrl
import com.ardtt.app.bypass.vkSessionAction
import com.ardtt.app.bypass.vkShouldClearPartialSession
import com.ardtt.app.core.AppLog
import com.ardtt.app.core.holdsUserSession
import com.ardtt.app.core.ConnectionManager
import com.ardtt.app.profile.ProfileRepository
import com.ardtt.app.ui.components.control.ArdttButton
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ArdttTextField
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttSectionTitle
import com.ardtt.app.ui.theme.ArdttSpacing
import kotlinx.coroutines.launch

@Composable
fun CallHashSettingsContent(
    showHeader: Boolean,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    val vpnActive = ui.state.holdsUserSession()
    val canEdit = profile != null && !vpnActive && !busy

    fun refreshVkSession() {
        vkLoggedIn = VkSession.hasSessionCookie()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshVkSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vkLoggedIn) {
        vkDisplayName = if (vkLoggedIn) VkSession.resolveDisplayName() else null
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(ArdttSpacing.SmallPlus),
    ) {
        if (showHeader) {
            ArdttSectionTitle("Код звонка")
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
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        ) {
            ArdttButton(
                text = "Создать код",
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Выполняется создание кода…"
                        val r = VkCallHashGenerator.generateOne(context)
                        busy = false
                        refreshVkSession()
                        r.onSuccess { hash ->
                            conn.saveCallHash(hash)
                            message = "Код звонка сохранён."
                        }.onFailure { e ->
                            message = e.message ?: "Не удалось создать код звонка."
                        }
                    }
                },
                enabled = canEdit && vkLoggedIn,
                variant = ArdttButtonVariant.Outlined,
                size = ArdttButtonSize.Compact,
                fillMaxWidth = false,
                modifier = Modifier.weight(1f),
            )
            ArdttButton(
                text = "Ввести вручную",
                onClick = {
                    manualDraft = conn.callHashOrNull().orEmpty()
                    showManual = true
                },
                enabled = canEdit,
                variant = ArdttButtonVariant.Outlined,
                size = ArdttButtonSize.Compact,
                fillMaxWidth = false,
                modifier = Modifier.weight(1f),
            )
        }
        val sessionAction = vkSessionAction(
            loggedIn = vkLoggedIn,
            vpnActive = vpnActive,
            busy = busy,
            hasProfile = profile != null,
        )
        ArdttButton(
            text = sessionAction.label,
            onClick = {
                if (!sessionAction.enabled) return@ArdttButton
                if (sessionAction.destructive) {
                    scope.launch {
                        busy = true
                        VkSession.clear()
                        refreshVkSession()
                        vkDisplayName = null
                        busy = false
                        message = if (!vkLoggedIn) {
                            "Сессия ВКонтакте завершена."
                        } else {
                            "Не удалось очистить сессию. Повторите."
                        }
                    }
                    return@ArdttButton
                }
                scope.launch {
                    val startedLoggedIn = vkLoggedIn
                    busy = true
                    message = "Открывается авторизация ВКонтакте…"
                    AppLog.i("VK", "Settings login")
                    val activityCtx = context.findActivity() ?: context
                    val r = runCatching { VkLoginActivity.login(activityCtx) }
                        .getOrElse { Result.failure(it) }
                    if (vkShouldClearPartialSession(startedLoggedIn, r.isSuccess)) {
                        // Drop partial remixsid so the CTA stays «Авторизация».
                        VkSession.clear()
                    }
                    refreshVkSession()
                    busy = false
                    message = when {
                        r.isSuccess && vkLoggedIn ->
                            "Вход выполнен. Создайте код звонка."
                        r.isSuccess -> "Сессия не подтверждена. Повторите вход."
                        else -> r.exceptionOrNull()?.message ?: "Авторизация отменена."
                    }
                }
            },
            enabled = sessionAction.enabled,
            busy = busy,
            variant = if (sessionAction.destructive) {
                ArdttButtonVariant.Danger
            } else {
                ArdttButtonVariant.Primary
            },
        )
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
    ArdttDialog(
        title = "Код звонка",
        onDismissRequest = onDismiss,
        confirmAction = ArdttDialogAction(
            text = "Сохранить",
            onClick = { onSave(value) },
            enabled = value.isNotBlank(),
        ),
        dismissAction = ArdttDialogAction("Отмена", onDismiss),
        secondaryAction = ArdttDialogAction(
            text = "Удалить",
            onClick = onClear,
            destructive = true,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ArdttSpacing.Medium)) {
            Text(
                "Укажите ссылку вида vk.com/call/join/… либо сам код.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArdttTextField(
                value = value,
                onValueChange = onValueChange,
            )
            ArdttButton(
                text = "Копировать",
                onClick = onCopy,
                enabled = value.isNotBlank(),
                variant = ArdttButtonVariant.Outlined,
                icon = Icons.Default.ContentCopy,
            )
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
