package com.ardtt.app.core

import android.net.Network
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pulls official whitelist-reachable RSS feeds and exposes a short UI headline
 * when agencies report mobile-internet restrictions.
 *
 * Path selection still uses [NetworkProbe]. This feed is a human-facing
 * corroboration that keeps working on operator БС (TASS / RIA / Interfax).
 */
object OfficialRestrictionBulletin {
    private const val TAG = "OfficialBulletin"
    private const val CONNECT_MS = 2500
    private const val READ_MS = 2500
    private const val MAX_BODY = 512 * 1024
    private const val REFRESH_MS = 15L * 60L * 1000L
    private const val URGENT_REFRESH_MS = 2L * 60L * 1000L

    data class Source(
        val name: String,
        val url: String,
    )

    val sources: List<Source> = listOf(
        Source("ТАСС", "https://tass.ru/rss/v2.xml"),
        Source("РИА Новости", "https://ria.ru/export/rss2/index.xml"),
        Source("Интерфакс", "https://www.interfax.ru/rss"),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight = AtomicBoolean(false)
    private val lastAttemptElapsedMs = AtomicLong(0L)
    private val _headline = MutableStateFlow<String?>(null)
    val headline: StateFlow<String?> = _headline.asStateFlow()

    /**
     * Refresh when Yandex (whitelist control) is reachable. [urgent] shortens
     * the throttle after a probe already suspects operator restriction.
     */
    fun scheduleRefresh(bindNetwork: Network?, urgent: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val minGap = if (urgent) URGENT_REFRESH_MS else REFRESH_MS
        val last = lastAttemptElapsedMs.get()
        if (last > 0L && now - last < minGap) return
        if (!inFlight.compareAndSet(false, true)) return
        lastAttemptElapsedMs.set(now)
        scope.launch {
            try {
                mutex.withLock { refreshLocked(bindNetwork) }
            } finally {
                inFlight.set(false)
            }
        }
    }

    internal suspend fun refreshLocked(bindNetwork: Network?) {
        val nowWall = System.currentTimeMillis()
        val items = mutableListOf<OfficialRestrictionParser.Item>()
        for (source in sources) {
            val xml = fetchXml(source.url, bindNetwork) ?: continue
            items += OfficialRestrictionParser.parseRss(xml, source.name, nowWall)
        }
        val line = OfficialRestrictionParser.headline(items, nowWall)
        _headline.value = line
        AppLog.i(
            TAG,
            "official restriction feed items=${items.size} " +
                "active=${items.count { it.kind == OfficialRestrictionParser.Kind.ActiveRestriction }} " +
                "headline=${line ?: "none"}",
        )
    }

    internal fun fetchXml(url: String, bindNetwork: Network?): String? {
        var conn: HttpURLConnection? = null
        return try {
            val raw = if (bindNetwork != null) {
                bindNetwork.openConnection(URL(url))
            } else {
                URL(url).openConnection()
            }
            conn = (raw as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                setRequestProperty("User-Agent", "ARDTT/1.0 (Android; official restriction bulletin)")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                AppLog.w(TAG, "rss HTTP $code $url")
                return null
            }
            val stream = conn.inputStream.buffered()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BODY) {
                    AppLog.w(TAG, "rss too large $total $url")
                    return null
                }
                buffer.write(chunk, 0, n)
            }
            String(buffer.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.w(TAG, "rss fail $url: ${t.javaClass.simpleName} ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
