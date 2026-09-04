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
import okio.Buffer
import okio.BufferedSink
import okio.source

class TelemetryUploadClient {
    suspend fun upload(
        context: Context,
        file: File,
        clientId: String,
        uploadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
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

    private fun uploadOnce(
        context: Context,
        file: File,
        clientId: String,
        uploadUrl: String,
        onProgress: (Float) -> Unit,
    ) {
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
        // Prefer underlay so upload works while the tunnel is up / broken.
        val underlay = pickBestUnderlayNetwork(context)
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
        if (underlay != null) {
            clientBuilder.socketFactory(underlay.socketFactory)
        }
        val client = clientBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                onProgress(1f)
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(200)
                    throw IllegalStateException(
                        "Сервер логов вернул ${response.code}" +
                            if (body.isNotBlank()) ": $body" else "",
                    )
                }
            }
        } catch (t: Throwable) {
            // Retry once on default route if underlay bind failed.
            if (underlay != null && isNetworkFailure(t)) {
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        onProgress(1f)
                        if (!response.isSuccessful) {
                            val body = response.body?.string().orEmpty().take(200)
                            throw IllegalStateException(
                                "Сервер логов вернул ${response.code}" +
                                    if (body.isNotBlank()) ": $body" else "",
                            )
                        }
                    }
            } else {
                throw t
            }
        }
    }

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
