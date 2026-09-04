package com.ardtt.app.core

import com.ardtt.app.telemetry.TelemetryBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-app event log: ring buffer for the Логи tab.
 *
 * Minimal (user) mode keeps only non-verbose lines + warnings/errors.
 * Detailed (admin) mode also keeps verbose diagnostic lines.
 */
object AppLog {
    enum class Level { I, W, E }

    data class Entry(
        val id: Int,
        val timeMs: Long,
        val tag: String,
        val message: String,
        val level: Level,
        val verbose: Boolean = false,
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
    private val detailedEnabled = AtomicBoolean(false)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun setDetailedEnabled(enabled: Boolean) {
        detailedEnabled.set(enabled)
        if (!enabled) {
            _entries.update { cur -> cur.filterNot { it.verbose && it.level == Level.I } }
        }
    }

    fun isDetailedEnabled(): Boolean = detailedEnabled.get()

    fun i(tag: String, message: String, verbose: Boolean = false) =
        append(Level.I, tag, message, verbose = verbose)

    fun w(tag: String, message: String) = append(Level.W, tag, message, verbose = false)
    fun e(tag: String, message: String) = append(Level.E, tag, message, verbose = false)

    /** Verbose diagnostic — only shown when detailed logs are on (admin). */
    fun v(tag: String, message: String) = append(Level.I, tag, message, verbose = true)

    fun clear() {
        _entries.value = emptyList()
    }

    fun dumpText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { it.displayLine(fmt) }
    }

    @Synchronized
    private fun append(level: Level, tag: String, message: String, verbose: Boolean) {
        if (verbose && level == Level.I && !detailedEnabled.get()) {
            android.util.Log.d(tag, message.trim().ifBlank { "(empty)" })
            return
        }
        val msg = message.trim().ifBlank { "(empty)" }
        _entries.update { cur ->
            val last = cur.lastOrNull()
            if (
                last != null &&
                last.tag == tag &&
                last.message == msg &&
                last.level == level &&
                last.verbose == verbose
            ) {
                cur.dropLast(1) + last.copy(count = last.count + 1, timeMs = System.currentTimeMillis())
            } else {
                val next = cur + Entry(
                    id = seq.incrementAndGet(),
                    timeMs = System.currentTimeMillis(),
                    tag = tag,
                    message = msg,
                    level = level,
                    verbose = verbose,
                )
                if (next.size > MAX) next.takeLast(MAX) else next
            }
        }
        when (level) {
            Level.E -> android.util.Log.e(tag, msg)
            Level.W -> android.util.Log.w(tag, msg)
            Level.I -> if (verbose) android.util.Log.d(tag, msg) else android.util.Log.i(tag, msg)
        }
        TelemetryBridge.appLog(
            level = level.name,
            tag = tag,
            message = msg,
            verbose = verbose,
        )
    }
}
