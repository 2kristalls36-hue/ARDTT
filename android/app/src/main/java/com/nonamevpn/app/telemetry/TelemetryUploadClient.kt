package com.nonamevpn.app.telemetry

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source

class TelemetryUploadClient {
    suspend fun upload(
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
                uploadOnce(file, clientId, uploadUrl, onProgress)
            }
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull()
            if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt)
        }
        Result.failure(lastError ?: IllegalStateException("Не удалось отправить лог"))
    }

    private fun uploadOnce(
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
        val client = AppHttpClient.builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            onProgress(1f)
            if (!response.isSuccessful) {
                throw IllegalStateException("Сервер вернул ${response.code}: ${response.body?.string()}")
            }
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
