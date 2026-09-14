package com.ardtt.app.bypass

import com.ardtt.app.core.CallValidity
import com.ardtt.app.core.ConnState
import com.ardtt.app.core.RecoverySettings
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

enum class CallHashPhase {
    Session,
    OAuth,
    CallsStart,
    Parse,
}

enum class CallHashErrorKind {
    TransientNetwork,
    AuthRequired,
    Captcha,
    ApiError,
    InvalidResponse,
}

data class CallHashFailure(
    val kind: CallHashErrorKind,
    val phase: CallHashPhase,
    val message: String,
    val httpCode: Int? = null,
    val apiCode: Int? = null,
) {
    val userMessage: String
        get() = when (kind) {
            CallHashErrorKind.TransientNetwork -> TRANSIENT_NETWORK_MESSAGE
            CallHashErrorKind.AuthRequired -> AUTH_REQUIRED_MESSAGE
            CallHashErrorKind.Captcha ->
                "Требуется проверка капчи. Выберите способ «Капча» в настройках обхода."
            CallHashErrorKind.ApiError ->
                message.takeIf { it.isNotBlank() } ?: "ВКонтакте отклонил создание звонка"
            CallHashErrorKind.InvalidResponse -> INVALID_RESPONSE_MESSAGE
        }
}

sealed class CallHashOutcome {
    data class Success(val hash: String) : CallHashOutcome()
    data class Failure(val error: CallHashFailure) : CallHashOutcome()
}

data class CallRecreateIdentity(
    val sessionEpoch: Long,
    val generation: Long,
    val profileId: String?,
    val callEpoch: Long,
    val requestId: Long?,
    val wantsConnected: Boolean,
)

sealed class CallRecreateDecision {
    data class ApplyHash(val hash: String) : CallRecreateDecision()
    data class WaitAndRetry(
        val delayMs: Long,
        val status: String,
        val validity: CallValidity,
    ) : CallRecreateDecision()
    data class UserAction(
        val prompt: CallRecreatePrompt,
        val message: String,
        val validity: CallValidity,
        val stopTunnel: Boolean,
    ) : CallRecreateDecision()
    data object IgnoreStale : CallRecreateDecision()
    data object IgnoreCancelled : CallRecreateDecision()
}

fun callRecreateIdentityStillCurrent(
    captured: CallRecreateIdentity,
    live: CallRecreateIdentity,
): Boolean {
    if (!live.wantsConnected) return false
    if (captured.sessionEpoch != live.sessionEpoch) return false
    if (captured.generation != live.generation) return false
    if (captured.callEpoch != live.callEpoch) return false
    if (
        captured.profileId != null &&
        live.profileId != null &&
        captured.profileId != live.profileId
    ) {
        return false
    }
    if (captured.requestId != null && live.requestId != captured.requestId) return false
    return true
}

data class CallRecreateApplyPlan(
    val saveHash: String? = null,
    val reconnectBypass: Boolean = false,
    val retryDelayMs: Long? = null,
    val uiState: ConnState? = null,
    val status: String? = null,
    val prompt: CallRecreatePrompt? = null,
    val dispatchValidity: CallValidity? = null,
    val stopTunnel: Boolean = false,
    val endUserAttempt: Boolean = false,
    val keepWantsConnected: Boolean = true,
    val decisionName: String,
)

fun planCallRecreateApply(
    decision: CallRecreateDecision,
    underlayAllowsOps: Boolean,
    silentRecreateInFlight: Boolean,
): CallRecreateApplyPlan {
    return when (decision) {
        is CallRecreateDecision.ApplyHash -> CallRecreateApplyPlan(
            saveHash = decision.hash,
            reconnectBypass = true,
            keepWantsConnected = true,
            decisionName = "apply_hash",
        )
        is CallRecreateDecision.WaitAndRetry -> CallRecreateApplyPlan(
            retryDelayMs = decision.delayMs,
            uiState = if (underlayAllowsOps) {
                ConnState.Recovering
            } else {
                ConnState.WaitingForNetwork
            },
            status = decision.status,
            dispatchValidity = if (underlayAllowsOps) null else decision.validity,
            keepWantsConnected = true,
            decisionName = "wait_retry",
        )
        is CallRecreateDecision.UserAction -> CallRecreateApplyPlan(
            uiState = ConnState.NeedsUserAction,
            status = decision.message,
            prompt = decision.prompt,
            dispatchValidity = when {
                decision.validity == CallValidity.ConfirmedDead && silentRecreateInFlight -> null
                else -> decision.validity
            },
            stopTunnel = decision.stopTunnel,
            endUserAttempt = false,
            keepWantsConnected = true,
            decisionName = "user_action",
        )
        CallRecreateDecision.IgnoreStale -> CallRecreateApplyPlan(
            keepWantsConnected = true,
            decisionName = "ignore_stale",
        )
        CallRecreateDecision.IgnoreCancelled -> CallRecreateApplyPlan(
            keepWantsConnected = false,
            decisionName = "ignore_cancelled",
        )
    }
}

fun evaluateCallRecreateResult(
    captured: CallRecreateIdentity,
    live: CallRecreateIdentity,
    outcome: CallHashOutcome,
    networkAttempts: Int,
    maxNetworkAttempts: Int = RecoverySettings.CALL_RECREATE_NETWORK_ATTEMPTS,
): CallRecreateDecision {
    if (!live.wantsConnected) return CallRecreateDecision.IgnoreCancelled
    if (!callRecreateIdentityStillCurrent(captured, live)) {
        return CallRecreateDecision.IgnoreStale
    }
    return when (outcome) {
        is CallHashOutcome.Success -> CallRecreateDecision.ApplyHash(outcome.hash)
        is CallHashOutcome.Failure -> decideCallHashFailure(
            error = outcome.error,
            networkAttempts = networkAttempts,
            maxNetworkAttempts = maxNetworkAttempts,
        )
    }
}

fun classifyCallHashThrowable(error: Throwable, phase: CallHashPhase): CallHashFailure {
    if (error is CancellationException) throw error
    val kind = when (error) {
        is SocketTimeoutException,
        is UnknownHostException,
        is SSLException,
        is IOException,
        -> CallHashErrorKind.TransientNetwork
        is JSONException -> CallHashErrorKind.InvalidResponse
        else -> CallHashErrorKind.ApiError
    }
    return CallHashFailure(
        kind = kind,
        phase = phase,
        message = safeCallHashErrorMessage(error),
    )
}

internal fun parseCallsStartBody(body: String): CallHashOutcome {
    if (body.isBlank()) {
        return CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.InvalidResponse,
                phase = CallHashPhase.CallsStart,
                message = INVALID_RESPONSE_MESSAGE,
            ),
        )
    }
    val json = try {
        JSONObject(body)
    } catch (_: JSONException) {
        return CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.InvalidResponse,
                phase = CallHashPhase.Parse,
                message = INVALID_RESPONSE_MESSAGE,
            ),
        )
    }
    if (json.has("error")) {
        val err = json.optJSONObject("error")
        val apiCode = err?.optInt("error_code", 0)?.takeIf { it != 0 }
        val raw = err?.optString("error_msg").orEmpty()
        val kind = vkApiErrorKind(apiCode, raw)
        return CallHashOutcome.Failure(
            CallHashFailure(
                kind = kind,
                phase = CallHashPhase.CallsStart,
                message = raw.ifBlank { "VK API error" },
                apiCode = apiCode,
            ),
        )
    }
    val joinLink = json.optJSONObject("response")?.optString("join_link").orEmpty()
    if (joinLink.isBlank()) {
        return CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.InvalidResponse,
                phase = CallHashPhase.Parse,
                message = INVALID_RESPONSE_MESSAGE,
            ),
        )
    }
    val hash = VkUrl.strip(joinLink)
    if (!VkUrl.isPlausibleHash(hash)) {
        return CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.InvalidResponse,
                phase = CallHashPhase.Parse,
                message = INVALID_RESPONSE_MESSAGE,
            ),
        )
    }
    return CallHashOutcome.Success(hash)
}

internal fun vkApiErrorKind(apiCode: Int?, message: String): CallHashErrorKind {
    val lower = message.lowercase()
    if (apiCode == 14 || lower.contains("captcha")) return CallHashErrorKind.Captcha
    if (apiCode == 5 || apiCode == 1117 || lower.contains("authorization") || lower.contains("access_token")) {
        return CallHashErrorKind.AuthRequired
    }
    if (apiCode == 1 || apiCode == 6 || apiCode == 9 || apiCode == 10 || apiCode == 29) {
        return CallHashErrorKind.TransientNetwork
    }
    return CallHashErrorKind.ApiError
}

private fun decideCallHashFailure(
    error: CallHashFailure,
    networkAttempts: Int,
    maxNetworkAttempts: Int,
): CallRecreateDecision {
    return when (error.kind) {
        CallHashErrorKind.TransientNetwork -> {
            val next = networkAttempts + 1
            if (next < maxNetworkAttempts) {
                CallRecreateDecision.WaitAndRetry(
                    delayMs = RecoverySettings.retryDelayMs(networkAttempts),
                    status = "${TRANSIENT_NETWORK_MESSAGE}. Повтор через ${RecoverySettings.retryDelayMs(networkAttempts) / 1000} с",
                    validity = CallValidity.UnknownDueToNetwork,
                )
            } else {
                CallRecreateDecision.UserAction(
                    prompt = CallRecreatePrompt.Ask,
                    message = TRANSIENT_NETWORK_MESSAGE,
                    validity = CallValidity.UnknownDueToNetwork,
                    stopTunnel = false,
                )
            }
        }
        CallHashErrorKind.AuthRequired -> CallRecreateDecision.UserAction(
            prompt = CallRecreatePrompt.NeedLogin,
            message = error.userMessage,
            validity = CallValidity.NeedsAuth,
            stopTunnel = false,
        )
        CallHashErrorKind.Captcha -> CallRecreateDecision.UserAction(
            prompt = CallRecreatePrompt.Ask,
            message = error.userMessage,
            validity = CallValidity.NeedsAuth,
            stopTunnel = false,
        )
        CallHashErrorKind.ApiError,
        CallHashErrorKind.InvalidResponse,
        -> CallRecreateDecision.UserAction(
            prompt = CallRecreatePrompt.Ask,
            message = error.userMessage,
            validity = CallValidity.ConfirmedDead,
            stopTunnel = false,
        )
    }
}

private fun safeCallHashErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    val cut = raw.substringBefore('?').substringBefore('#')
    return cut.take(180).ifBlank { error.javaClass.simpleName }
}

internal const val TRANSIENT_NETWORK_MESSAGE = "Нет связи с ВКонтакте"
internal const val AUTH_REQUIRED_MESSAGE = "Нужна авторизация ВКонтакте"
internal const val INVALID_RESPONSE_MESSAGE = "Некорректный ответ ВКонтакте"
