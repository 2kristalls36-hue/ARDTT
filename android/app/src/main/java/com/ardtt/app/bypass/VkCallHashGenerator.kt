package com.ardtt.app.bypass

import android.content.Context
import android.webkit.WebSettings
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.ardtt.app.telemetry.AppHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * After WebView login (remixsid cookie): OAuth token → calls.start → join hash.
 * Connect Path B stays anonymous via go_client + stored hash.
 */
object VkCallHashGenerator {
    private const val VK_CLIENT_ID = "6287487"
    private const val API_VERSION = "5.199"
    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    suspend fun generateOne(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (!VkSession.hasSessionCookie()) {
            return@withContext Result.failure(IllegalStateException("Требуется авторизация во ВКонтакте"))
        }
        val token = obtainAccessTokenViaHttp(context)
            ?: runCatching { VkLoginActivity.awaitAccessToken(context) }.getOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException("Не удалось получить токен ВКонтакте. Повторите авторизацию."),
            )
        val joinLink = startCall(token)
            ?: return@withContext Result.failure(IllegalStateException("Не удалось создать звонок ВКонтакте"))
        val hash = VkUrl.strip(joinLink)
        if (!VkUrl.isPlausibleHash(hash)) {
            return@withContext Result.failure(IllegalStateException("Получена некорректная ссылка на звонок"))
        }
        Result.success(hash)
    }

    private data class HttpHop(val token: String? = null, val nextUrl: String? = null)

    private fun obtainAccessTokenViaHttp(context: Context): String? {
        val cookieHeader = VkSession.cookieHeader()
        if (cookieHeader.isBlank()) return null
        val client = AppHttpClient.builder()
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val ua = WebSettings.getDefaultUserAgent(context)
        var url = buildAuthorizeUrl()
        repeat(12) {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            val hop = client.newCall(request).execute().use { parseAuthorizeResponse(it) }
            hop.token?.let { return it }
            val next = hop.nextUrl ?: return null
            url = next
        }
        return null
    }

    private fun parseAuthorizeResponse(response: okhttp3.Response): HttpHop {
        val location = response.header("Location").orEmpty()
        extractAccessToken(location)?.let { return HttpHop(token = it) }
        if (location.isNotBlank()) return HttpHop(nextUrl = location)
        if (!response.isSuccessful) return HttpHop()
        val body = response.body?.string().orEmpty()
        val href = Regex("""location\.href\s*=\s*["']([^"']+)["']""")
            .find(body)?.groupValues?.getOrNull(1).orEmpty()
        extractAccessToken(href)?.let { return HttpHop(token = it) }
        val grantUrl = Regex("""(https://login\.vk\.com/\?act=grant_access[^"'\\s<]+)""")
            .find(body)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
        return HttpHop(nextUrl = grantUrl)
    }

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

    private fun startCall(accessToken: String): String? {
        val url = "https://api.vk.ru/method/calls.start".toHttpUrl().newBuilder()
            .addQueryParameter("access_token", accessToken)
            .addQueryParameter("v", API_VERSION)
            .build()
        val client = AppHttpClient.builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val body = client.newCall(Request.Builder().url(url).get().build()).execute().use {
            it.body?.string().orEmpty()
        }
        if (body.isBlank()) return null
        val json = JSONObject(body)
        if (json.has("error")) {
            val err = json.getJSONObject("error")
            throw IllegalStateException(err.optString("error_msg", "VK API error"))
        }
        return json.optJSONObject("response")?.optString("join_link")?.takeIf { it.isNotBlank() }
    }
}
