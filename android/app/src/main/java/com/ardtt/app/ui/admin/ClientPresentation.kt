package com.ardtt.app.ui.admin

import androidx.compose.ui.graphics.Color
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.deviceDisplayLabels
import com.ardtt.app.profile.VpnProfile
import com.ardtt.app.ui.theme.ArdttAlpha
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

internal fun clientDeviceCountLabel(used: Int, max: Int): String =
    "Устройства: $used/${max.coerceAtLeast(0)}"

/** One action: offer to turn off an active client, or turn on a deactivated one. */
internal data class ClientEnableAction(
    val label: String,
    val nextDeactivated: Boolean,
)

internal fun clientEnableAction(deactivated: Boolean) = ClientEnableAction(
    label = if (deactivated) "Включить" else "Выключить",
    nextDeactivated = !deactivated,
)

internal fun clientEnableActionLabel(deactivated: Boolean): String =
    clientEnableAction(deactivated).label

/** Stroke for Limit / enable so the default action never drops the outline. */
internal fun clientActionButtonStroke(
    accent: Color?,
    outline: Color,
    busy: Boolean,
): Color {
    val color = accent ?: outline
    return if (busy) color.copy(alpha = ArdttAlpha.Disabled) else color
}

/** Fresh create: profile JSON may include a template deviceId, but nothing is bound yet. */
internal fun userStubFromProfile(profile: VpnProfile) = ProvisionAdminApi.UserSummary(
    name = profile.name,
    hostId = profile.hostId,
    deviceId = "",
    deviceIds = emptyList(),
    maxDevices = profile.maxDevices,
    hideIp = profile.hideIp,
    createdAt = "",
    expiresAt = profile.expiresAt,
    deactivated = profile.deactivated,
)

internal fun profileWithBindDeviceId(
    profile: VpnProfile,
    generated: String = newLocalDeviceId(),
): VpnProfile =
    if (profile.deviceId.isBlank()) profile.copy(deviceId = generated) else profile

internal fun newLocalDeviceId(): String =
    "dev-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

internal fun userHasDevice(user: ProvisionAdminApi.UserSummary, deviceId: String): Boolean {
    val id = deviceId.trim()
    return id.isNotEmpty() && user.deviceIds.any { it == id }
}

internal fun addToPhoneBindMessage(bound: Boolean): String =
    if (bound) "Добавлен в профили" else "Профиль добавлен, устройство не привязалось"

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

internal enum class ClientExpiresTone {
    Unlimited,
    Active,
    ExpiringSoon,
    Expired,
}

internal const val EXPIRES_SOON_MS = 7L * 24L * 60L * 60L * 1000L

internal fun clientExpiresTone(
    expiresAt: Long,
    nowMs: Long = System.currentTimeMillis(),
): ClientExpiresTone {
    if (expiresAt <= 0L) return ClientExpiresTone.Unlimited
    val endMs = expiresAt * 1000L
    return when {
        endMs <= nowMs -> ClientExpiresTone.Expired
        endMs - nowMs <= EXPIRES_SOON_MS -> ClientExpiresTone.ExpiringSoon
        else -> ClientExpiresTone.Active
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

internal enum class ClientAppVersionTone {
    Current,
    Outdated,
    Unknown,
}

internal data class ClientAppVersionView(
    val label: String,
    val tone: ClientAppVersionTone,
)

internal fun clientDeviceAppVersion(
    user: ProvisionAdminApi.UserSummary,
    deviceId: String,
): String? {
    val id = deviceId.trim()
    if (id.isEmpty()) return null
    val name = user.deviceAppVersions[id]?.trim().orEmpty()
    val code = user.deviceAppVersionCodes[id] ?: 0
    return when {
        name.isNotEmpty() -> name
        code > 0 -> "сборка $code"
        else -> null
    }
}

internal fun clientAppVersionView(
    user: ProvisionAdminApi.UserSummary,
    latestCode: Int,
): ClientAppVersionView {
    val entries = linkedMapOf<String, Pair<String, Int>>()
    val ids = (user.deviceAppVersions.keys + user.deviceAppVersionCodes.keys +
        listOf(user.deviceId).filter { it.isNotBlank() }).toSet()
    for (id in ids) {
        val name = user.deviceAppVersions[id]?.trim().orEmpty()
        val code = user.deviceAppVersionCodes[id] ?: 0
        if (name.isNotEmpty() || code > 0) {
            entries[id] = name to code
        }
    }
    if (entries.isEmpty()) {
        val fallbackName = user.appVersion.trim()
        val fallbackCode = user.appVersionCode
        if (fallbackName.isNotEmpty() || fallbackCode > 0) {
            entries["_"] = fallbackName to fallbackCode
        }
    }
    if (entries.isEmpty()) {
        return ClientAppVersionView("нет версии", ClientAppVersionTone.Unknown)
    }
    val worst = entries.values.minBy { pair ->
        if (pair.second > 0) pair.second else Int.MAX_VALUE
    }
    val label = worst.first.ifBlank {
        if (worst.second > 0) "сборка ${worst.second}" else "нет версии"
    }
    val tone = when {
        worst.second <= 0 -> ClientAppVersionTone.Unknown
        latestCode > 0 && worst.second < latestCode -> ClientAppVersionTone.Outdated
        else -> ClientAppVersionTone.Current
    }
    return ClientAppVersionView(label, tone)
}
