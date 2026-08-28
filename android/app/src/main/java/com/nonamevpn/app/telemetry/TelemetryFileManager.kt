package com.nonamevpn.app.telemetry

import android.content.Context
import com.nonamevpn.app.BuildConfig
import java.io.FileWriter
import java.io.File
import java.util.regex.Pattern
import org.json.JSONObject

class TelemetryFileManager(private val context: Context) {
    val logsDir: File
        get() = File(context.filesDir, "logs").also { it.mkdirs() }

    fun tempFile(sessionId: String): File =
        File(logsDir, ".recording_${sessionId}.jsonl")

    fun buildFinalName(
        clientId: String,
        serverIp: String,
        startSec: Long,
        endSec: Long,
        part: Int = 0,
    ): String {
        val version = BuildConfig.VERSION_NAME.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val build = BuildConfig.VERSION_CODE
        val ip = serverIp.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val partSuffix = if (part > 0) "_part$part" else ""
        return "${clientId}_${version}_${build}_${ip}_${startSec}_${endSec}$partSuffix.json"
    }

    fun finalizeRecording(
        temp: File,
        clientId: String,
        serverIp: String,
        startSec: Long,
        endSec: Long,
        part: Int = 0,
    ): File {
        val finalName = buildFinalName(clientId, serverIp, startSec, endSec, part)
        val dest = File(logsDir, finalName)
        if (dest.exists()) dest.delete()
        check(temp.renameTo(dest)) { "Не удалось сохранить лог: ${temp.absolutePath}" }
        return dest
    }

    fun listLogs(): List<TelemetryLogEntry> {
        val pattern = Pattern.compile(
            """^(.+)_([^_]+)_(\d+)_([^_]+)_(\d+)_(\d+)(?:_part(\d+))?\.json$""",
        )
        return logsDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                val m = pattern.matcher(file.name)
                if (!m.matches()) {
                    TelemetryLogEntry(
                        file = file,
                        displayName = file.name,
                        createdAtMs = file.lastModified(),
                        durationMs = 0,
                        sizeBytes = file.length(),
                    )
                } else {
                    val start = m.group(5)?.toLongOrNull() ?: 0L
                    val end = m.group(6)?.toLongOrNull() ?: 0L
                    TelemetryLogEntry(
                        file = file,
                        displayName = file.name,
                        createdAtMs = start * 1000,
                        durationMs = ((end - start).coerceAtLeast(0)) * 1000,
                        sizeBytes = file.length(),
                    )
                }
            }
            ?.sortedByDescending { it.createdAtMs }
            ?: emptyList()
    }

    fun delete(file: File): Boolean = file.delete()

    companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024

        fun embedUserComment(file: File, comment: String) {
            require(file.isFile) { "Лог-файл не найден" }
            val cleaned = TelemetryRedactor.redact(comment.trim())
            require(cleaned.isNotBlank()) { "Введите комментарий" }
            FileWriter(file, true).buffered().use { writer ->
                if (file.length() > 0L && !fileEndsWithNewline(file)) {
                    writer.newLine()
                }
                writer.write(buildUserCommentLine(cleaned))
                writer.newLine()
            }
        }

        internal fun buildUserCommentLine(comment: String, timestamp: Long = System.currentTimeMillis()): String =
            JSONObject()
                .put("timestamp", timestamp)
                .put("event_type", TelemetryEventType.UserComment.wire)
                .put("session_id", "user-comment")
                .put(
                    "data",
                    JSONObject()
                        .put("comment", TelemetryRedactor.redact(comment.trim()))
                        .put("source", "testing_screen"),
                )
                .toString()

        private fun fileEndsWithNewline(file: File): Boolean =
            file.inputStream().use { stream ->
                if (file.length() <= 0L) return@use true
                stream.skip(file.length() - 1)
                stream.read() == '\n'.code
            }
    }
}
