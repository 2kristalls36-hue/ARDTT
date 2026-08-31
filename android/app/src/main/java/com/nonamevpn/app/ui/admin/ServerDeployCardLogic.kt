package com.nonamevpn.app.ui.admin

import com.nonamevpn.app.deploy.DeployBundle
import com.nonamevpn.app.deploy.ProvisionAdminApi

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
