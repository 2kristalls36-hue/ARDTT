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
import com.ardtt.app.ui.components.surface.ArdttConfirmDialog
import com.ardtt.app.ui.components.surface.ArdttDialog
import com.ardtt.app.ui.components.surface.ArdttDialogAction
import com.ardtt.app.ui.components.surface.ArdttFloatingShell
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
    var showEndVkSessionConfirm by remember { mutableStateOf(false) }
    var codeNotice by remember { mutableStateOf<String?>(null) }
    var sessionNotice by remember { mutableStateOf<String?>(null) }
    var vkLoggedIn by remember { mutableStateOf(VkSession.hasSessionCookie()) }

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
        Text(
            bypassCodeNotice(
                vpnActive = vpnActive,
                transient = codeNotice,
                hasCallHash = ui.hasCallHash,
                hasProfile = profile != null,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ArdttSpacing.Small),
        ) {
            ArdttButton(
                text = "Создать код",
                onClick = {
                    scope.launch {
                        busy = true
                        sessionNotice = null
                        codeNotice = "Выполняется создание кода…"
                        val r = VkCallHashGenerator.generateOne(context)
                        busy = false
                        refreshVkSession()
                        r.onSuccess { hash ->
                            conn.saveCallHash(hash)
                            codeNotice = "Код звонка сохранён."
                        }.onFailure { e ->
                            codeNotice = e.message ?: "Не удалось создать код звонка."
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
        bypassLoginNotice(loggedIn = vkLoggedIn, transient = sessionNotice)?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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
                    showEndVkSessionConfirm = true
                    return@ArdttButton
                }
                scope.launch {
                    val startedLoggedIn = vkLoggedIn
                    busy = true
                    codeNotice = null
                    sessionNotice = "Открывается авторизация ВКонтакте…"
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
                    sessionNotice = when {
                        r.isSuccess && vkLoggedIn -> null
                        r.isSuccess -> "Сессия не подтверждена. Повторите вход."
                        else -> r.exceptionOrNull()?.message ?: "Авторизация отменена."
                    }
                }
            },
            enabled = sessionAction.enabled,
            busy = busy,
            fillMaxWidth = sessionAction.fillMaxWidth,
            variant = if (sessionAction.destructive) {
                ArdttButtonVariant.Danger
            } else {
                ArdttButtonVariant.Primary
            },
            // Same danger fill while the tunnel keeps the button disabled.
            containerColor = if (sessionAction.destructive) {
                ArdttFloatingShell.opaqueGlassFill(
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.surface,
                )
            } else {
                null
            },
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
                    sessionNotice = null
                    codeNotice = "Код звонка сохранён."
                } else {
                    sessionNotice = null
                    codeNotice = "Указано недопустимое значение кода."
                }
            },
            onClear = {
                conn.clearCallHash()
                showManual = false
                sessionNotice = null
                codeNotice = "Код звонка удалён."
            },
            onCopy = {
                if (manualDraft.isBlank()) return@CallHashDialog
                clipboard.setText(AnnotatedString(manualDraft))
                Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showEndVkSessionConfirm) {
        ArdttConfirmDialog(
            title = "Завершить сессию ВКонтакте?",
            body = "Привязка аккаунта будет удалена с устройства. Автоматическое обновление кода звонка перестанет работать до следующей авторизации.",
            confirmText = "Завершить",
            busy = busy,
            onConfirm = {
                scope.launch {
                    busy = true
                    VkSession.clear()
                    refreshVkSession()
                    busy = false
                    showEndVkSessionConfirm = false
                    codeNotice = null
                    sessionNotice = if (!vkLoggedIn) {
                        "Сессия ВКонтакте завершена."
                    } else {
                        "Не удалось очистить сессию. Повторите."
                    }
                }
            },
            onDismiss = { if (!busy) showEndVkSessionConfirm = false },
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

internal enum class BypassMethodBlock {
    CodeNotice,
    CodeActions,
    LoginNotice,
    SessionAction,
}

/** Saved-code line, then the two code buttons, then login line, then end-session. */
internal fun bypassMethodBlockOrder(): List<BypassMethodBlock> = listOf(
    BypassMethodBlock.CodeNotice,
    BypassMethodBlock.CodeActions,
    BypassMethodBlock.LoginNotice,
    BypassMethodBlock.SessionAction,
)

internal object BypassMethodCopy {
    const val CODE_SAVED = "Код сохранён на этом устройстве."
    const val CODE_MISSING = "Код не задан."
    const val NEED_PROFILE = "Сначала выберите профиль."
    const val VPN_LOCKED = "Недоступно во время соединения."
    const val LOGIN_DONE = "Вход выполнен"
}

internal fun bypassCodeNotice(
    vpnActive: Boolean,
    transient: String?,
    hasCallHash: Boolean,
    hasProfile: Boolean,
): String = when {
    vpnActive -> BypassMethodCopy.VPN_LOCKED
    !transient.isNullOrBlank() -> transient
    hasCallHash -> BypassMethodCopy.CODE_SAVED
    !hasProfile -> BypassMethodCopy.NEED_PROFILE
    else -> BypassMethodCopy.CODE_MISSING
}

/** Steady «Вход выполнен», or a login/logout message. Absent until there is a session. */
internal fun bypassLoginNotice(loggedIn: Boolean, transient: String?): String? = when {
    !transient.isNullOrBlank() -> transient
    loggedIn -> BypassMethodCopy.LOGIN_DONE
    else -> null
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
