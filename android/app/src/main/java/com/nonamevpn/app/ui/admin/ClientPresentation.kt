package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.deploy.ProvisionAdminApi
import com.nonamevpn.app.deploy.deviceDisplayLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun clientSubscriptionActive(
    user: ProvisionAdminApi.UserSummary,
    nowMs: Long = System.currentTimeMillis(),
): Boolean = !user.deactivated && (user.expiresAt <= 0L || user.expiresAt * 1000L > nowMs)

internal fun clientModeLabel(
    user: ProvisionAdminApi.UserSummary,
    nowMs: Long = System.currentTimeMillis(),
): String = when {
    user.deactivated -> "Отключён"
    !clientSubscriptionActive(user, nowMs) -> "Истекла"
    user.online -> "Онлайн"
    else -> "Оффлайн"
}

internal fun clientDeviceSummary(user: ProvisionAdminApi.UserSummary): String =
    deviceDisplayLabels(user.deviceIds, user.deviceModels).firstOrNull().orEmpty().ifBlank { "—" }

internal fun clientTrafficLabel(user: ProvisionAdminApi.UserSummary): String {
    val used = formatClientBytes(user.usedBytes)
    return if (user.trafficLimitBytes > 0L) {
        "$used / ${formatClientBytes(user.trafficLimitBytes)}"
    } else {
        used
    }
}

internal fun formatClientBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val locale = Locale("ru")
    return when {
        b < 1024L -> "$b Б"
        b < 1024L * 1024L -> String.format(locale, "%.1f КБ", b / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(locale, "%.2f МБ", b / (1024.0 * 1024.0))
        else -> String.format(locale, "%.2f ГБ", b / (1024.0 * 1024.0 * 1024.0))
    }
}

internal fun formatOfflineDuration(sec: Long): String {
    if (sec <= 0L) return "—"
    val minutes = sec / 60L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0L -> "$days дн"
        hours > 0L -> "$hours ч"
        minutes > 0L -> "$minutes мин"
        else -> "меньше минуты"
    }
}

internal fun formatClientExpires(expiresAt: Long): String {
    if (expiresAt <= 0L) return "без срока"
    return SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(expiresAt * 1000L))
}

internal fun formatClientRelative(ms: Long): String {
    if (ms <= 0L) return ""
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        minutes < 1L -> "только что"
        minutes < 60L -> "$minutes мин назад"
        hours < 24L -> "$hours ч назад"
        days < 30L -> "$days дн назад"
        else -> SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(ms))
    }
}
