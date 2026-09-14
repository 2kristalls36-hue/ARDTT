package com.ardtt.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class NetcheckTone { Neutral, Ok, Warn, Error }

data class NetcheckItem(
    val id: String,
    val label: String,
    val status: String,
    val detail: String,
)

data class NetcheckReport(
    val ok: Boolean,
    val viaWarp: Boolean,
    val cached: Boolean,
    val items: List<NetcheckItem>,
)

data class NetcheckUiRow(
    val id: String,
    val label: String,
    val pending: Boolean,
    val value: String,
    val tone: NetcheckTone,
)

internal object NetcheckCopy {
    const val VERDICT_ID = "verdict"
    const val VERDICT_LABEL = "Итог"
    const val IP_TYPE_ID = "ip_type"
    const val IP_TYPE_LABEL = "Тип адреса"
    const val IDLE = "—"
    const val OK = "В порядке"
    const val RESTRICTED = "Есть ограничения"
    const val BLOCKED = "Есть блокировки"
    const val PARTIAL = "Проверено частично"
    const val FAILED = "Не удалось проверить"
}

object NetcheckClient {
    /**
     * Compact status-card rows: overall verdict + address kind.
     * Service slot names stay on the server; the admin card does not list them.
     *
     * @param probeActive when false (tunnel off / pause), rows show «—» without spinners.
     */
    fun summaryRows(report: NetcheckReport?, probeActive: Boolean): List<NetcheckUiRow> =
        listOf(verdictRow(report, probeActive), ipTypeRow(report, probeActive))

    internal fun verdictOf(report: NetcheckReport): Pair<String, NetcheckTone> {
        val services = report.items.filter { it.id != NetcheckCopy.IP_TYPE_ID }
        if (services.isEmpty()) {
            return if (report.ok) {
                NetcheckCopy.OK to NetcheckTone.Ok
            } else {
                NetcheckCopy.FAILED to NetcheckTone.Error
            }
        }
        val statuses = services.map { it.status.trim().lowercase() }
        val blocked = statuses.count { it == "blocked" }
        val restricted = statuses.count { it == "restricted" }
        val errors = statuses.count { it == "error" }
        val okish = statuses.count { it == "ok" || it == "isp" }
        return when {
            blocked > 0 -> NetcheckCopy.BLOCKED to NetcheckTone.Error
            restricted > 0 -> NetcheckCopy.RESTRICTED to NetcheckTone.Warn
            errors > 0 && okish == 0 -> NetcheckCopy.FAILED to NetcheckTone.Error
            errors > 0 -> NetcheckCopy.PARTIAL to NetcheckTone.Warn
            !report.ok -> NetcheckCopy.FAILED to NetcheckTone.Error
            else -> NetcheckCopy.OK to NetcheckTone.Ok
        }
    }

    private fun verdictRow(report: NetcheckReport?, probeActive: Boolean): NetcheckUiRow {
        if (!probeActive) {
            return NetcheckUiRow(
                id = NetcheckCopy.VERDICT_ID,
                label = NetcheckCopy.VERDICT_LABEL,
                pending = false,
                value = NetcheckCopy.IDLE,
                tone = NetcheckTone.Neutral,
            )
        }
        if (report == null) {
            return NetcheckUiRow(
                id = NetcheckCopy.VERDICT_ID,
                label = NetcheckCopy.VERDICT_LABEL,
                pending = true,
                value = "",
                tone = NetcheckTone.Neutral,
            )
        }
        val (value, tone) = verdictOf(report)
        return NetcheckUiRow(
            id = NetcheckCopy.VERDICT_ID,
            label = NetcheckCopy.VERDICT_LABEL,
            pending = false,
            value = value,
            tone = tone,
        )
    }

    private fun ipTypeRow(report: NetcheckReport?, probeActive: Boolean): NetcheckUiRow {
        if (!probeActive) {
            return NetcheckUiRow(
                id = NetcheckCopy.IP_TYPE_ID,
                label = NetcheckCopy.IP_TYPE_LABEL,
                pending = false,
                value = NetcheckCopy.IDLE,
                tone = NetcheckTone.Neutral,
            )
        }
        val item = report?.items?.firstOrNull { it.id == NetcheckCopy.IP_TYPE_ID }
        if (item == null) {
            return NetcheckUiRow(
                id = NetcheckCopy.IP_TYPE_ID,
                label = NetcheckCopy.IP_TYPE_LABEL,
                pending = true,
                value = "",
                tone = NetcheckTone.Neutral,
            )
        }
        return NetcheckUiRow(
            id = NetcheckCopy.IP_TYPE_ID,
            label = NetcheckCopy.IP_TYPE_LABEL,
            pending = false,
            value = item.detail.ifBlank { statusFallback(item.status) },
            tone = toneOf(item.status),
        )
    }

    fun parse(raw: String): NetcheckReport {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("items")
        val items = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    add(
                        NetcheckItem(
                            id = it.optString("id"),
                            label = it.optString("label"),
                            status = it.optString("status"),
                            detail = it.optString("detail"),
                        ),
                    )
                }
            }
        }
        return NetcheckReport(
            ok = o.optBoolean("ok", true),
            viaWarp = o.optBoolean("viaWarp", false),
            cached = o.optBoolean("cached", false),
            items = items,
        )
    }

    fun toneOf(status: String): NetcheckTone = when (status) {
        "ok", "isp" -> NetcheckTone.Ok
        "restricted", "hosting", "proxy" -> NetcheckTone.Warn
        "blocked", "error" -> NetcheckTone.Error
        else -> NetcheckTone.Neutral
    }

    private fun statusFallback(status: String): String = when (status) {
        "ok" -> "доступен"
        "restricted" -> "ограничен"
        "blocked" -> "недоступен"
        "hosting" -> "хостинг"
        "proxy" -> "прокси"
        "isp" -> "провайдер"
        else -> "не удалось проверить"
    }

    suspend fun fetch(
        context: Context,
        provisionBaseUrl: String?,
        deviceId: String?,
        hideIp: Boolean,
        refresh: Boolean,
    ): NetcheckReport? = withContext(Dispatchers.IO) {
        val base = provisionBaseUrl?.trimEnd('/') ?: return@withContext null
        val q = buildString {
            append("$base/v1/netcheck?")
            val params = mutableListOf<String>()
            if (!deviceId.isNullOrBlank()) {
                params += "deviceId=" + java.net.URLEncoder.encode(deviceId.trim(), Charsets.UTF_8.name())
            }
            params += if (hideIp) "viaWarp=1" else "viaWarp=0"
            if (refresh) params += "refresh=1"
            append(params.joinToString("&"))
        }
        val underlay = pickUnderlay(context)
        runCatching { getReport(q, underlay) }.getOrElse {
            if (underlay != null) {
                runCatching { getReport(q, null) }.getOrNull()
            } else {
                null
            }
        }
    }

    private fun getReport(url: String, bind: Network?): NetcheckReport {
        val raw = if (bind != null) bind.openConnection(URL(url)) else URL(url).openConnection()
        val conn = raw as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 6_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) error("HTTP $code")
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun pickUnderlay(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
