package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.serverOsBadgeLabel

internal sealed class HealthUi {
    data object Checking : HealthUi()
    data class Online(
        val deployVersion: String = "",
        val pingMs: Long = -1L,
    ) : HealthUi()
    data object Offline : HealthUi()
}

internal fun healthUiOf(info: ProvisionAdminApi.HealthInfo?): HealthUi {
    if (info == null || !info.ok) return HealthUi.Offline
    return HealthUi.Online(info.deployVersion, info.pingMs)
}

internal fun formatHealthPingMs(pingMs: Long): String {
    if (pingMs <= 0L) return ""
    return "$pingMs мс"
}

/**
 * Public VPN host to show next to SSH identity — only when it actually differs.
 */
internal fun distinctPublicHost(sshHost: String, publicHost: String): String? {
    val ssh = sshHost.trim()
    val pub = publicHost.trim()
    if (pub.isEmpty()) return null
    return pub.takeUnless { it.equals(ssh, ignoreCase = true) }
}

internal fun serverCardTitle(name: String, host: String): String =
    name.trim().ifBlank { host.trim() }

/** SSH / pub facts under the title — never repeats the title IP. */
internal fun serverCardMetaLine(
    name: String,
    host: String,
    sshPort: Int,
    publicHost: String,
): String {
    val title = serverCardTitle(name, host)
    val ssh = host.trim()
    val parts = mutableListOf<String>()
    if (ssh.isNotEmpty() && !ssh.equals(title, ignoreCase = true)) {
        parts.add(ssh)
    }
    parts.add("SSH $sshPort")
    distinctPublicHost(ssh, publicHost)?.let { parts.add("pub $it") }
    return parts.joinToString(" · ")
}

/**
 * Version fragment for the OS badge, without repeating [serverOsBadgeLabel].
 * "Ubuntu 24.04.1 LTS" → "24.04.1 LTS"; omitted when empty or equal to the name.
 */
internal fun serverOsBadgeVersionText(osId: String, osVersion: String): String? {
    val ver = osVersion.trim()
    if (ver.isEmpty()) return null
    val name = serverOsBadgeLabel(osId)
    if (ver.equals(name, ignoreCase = true)) return null
    if (name.isNotEmpty() && ver.startsWith(name, ignoreCase = true)) {
        val rest = ver.substring(name.length).trim().trimStart('-', '·', ':').trim()
        return rest.ifEmpty { null }
    }
    return ver
}

/**
 * True unless health is online and [DeployBundle.isCurrent].
 * Checking / offline / unknown count as outdated (needs update or reinstall).
 */
internal fun isDeployOutdated(health: HealthUi?, expectedVersion: String): Boolean {
    val online = health as? HealthUi.Online ?: return true
    return !DeployBundle.isCurrent(online.deployVersion, expectedVersion)
}

/** Sticky «Обновить деплой» — hidden only when the stack is known-current. */
internal fun shouldShowUpdateDeployButton(health: HealthUi?, expectedVersion: String): Boolean =
    isDeployOutdated(health, expectedVersion)

/** Saved server params: reinstall only. New card: first install. */
internal fun serverDeployActionLabel(saved: Boolean, cascadeEnabled: Boolean): String = when {
    saved -> "Переустановить сервер"
    cascadeEnabled -> "Установить каскад"
    else -> "Установить на VPS"
}

internal fun serverDeployScreenTitle(saved: Boolean): String =
    if (saved) "Параметры сервера" else "Деплой"

internal fun serverDeployFormHelp(
    saved: Boolean,
    cascadeEnabled: Boolean,
    expectedVersion: String,
): String {
    if (saved) {
        return "Кнопка «Переустановить сервер» заново зальёт стек версии $expectedVersion на VPS. " +
            "Ход установки откроется снизу, как при обновлении деплоя."
    }
    val action = serverDeployActionLabel(saved = false, cascadeEnabled = cascadeEnabled)
    return "«Сохранить» только добавляет VPS в список. Установка стека — кнопка «$action»."
}

internal fun serverReinstallConfirmBody(host: String, expectedVersion: String): String {
    val target = host.trim().ifBlank { "VPS" }
    return "Стек версии $expectedVersion будет заново залит на $target по указанным SSH-данным."
}

internal fun deployProgressSheetTitle(busy: Boolean, isUpdate: Boolean, status: String?): String = when {
    busy && isUpdate -> "Обновление деплоя…"
    busy -> "Установка деплоя…"
    status?.startsWith("Ошибка") == true -> "Ошибка"
    else -> "Готово"
}

/**
 * Orange freshness bar: only when online but not current.
 * Current deploys use the status line; no second “актуален” chip.
 */
internal fun deployFreshnessChipText(health: HealthUi?, expectedVersion: String): String? {
    val online = health as? HealthUi.Online ?: return null
    if (DeployBundle.isCurrent(online.deployVersion, expectedVersion)) return null
    val installed = online.deployVersion.trim().ifBlank { "—" }
    val expected = expectedVersion.trim().ifBlank { "—" }
    return "Требуется обновление · $installed → $expected"
}

/** Status line without repeating “актуален” / “нужно обновить” (that lives on the chip). */
internal fun healthStatusLabel(
    health: HealthUi?,
    lastDeployedAtMs: Long,
): String {
    return when (health) {
        null, HealthUi.Checking -> "● Проверка…"
        is HealthUi.Online -> {
            val ver = health.deployVersion.ifBlank { "—" }
            val base = "● Онлайн · деплой $ver"
            val ping = formatHealthPingMs(health.pingMs)
            if (ping.isNotEmpty()) "$base · $ping" else base
        }
        HealthUi.Offline -> {
            if (lastDeployedAtMs == 0L) {
                "● Не установлен / нет связи"
            } else {
                "● Нет связи"
            }
        }
    }
}
