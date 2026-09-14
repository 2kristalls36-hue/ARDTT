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
        val token = obtainAccessTokenViaHttp(cookieHeader, userAgent, transport)
            ?: return@withContext CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.OAuth,
                    message = AUTH_REQUIRED_MESSAGE,
                ),
            )
        startCall(token, transport)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        CallHashOutcome.Failure(classifyCallHashThrowable(t, CallHashPhase.OAuth))
    }
}

private data class HttpHop(val token: String? = null, val nextUrl: String? = null)

private fun obtainAccessTokenViaHttp(
    cookieHeader: String,
    userAgent: String,
    transport: VkApiTransport,
): String? {
    if (cookieHeader.isBlank()) return null
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
        hop.token?.let { return it }
        val next = hop.nextUrl ?: return null
        url = next
    }
    return null
}

private fun parseAuthorizeResponse(response: VkHttpResponse): HttpHop {
    val location = response.location.orEmpty()
    VkCallHashGenerator.extractAccessToken(location)?.let { return HttpHop(token = it) }
    if (location.isNotBlank()) return HttpHop(nextUrl = location)
    if (response.code !in 200..299) return HttpHop()
    val body = response.body
    val href = Regex("""location\.href\s*=\s*["']([^"']+)["']""")
        .find(body)?.groupValues?.getOrNull(1).orEmpty()
    VkCallHashGenerator.extractAccessToken(href)?.let { return HttpHop(token = it) }
    val grantUrl = Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""")
        .find(body)?.groupValues?.getOrNull(1)
        ?.replace("&amp;", "&")
    return HttpHop(nextUrl = grantUrl)
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
    return parseCallsStartBody(response.body).let { outcome ->
        if (outcome is CallHashOutcome.Failure && outcome.error.httpCode == null) {
            CallHashOutcome.Failure(outcome.error.copy(httpCode = response.code))
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
            )
        }
    }
}
