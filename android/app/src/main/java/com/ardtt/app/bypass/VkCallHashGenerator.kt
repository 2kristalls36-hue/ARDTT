package com.ardtt.app.bypass

import android.content.Context
import android.webkit.WebSettings
import com.ardtt.app.telemetry.AppHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request


/**
 * After WebView login (remixsid cookie): OAuth token → calls.start → join hash.
 * Connect Path B stays anonymous via go_client + stored hash.
 *
 * Expected OAuth/API/JSON failures are [CallHashOutcome.Failure]. Only
 * [CancellationException] is thrown to the caller.
 */
object VkCallHashGenerator {
    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun generateOne(context: Context): Result<String> =
        when (val outcome = generateOutcome(context)) {
            is CallHashOutcome.Success -> Result.success(outcome.hash)
            is CallHashOutcome.Failure ->
                Result.failure(IllegalStateException(outcome.error.userMessage))
        }

    suspend fun generateOutcome(context: Context): CallHashOutcome = generateCallHash(
        hasSession = VkSession.hasSessionCookie(),
        userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("ARDTT"),
        cookieHeader = VkSession.cookieHeader(),
        transport = DefaultVkApiTransport,
    )

    internal fun extractAccessToken(url: String): String? {
        if (!url.contains("access_token=")) return null
        val part = when {
            url.contains('#') -> url.substringAfter('#')
            url.contains('?') -> url.substringAfter('?')
            else -> url
        }
        return part.split('&')
            .firstOrNull { it.startsWith("access_token=") }
            ?.substringAfter("access_token=")
            ?.takeIf { it.isNotBlank() }
    }

    fun oauthTokenStartUrl(): String = buildAuthorizeUrl()

    private fun buildAuthorizeUrl(): String {
        val redirect = URLEncoder.encode(REDIRECT_URI, Charsets.UTF_8.name())
        return "https://oauth.vk.com/authorize" +
            "?client_id=$VK_CLIENT_ID" +
            "&display=mobile" +
            "&redirect_uri=$redirect" +
            "&response_type=token" +
            "&scope=messages" +
            "&state=ardtt" +
            "&v=$API_VERSION"
    }
}

internal fun interface VkApiTransport {
    fun get(url: String, cookieHeader: String, userAgent: String): VkHttpResponse
}

internal data class VkHttpResponse(
    val code: Int,
    val body: String,
    val location: String? = null,
    val retryAfterMs: Long? = null,
)

internal suspend fun generateCallHash(
    hasSession: Boolean,
    userAgent: String,
    cookieHeader: String,
    transport: VkApiTransport,
): CallHashOutcome = withContext(Dispatchers.IO) {
    if (!hasSession) {
        return@withContext CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.AuthRequired,
                phase = CallHashPhase.Session,
                message = AUTH_REQUIRED_MESSAGE,
            ),
        )
    }
    try {
        when (val token = obtainAccessTokenViaHttp(cookieHeader, userAgent, transport)) {
            is AccessTokenResult.Fail -> return@withContext CallHashOutcome.Failure(token.error)
            is AccessTokenResult.Ok -> startCall(token.token, transport)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        CallHashOutcome.Failure(classifyCallHashThrowable(t, CallHashPhase.OAuth))
    }
}

private sealed class AccessTokenResult {
    data class Ok(val token: String) : AccessTokenResult()
    data class Fail(val error: CallHashFailure) : AccessTokenResult()
}

private sealed class OAuthHop {
    data class Token(val token: String) : OAuthHop()
    data class Continue(val nextUrl: String) : OAuthHop()
    data class Fail(val error: CallHashFailure) : OAuthHop()
}

private fun obtainAccessTokenViaHttp(
    cookieHeader: String,
    userAgent: String,
    transport: VkApiTransport,
): AccessTokenResult {
    if (cookieHeader.isBlank()) {
        return AccessTokenResult.Fail(
            CallHashFailure(
                kind = CallHashErrorKind.AuthRequired,
                phase = CallHashPhase.OAuth,
                message = AUTH_REQUIRED_MESSAGE,
            ),
        )
    }
    var url = VkCallHashGenerator.oauthTokenStartUrl()
    repeat(12) {
        val hop = try {
            val response = transport.get(url, cookieHeader, userAgent)
            parseAuthorizeResponse(response)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw t
        }
        when (hop) {
            is OAuthHop.Token -> return AccessTokenResult.Ok(hop.token)
            is OAuthHop.Fail -> return AccessTokenResult.Fail(hop.error)
            is OAuthHop.Continue -> url = hop.nextUrl
        }
    }
    return AccessTokenResult.Fail(
        CallHashFailure(
            kind = CallHashErrorKind.AuthRequired,
            phase = CallHashPhase.OAuth,
            message = AUTH_REQUIRED_MESSAGE,
        ),
    )
}

internal fun parseRetryAfterMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val seconds = raw.trim().toLongOrNull() ?: return null
    if (seconds < 0L) return 0L
    return (seconds * 1000L).coerceAtMost(60_000L)
}

internal fun httpStatusFailure(
    code: Int,
    phase: CallHashPhase,
    retryAfterMs: Long?,
    body: String = "",
): CallHashFailure? {
    if (code in 200..399) return null
    if (code == 429 || code in 500..599) {
        return CallHashFailure(
            kind = CallHashErrorKind.TransientNetwork,
            phase = phase,
            message = TRANSIENT_NETWORK_MESSAGE,
            httpCode = code,
            retryAfterMs = retryAfterMs,
        )
    }
    if (code == 401) {
        return CallHashFailure(
            kind = CallHashErrorKind.AuthRequired,
            phase = phase,
            message = AUTH_REQUIRED_MESSAGE,
            httpCode = code,
        )
    }
    if (code == 403) {
        return forbiddenFailure(phase, body, retryAfterMs)
    }
    return CallHashFailure(
        kind = CallHashErrorKind.InvalidResponse,
        phase = phase,
        message = INVALID_RESPONSE_MESSAGE,
        httpCode = code,
        retryAfterMs = retryAfterMs,
    )
}

private fun forbiddenFailure(
    phase: CallHashPhase,
    body: String,
    retryAfterMs: Long?,
): CallHashFailure {
    if (body.isNotBlank()) {
        val parsed = parseCallsStartBody(body)
        if (parsed is CallHashOutcome.Failure) {
            when (parsed.error.kind) {
                CallHashErrorKind.Captcha,
                CallHashErrorKind.AuthRequired,
                -> return parsed.error.copy(httpCode = 403, phase = phase)
                else -> Unit
            }
        }
        val lower = body.lowercase()
        if (lower.contains("captcha")) {
            return CallHashFailure(
                kind = CallHashErrorKind.Captcha,
                phase = phase,
                message = body.take(180),
                httpCode = 403,
            )
        }
    }
    return CallHashFailure(
        kind = CallHashErrorKind.TransientNetwork,
        phase = phase,
        message = TRANSIENT_NETWORK_MESSAGE,
        httpCode = 403,
        retryAfterMs = retryAfterMs,
    )
}

private fun parseAuthorizeResponse(response: VkHttpResponse): OAuthHop {
    val location = response.location.orEmpty()
    VkCallHashGenerator.extractAccessToken(location)?.let { return OAuthHop.Token(it) }
    if (location.isNotBlank()) return OAuthHop.Continue(location)
    httpStatusFailure(
        code = response.code,
        phase = CallHashPhase.OAuth,
        retryAfterMs = response.retryAfterMs,
        body = response.body,
    )?.let { return OAuthHop.Fail(it) }
    val body = response.body
    val href = Regex("""location\.href\s*=\s*["']([^"']+)["']""")
        .find(body)?.groupValues?.getOrNull(1).orEmpty()
    VkCallHashGenerator.extractAccessToken(href)?.let { return OAuthHop.Token(it) }
    val grantUrl = Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""")
        .find(body)?.groupValues?.getOrNull(1)
        ?.replace("&amp;", "&")
    if (!grantUrl.isNullOrBlank()) return OAuthHop.Continue(grantUrl)
    return OAuthHop.Fail(
        CallHashFailure(
            kind = CallHashErrorKind.AuthRequired,
            phase = CallHashPhase.OAuth,
            message = AUTH_REQUIRED_MESSAGE,
            httpCode = response.code,
        ),
    )
}

private fun startCall(accessToken: String, transport: VkApiTransport): CallHashOutcome {
    val url = "https://api.vk.ru/method/calls.start".toHttpUrl().newBuilder()
        .addQueryParameter("access_token", accessToken)
        .addQueryParameter("v", "5.199")
        .build()
        .toString()
    val response = try {
        transport.get(url, cookieHeader = "", userAgent = "")
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        return CallHashOutcome.Failure(classifyCallHashThrowable(t, CallHashPhase.CallsStart))
    }
    httpStatusFailure(
        code = response.code,
        phase = CallHashPhase.CallsStart,
        retryAfterMs = response.retryAfterMs,
        body = response.body,
    )?.let { return CallHashOutcome.Failure(it) }
    return parseCallsStartBody(response.body).let { outcome ->
        if (outcome is CallHashOutcome.Failure && outcome.error.httpCode == null) {
            CallHashOutcome.Failure(
                outcome.error.copy(
                    httpCode = response.code,
                    retryAfterMs = outcome.error.retryAfterMs ?: response.retryAfterMs,
                ),
            )
        } else {
            outcome
        }
    }
}

private object DefaultVkApiTransport : VkApiTransport {
    override fun get(url: String, cookieHeader: String, userAgent: String): VkHttpResponse {
        val client = AppHttpClient.builder()
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).get()
        if (cookieHeader.isNotBlank()) request.header("Cookie", cookieHeader)
        if (userAgent.isNotBlank()) {
            request.header("User-Agent", userAgent)
            request.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        return client.newCall(request.build()).execute().use { response ->
            VkHttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                location = response.header("Location"),
                retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
            )
        }
    }
}
