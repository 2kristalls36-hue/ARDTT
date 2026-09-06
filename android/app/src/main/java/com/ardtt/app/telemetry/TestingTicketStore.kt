package com.ardtt.app.telemetry

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TestingTicket(
    val number: Int,
    val comment: String,
    val createdAtMs: Long,
    val logName: String = "",
)

data class TestingTicketState(
    val draftComment: String = "",
    val nextNumber: Int = 1,
    val tickets: List<TestingTicket> = emptyList(),
)

/** Ask for a comment only when the testing-screen draft is still empty. */
internal fun testingUploadNeedsCommentPrompt(draft: String): Boolean =
    draft.trim().isBlank()

internal fun testingTicketTitle(number: Int): String = "Обращение №$number"

internal fun testingTicketNextNumber(nextNumber: Int, tickets: List<TestingTicket>): Int {
    val maxExisting = tickets.maxOfOrNull { it.number } ?: 0
    return maxOf(nextNumber, maxExisting + 1, 1)
}

internal fun formatTestingTicketTime(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ms))
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
    fun register(comment: String, logName: String = ""): TestingTicket =
        registerInternal(comment, logName, reuseLog = false)

    /** Keep the same number when the same log file is sent again after a failed upload. */
    @Synchronized
    fun registerOrReuse(comment: String, logName: String): TestingTicket =
        registerInternal(comment, logName, reuseLog = true)

    private fun registerInternal(comment: String, logName: String, reuseLog: Boolean): TestingTicket {
        val cleaned = comment.trim()
        require(cleaned.isNotBlank()) { "Введите комментарий" }
        val name = logName.trim()
        val state = load()
        if (reuseLog && name.isNotEmpty()) {
            val existing = state.tickets.firstOrNull { it.logName == name }
            if (existing != null) {
                val updated = existing.copy(comment = cleaned.take(MAX_COMMENT))
                if (updated != existing) {
                    persist(
                        state.copy(
                            tickets = state.tickets.map { ticket ->
                                if (ticket.number == existing.number) updated else ticket
                            },
                        ),
                    )
                }
                return updated
            }
        }
        val ticket = TestingTicket(
            number = testingTicketNextNumber(state.nextNumber, state.tickets),
            comment = cleaned.take(MAX_COMMENT),
            createdAtMs = System.currentTimeMillis(),
            logName = name,
        )
        persist(
            state.copy(
                nextNumber = ticket.number + 1,
                tickets = listOf(ticket) + state.tickets,
            ),
        )
        return ticket
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
                    )
                }
            }
            val ordered = tickets.sortedByDescending { it.number }
            return TestingTicketState(
                draftComment = o.optString("draftComment"),
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
                        .put("logName", ticket.logName),
                )
            }
            return JSONObject()
                .put("draftComment", state.draftComment)
                .put("nextNumber", state.nextNumber.coerceAtLeast(1))
                .put("tickets", tickets)
                .toString()
        }
    }
}
