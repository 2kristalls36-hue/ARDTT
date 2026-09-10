package com.ardtt.app.bypass

/**
 * Dead-call handling for Path B.
 *
 * Architecture: «Звонок мёртв: спросить | тихий recreate».
 * Silent recreate needs a live VK WebView cookie; otherwise ask the user to log in.
 */

enum class BypassFatalKind {
    DeadCall,
    Captcha,
    WrapAuth,
    DialFailed,
}

enum class CallRecreatePrompt {
    /** Ask before creating a new VK call. */
    Ask,
    /** Silent mode, but remixsid is gone — need VK login first. */
    NeedLogin,
}

sealed class DeadCallAction {
    data object SilentRecreate : DeadCallAction()
    data object NeedVkLogin : DeadCallAction()
    data object AskUser : DeadCallAction()
    data object GiveUp : DeadCallAction()
}

fun classifyBypassFatalKind(line: String): BypassFatalKind? {
    val l = line.lowercase()
    return when {
        l.contains("fatal_auth") || l.contains("неверный пароль") ->
            BypassFatalKind.WrapAuth
        isDeadCallLog(l) ->
            BypassFatalKind.DeadCall
        l.contains("captcha") && (l.contains("required") || l.contains("wait")) ->
            BypassFatalKind.Captcha
        l.contains("all vk credentials failed") ->
            BypassFatalKind.DialFailed
        else -> null
    }
}

fun userMessageForBypassFatal(kind: BypassFatalKind): String = when (kind) {
    BypassFatalKind.WrapAuth -> "Неверный пароль обхода (WRAP)"
    BypassFatalKind.DeadCall -> DEAD_CALL_USER_MESSAGE
    BypassFatalKind.Captcha ->
        "Требуется проверка капчи. Выберите способ «Капча» в настройках обхода."
    BypassFatalKind.DialFailed -> "Не удалось получить TURN (vkcalls/legacy)"
}

fun userActionForBypassFailure(message: String): com.ardtt.app.core.UserActionKind? {
    return when (classifyBypassFatalKind(message)) {
        BypassFatalKind.WrapAuth -> com.ardtt.app.core.UserActionKind.Profile
        BypassFatalKind.Captcha -> com.ardtt.app.core.UserActionKind.Captcha
        BypassFatalKind.DeadCall -> com.ardtt.app.core.UserActionKind.CallDead
        BypassFatalKind.DialFailed, null -> null
    }
}

fun isDeadCallMessage(message: String): Boolean = isDeadCallLog(message.lowercase())

fun decideDeadCallAction(
    silentRecreate: Boolean,
    hasVkSession: Boolean,
    recreateAttempts: Int,
    maxAttempts: Int = 1,
): DeadCallAction {
    if (recreateAttempts >= maxAttempts) {
        return DeadCallAction.GiveUp
    }
    if (!silentRecreate) {
        return if (hasVkSession) DeadCallAction.AskUser else DeadCallAction.NeedVkLogin
    }
    return if (hasVkSession) DeadCallAction.SilentRecreate else DeadCallAction.NeedVkLogin
}

internal const val DEAD_CALL_USER_MESSAGE =
    "Звонок не найден или закрыт. Создайте новый код звонка."

private fun isDeadCallLog(lower: String): Boolean {
    if (lower.contains("call_unavailable") || lower.contains("callunavailable")) return true
    if (lower.contains("call is unavailable")) return true
    if (lower.contains("call not found")) return true
    if (lower.contains("хеш мёртв") || lower.contains("хеш мертв")) return true
    if (lower.contains("non-retryable call")) return true
    if (lower.contains("звонок не найден") || lower.contains("звонок закрыт")) return true
    // Same codes as go_client fatalCallError: 951/954 and legacy 9000–9999.
    val code = Regex("""error_code[=:]?\s*(\d+)""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (code == 951 || code == 954) return true
    if (code != null && code in 9000..9999) return true
    return false
}
