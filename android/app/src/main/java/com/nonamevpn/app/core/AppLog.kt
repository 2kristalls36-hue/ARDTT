package com.nonamevpn.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-app event log (qWDTT-style): ring buffer for the Логи tab.
 */
object AppLog {
    enum class Level { I, W, E }

    data class Entry(
        val id: Int,
        val timeMs: Long,
        val tag: String,
        val message: String,
        val level: Level,
        val count: Int = 1,
    ) {
        fun displayLine(fmt: SimpleDateFormat): String {
            val t = fmt.format(Date(timeMs))
            val prefix = when (level) {
                Level.I -> ""
                Level.W -> "⚠ "
                Level.E -> "✖ "
            }
            val times = if (count > 1) " (×$count)" else ""
            return "$t [$tag] $prefix$message$times"
        }
    }

    private const val MAX = 800
    private val seq = AtomicInteger(0)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) = append(Level.I, tag, message)
    fun w(tag: String, message: String) = append(Level.W, tag, message)
    fun e(tag: String, message: String) = append(Level.E, tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    fun dumpText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { it.displayLine(fmt) }
    }

    @Synchronized
    private fun append(level: Level, tag: String, message: String) {
        val msg = message.trim().ifBlank { "(empty)" }
        _entries.update { cur ->
            val last = cur.lastOrNull()
            if (last != null && last.tag == tag && last.message == msg && last.level == level) {
                cur.dropLast(1) + last.copy(count = last.count + 1, timeMs = System.currentTimeMillis())
            } else {
                val next = cur + Entry(
                    id = seq.incrementAndGet(),
                    timeMs = System.currentTimeMillis(),
                    tag = tag,
                    message = msg,
                    level = level,
                )
                if (next.size > MAX) next.takeLast(MAX) else next
            }
        }
        when (level) {
            Level.E -> android.util.Log.e(tag, msg)
            Level.W -> android.util.Log.w(tag, msg)
            Level.I -> android.util.Log.i(tag, msg)
        }
    }
}
