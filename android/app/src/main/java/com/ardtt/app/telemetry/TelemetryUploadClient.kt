package com.ardtt.app.telemetry

import android.content.Context
import com.ardtt.app.core.pickBestUnderlayNetwork
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import org.json.JSONObject

data class TelemetryUploadResult(
    val ticket: Int,
    val filename: String,
    val bytes: Long = 0,
    val read: Boolean = false,
    val comment: String = "",
)

class TelemetryUploadClient {
    suspend fun upload(
        context: Context,
        file: File,
        clientId: String,
        uploadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<TelemetryUploadResult> = withContext(Dispatchers.IO) {
        if (uploadUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("URL загрузки не задан"))
        }
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_RETRIES) {
            attempt++
            val result = runCatching {
                uploadOnce(context, file, clientId, uploadUrl, onProgress)
            }
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull()
            if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt)
        }
        Result.failure(
            IllegalStateException(
                friendlyError(lastError),
                lastError,
            ),
        )
    }

    suspend fun fetchInbox(
        context: Context,
        clientId: String,
        uploadUrl: String,
    ): Result<List<TestingTicketStatus>> = withContext(Dispatchers.IO) {
        if (uploadUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("URL загрузки не задан"))
        }
        val url = telemetryClientStatusUrl(uploadUrl, clientId)
        if (url.isBlank()) {
            return@withContext Result.failure(IllegalStateException("URL статуса логов не задан"))
        }
        runCatching {
            val request = Request.Builder().url(url).get().build()
            execute(context, request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Сервер логов вернул ${response.code}" +
                            if (body.isNotBlank()) ": ${body.take(200)}" else "",
                    )
                }
                parseClientInbox(body)
            }
        }.recoverCatching { first ->
            if (!isNetworkFailure(first)) throw first
            val request = Request.Builder().url(url).get().build()
            defaultClient().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Сервер логов вернул ${response.code}" +
                            if (body.isNotBlank()) ": ${body.take(200)}" else "",
                    )
                }
                parseClientInbox(body)
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(IllegalStateException(friendlyError(it), it)) },
        )
    }

    private fun uploadOnce(
        context: Context,
        file: File,
        clientId: String,
        uploadUrl: String,
        onProgress: (Float) -> Unit,
    ): TelemetryUploadResult {
        val total = file.length().coerceAtLeast(1)
        val fileBody = ProgressRequestBody(file, "application/json".toMediaType(), total, onProgress)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("client_id", clientId)
            .addFormDataPart("file", file.name, fileBody)
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(multipart)
            .build()

        onProgress(0.05f)
        return try {
            readUpload(execute(context, request), file.name, onProgress)
        } catch (t: Throwable) {
            if (pickBestUnderlayNetwork(context) != null && isNetworkFailure(t)) {
                readUpload(defaultClient().newCall(request).execute(), file.name, onProgress)
            } else {
                throw t
            }
        }
    }

    private fun readUpload(
        response: Response,
        fallbackName: String,
        onProgress: (Float) -> Unit,
    ): TelemetryUploadResult = response.use {
        onProgress(1f)
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) {
            throw IllegalStateException(
                "Сервер логов вернул ${it.code}" +
                    if (body.isNotBlank()) ": ${body.take(200)}" else "",
            )
        }
        parseUploadResponse(body, fallbackName)
    }

    private fun execute(context: Context, request: Request): Response {
        val underlay = pickBestUnderlayNetwork(context)
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
        if (underlay != null) {
            builder.socketFactory(underlay.socketFactory)
        }
        return builder.build().newCall(request).execute()
    }

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    private fun isNetworkFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                is ConnectException, is SocketTimeoutException, is UnknownHostException, is IOException ->
                    return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun friendlyError(t: Throwable?): String {
        val msg = t?.message.orEmpty()
        return when {
            t is ConnectException || msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("ECONNREFUSED", ignoreCase = true) ->
                "Сервер логов недоступен (:9200). Проверьте, что на VPS запущен ardtt-telemetry."
            t is SocketTimeoutException || msg.contains("timeout", ignoreCase = true) ->
                "Таймаут отправки лога. Повторите при стабильной сети."
            t is UnknownHostException ->
                "Не удалось разрешить адрес сервера логов."
            msg.isNotBlank() -> msg
            else -> "Не удалось отправить лог"
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2_000L
    }
}

internal fun telemetryApiBase(uploadUrl: String): String {
    val trimmed = uploadUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.endsWith("/api/upload-log") -> trimmed.removeSuffix("/api/upload-log")
        "/api/" in trimmed -> trimmed.substringBefore("/api/")
        else -> trimmed
    }
}

internal fun telemetryClientStatusUrl(uploadUrl: String, clientId: String): String {
    val base = telemetryApiBase(uploadUrl)
    val id = clientId.trim()
    if (base.isEmpty() || id.isEmpty()) return ""
    return "$base/api/logs/$id/status"
}

internal fun parseUploadResponse(raw: String, fallbackName: String): TelemetryUploadResult {
    val json = runCatching { JSONObject(raw) }.getOrNull()
    if (json == null) {
        return TelemetryUploadResult(ticket = 0, filename = fallbackName)
    }
    return TelemetryUploadResult(
        ticket = json.optInt("ticket"),
        filename = json.optString("filename").ifBlank { fallbackName },
        bytes = json.optLong("bytes"),
        read = json.optBoolean("read"),
        comment = json.optString("comment"),
    )
}

internal fun parseClientInbox(raw: String): List<TestingTicketStatus> {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
    val arr = json.optJSONArray("logs") ?: return emptyList()
    val out = ArrayList<TestingTicketStatus>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val name = item.optString("filename").trim()
        if (name.isEmpty()) continue
        val review = item.optJSONObject("review")
        out += TestingTicketStatus(
            logName = name,
            number = item.optInt("ticket"),
            read = item.optBoolean("read") || review?.optBoolean("read") == true,
            comment = item.optString("comment"),
            processedAt = review?.optString("processed_at").orEmpty()
                .ifBlank { item.optString("processed_at") },
            processedBy = review?.optString("processed_by").orEmpty()
                .ifBlank { item.optString("processed_by") },
            reviewNote = review?.optString("note").orEmpty(),
            uploadedAtMs = parseTelemetryTimeMs(item.optString("uploaded_at")),
        )
    }
    return out
}

private class ProgressRequestBody(
    private val file: File,
    private val mediaType: okhttp3.MediaType,
    private val totalBytes: Long,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType() = mediaType

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.source().use { source ->
            val buffer = Buffer()
            var uploaded = 0L
            var read: Long
            while (source.read(buffer, 8_192).also { read = it } != -1L) {
                sink.write(buffer, read)
                uploaded += read
                onProgress((uploaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f))
            }
        }
    }
}
