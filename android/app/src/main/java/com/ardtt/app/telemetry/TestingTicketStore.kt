package com.ardtt.app.telemetry

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TestingTicket(
    val number: Int,
    val comment: String,
    val createdAtMs: Long,
    val logName: String = "",
    val read: Boolean = false,
    val processedAt: String = "",
    val processedBy: String = "",
    val reviewNote: String = "",
)

data class TestingTicketState(
    val draftComment: String = "",
    val drafts: Map<String, String> = emptyMap(),
    val nextNumber: Int = 1,
    val tickets: List<TestingTicket> = emptyList(),
)

/** Server (or recovered) status of one uploaded log. */
data class TestingTicketStatus(
    val logName: String,
    val number: Int = 0,
    val read: Boolean = false,
    val comment: String = "",
    val processedAt: String = "",
    val processedBy: String = "",
    val reviewNote: String = "",
    val uploadedAtMs: Long = 0L,
)

/** Ask for a comment only when the log-row draft is still empty. */
internal fun testingUploadNeedsCommentPrompt(draft: String): Boolean =
    draft.trim().isBlank()

internal const val TESTING_COMMENT_PREVIEW_CHARS = 48

/** One-line snippet shown in the log row before the send/delete icons. */
internal fun testingCommentPreview(
    comment: String,
    maxChars: Int = TESTING_COMMENT_PREVIEW_CHARS,
): String {
    val cleaned = comment.trim().replace(WHITESPACE, " ")
    if (cleaned.isEmpty()) return ""
    if (cleaned.length <= maxChars) return cleaned
    return cleaned.take(maxChars).trimEnd() + "…"
}

private val WHITESPACE = Regex("\\s+")

internal fun testingTicketTitle(number: Int): String =
    if (number > 0) "Обращение №$number" else "Обращение"

internal fun testingTicketReadLabel(read: Boolean): String =
    if (read) "Прочитано" else "Ожидает разбора"

internal const val TESTING_AUTHOR_REPLY_TITLE = "Ответ автора"
internal const val TESTING_USER_COMMENT_TITLE = "Ваш комментарий"

internal fun testingTicketNextNumber(nextNumber: Int, tickets: List<TestingTicket>): Int {
    val maxExisting = tickets.maxOfOrNull { it.number } ?: 0
    return maxOf(nextNumber, maxExisting + 1, 1)
}

internal fun formatTestingTicketTime(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ms))
}

internal fun parseTelemetryTimeMs(raw: String): Long {
    val value = raw.trim()
    if (value.isEmpty()) return 0L
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}

class TestingTicketStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    @Synchronized
    fun load(): TestingTicketState {
        if (!file.isFile) return TestingTicketState()
        val raw = runCatching { file.readText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return TestingTicketState()
        return runCatching { parse(raw) }.getOrDefault(TestingTicketState())
    }

    @Synchronized
    fun saveDraft(comment: String): TestingTicketState {
        val state = load().copy(draftComment = comment.take(MAX_COMMENT))
        persist(state)
        return state
    }

    @Synchronized
    fun saveDrafts(drafts: Map<String, String>): TestingTicketState {
        val cleaned = linkedMapOf<String, String>()
        drafts.forEach { (name, comment) ->
            val key = name.trim()
            val text = comment.take(MAX_COMMENT)
            if (key.isNotEmpty() && text.isNotBlank()) cleaned[key] = text
        }
        val state = load().copy(drafts = cleaned)
        persist(state)
        return state
    }

    @Synchronized
    fun register(comment: String, logName: String = ""): TestingTicket =
        rememberUpload(comment = comment, logName = logName, serverNumber = 0)

    /** Keep the same number when the same log file is sent again after a failed upload. */
    @Synchronized
    fun registerOrReuse(comment: String, logName: String): TestingTicket =
        rememberUpload(comment = comment, logName = logName, serverNumber = 0)

    /**
     * Record a successful upload. [serverNumber] from the telemetry host wins;
     * `0` falls back to a local sequential id so older stacks still show history.
     */
    @Synchronized
    fun rememberUpload(
        comment: String,
        logName: String,
        serverNumber: Int,
        read: Boolean = false,
        processedAt: String = "",
        processedBy: String = "",
        reviewNote: String = "",
        createdAtMs: Long = 0L,
    ): TestingTicket {
        val cleaned = comment.trim()
        require(cleaned.isNotBlank()) { "Введите комментарий" }
        val name = logName.trim()
        val state = load()
        val existing = state.tickets.firstOrNull { ticket ->
            (name.isNotEmpty() && ticket.logName == name) ||
                (serverNumber > 0 && ticket.number == serverNumber)
        }
        val number = when {
            serverNumber > 0 -> serverNumber
            existing != null && existing.number > 0 -> existing.number
            else -> testingTicketNextNumber(state.nextNumber, state.tickets)
        }
        val ticket = TestingTicket(
            number = number,
            comment = cleaned.take(MAX_COMMENT),
            createdAtMs = when {
                createdAtMs > 0L -> createdAtMs
                existing != null && existing.createdAtMs > 0L -> existing.createdAtMs
                else -> System.currentTimeMillis()
            },
            logName = name.ifEmpty { existing?.logName.orEmpty() },
            read = read,
            processedAt = processedAt,
            processedBy = processedBy,
            reviewNote = reviewNote,
        )
        val tickets = listOf(ticket) + state.tickets.filterNot { other ->
            other.number == ticket.number ||
                (ticket.logName.isNotEmpty() && other.logName == ticket.logName)
        }
        persist(
            state.copy(
                nextNumber = testingTicketNextNumber(ticket.number + 1, tickets),
                tickets = tickets.sortedByDescending { it.number },
            ),
        )
        return ticket
    }

    @Synchronized
    fun applyServerStatuses(items: List<TestingTicketStatus>): TestingTicketState {
        var state = load()
        items.forEach { item ->
            val name = item.logName.trim()
            if (name.isEmpty() && item.number <= 0) return@forEach
            val existing = state.tickets.firstOrNull { ticket ->
                (name.isNotEmpty() && ticket.logName == name) ||
                    (item.number > 0 && ticket.number == item.number)
            }
            val comment = item.comment.trim().ifBlank {
                existing?.comment.orEmpty().ifBlank { name.ifBlank { "Лог" } }
            }
            val ticket = TestingTicket(
                number = when {
                    item.number > 0 -> item.number
                    existing != null && existing.number > 0 -> existing.number
                    else -> testingTicketNextNumber(state.nextNumber, state.tickets)
                },
                comment = comment.take(MAX_COMMENT),
                createdAtMs = when {
                    existing != null && existing.createdAtMs > 0L -> existing.createdAtMs
                    item.uploadedAtMs > 0L -> item.uploadedAtMs
                    else -> System.currentTimeMillis()
                },
                logName = name.ifEmpty { existing?.logName.orEmpty() },
                read = item.read,
                processedAt = if (item.read) {
                    item.processedAt.ifBlank { existing?.processedAt.orEmpty() }
                } else {
                    ""
                },
                processedBy = if (item.read) {
                    item.processedBy.ifBlank { existing?.processedBy.orEmpty() }
                } else {
                    ""
                },
                reviewNote = if (item.read) {
                    item.reviewNote.ifBlank { existing?.reviewNote.orEmpty() }
                } else {
                    ""
                },
            )
            val tickets = listOf(ticket) + state.tickets.filterNot { other ->
                other.number == ticket.number ||
                    (ticket.logName.isNotEmpty() && other.logName == ticket.logName)
            }
            state = state.copy(
                nextNumber = testingTicketNextNumber(ticket.number + 1, tickets),
                tickets = tickets.sortedByDescending { it.number },
            )
        }
        persist(state)
        return state
    }

    private fun persist(state: TestingTicketState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encode(state))
        if (!tmp.renameTo(file)) {
            file.writeText(encode(state))
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "testing_tickets.json"
        const val MAX_COMMENT = 2_048

        internal fun parse(raw: String): TestingTicketState {
            val o = JSONObject(raw)
            val tickets = mutableListOf<TestingTicket>()
            val seen = mutableSetOf<Int>()
            o.optJSONArray("tickets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val number = t.optInt("number")
                    val comment = t.optString("comment").trim()
                    if (number <= 0 || comment.isEmpty() || !seen.add(number)) continue
                    tickets += TestingTicket(
                        number = number,
                        comment = comment,
                        createdAtMs = t.optLong("createdAtMs"),
                        logName = t.optString("logName"),
                        read = t.optBoolean("read"),
                        processedAt = t.optString("processedAt"),
                        processedBy = t.optString("processedBy"),
                        reviewNote = t.optString("reviewNote"),
                    )
                }
            }
            val ordered = tickets.sortedByDescending { it.number }
            val drafts = linkedMapOf<String, String>()
            o.optJSONObject("drafts")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim()
                    val text = obj.optString(key).take(MAX_COMMENT)
                    if (key.isNotEmpty() && text.isNotBlank()) drafts[key] = text
                }
            }
            return TestingTicketState(
                draftComment = o.optString("draftComment"),
                drafts = drafts,
                nextNumber = testingTicketNextNumber(o.optInt("nextNumber", 1), ordered),
                tickets = ordered,
            )
        }

        internal fun encode(state: TestingTicketState): String {
            val tickets = JSONArray()
            state.tickets.sortedByDescending { it.number }.forEach { ticket ->
                tickets.put(
                    JSONObject()
                        .put("number", ticket.number)
                        .put("comment", ticket.comment)
                        .put("createdAtMs", ticket.createdAtMs)
                        .put("logName", ticket.logName)
                        .put("read", ticket.read)
                        .put("processedAt", ticket.processedAt)
                        .put("processedBy", ticket.processedBy)
                        .put("reviewNote", ticket.reviewNote),
                )
            }
            val drafts = JSONObject()
            state.drafts.forEach { (name, comment) ->
                val key = name.trim()
                val text = comment.take(MAX_COMMENT)
                if (key.isNotEmpty() && text.isNotBlank()) drafts.put(key, text)
            }
            return JSONObject()
                .put("draftComment", state.draftComment)
                .put("drafts", drafts)
                .put("nextNumber", state.nextNumber.coerceAtLeast(1))
                .put("tickets", tickets)
                .toString()
        }
    }
}
