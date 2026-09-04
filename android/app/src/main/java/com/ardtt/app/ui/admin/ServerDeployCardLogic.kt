package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployTarget
import com.ardtt.app.deploy.ProvisionAdminApi
import com.ardtt.app.deploy.ServerOsProbe
import com.ardtt.app.deploy.ServersRepository
import com.ardtt.app.deploy.serverOsBadgeLabel

internal sealed class HealthUi {
    data object Checking : HealthUi()
    data class Online(
        val deployVersion: String = "",
        val pingMs: Long = -1L,
    ) : HealthUi()
    /** SSH/auth failed — host or credentials unreachable. */
    data object Unreachable : HealthUi()
    /** SSH/auth succeeded, but provision /health is down — stack not on the VPS. */
    data object NotInstalled : HealthUi()
}

internal fun healthUiFromProbes(
    info: ProvisionAdminApi.HealthInfo?,
    sshAuthOk: Boolean,
): HealthUi {
    if (info != null && info.ok) {
        return HealthUi.Online(info.deployVersion, info.pingMs)
    }
    return if (sshAuthOk) HealthUi.NotInstalled else HealthUi.Unreachable
}

internal fun healthUiOf(info: ProvisionAdminApi.HealthInfo?): HealthUi =
    healthUiFromProbes(info, sshAuthOk = false)

internal suspend fun probeServerHealthUi(
    target: DeployTarget,
    repo: ServersRepository,
): HealthUi {
    val info = ProvisionAdminApi.health(ProvisionAdminApi.provisionBase(target)).getOrNull()
    if (info != null && info.ok) {
        ServerOsProbe.refreshStored(repo, target)
        return healthUiFromProbes(info, sshAuthOk = false)
    }
    val sshOk = ServerOsProbe.authOk(target)
    if (sshOk) {
        ServerOsProbe.refreshStored(repo, target)
    }
    return healthUiFromProbes(info, sshOk)
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

internal fun serverCardTitle(
    name: String,
    host: String,
    cascadeIpSpan: String? = null,
): String {
    val trimmed = name.trim()
    val ssh = host.trim()
    if (trimmed.isNotEmpty() && !trimmed.equals(ssh, ignoreCase = true)) return trimmed
    return cascadeIpSpan?.takeIf { it.isNotBlank() } ?: ssh
}

/** Entry–exit `ip-ip` when the card is a cascade with two distinct hosts. */
internal fun serverCardCascadeIpSpan(
    host: String,
    publicHost: String,
    cascadeEnabled: Boolean,
    cascadeHost: String,
): String? {
    if (!cascadeEnabled) return null
    val entry = (
        DeployHop.host(publicHost)
            ?: publicHost.trim().ifBlank { null }
            ?: DeployHop.host(host)
            ?: host.trim().ifBlank { null }
        ) ?: return null
    val exit = DeployHop.host(cascadeHost)?.takeIf { it.isNotBlank() } ?: return null
    if (entry.equals(exit, ignoreCase = true)) return null
    return "$entry-$exit"
}

/** SSH / pub facts under the title — never repeats the title IP. Cascade shows `ip-ip`. */
internal fun serverCardMetaLine(
    name: String,
    host: String,
    sshPort: Int,
    publicHost: String,
    cascadeEnabled: Boolean = false,
    cascadeHost: String = "",
): String {
    val span = serverCardCascadeIpSpan(host, publicHost, cascadeEnabled, cascadeHost)
    val title = serverCardTitle(name, host, span)
    val ssh = host.trim()
    val parts = mutableListOf<String>()
    if (span != null) {
        if (!span.equals(title, ignoreCase = true)) parts.add(span)
        parts.add("SSH $sshPort")
        return parts.joinToString(" · ")
    }
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
 * Checking / unreachable / not-installed / unknown count as outdated (needs update or reinstall).
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
    saved -> "Переустановить деплой"
    cascadeEnabled -> "Установить каскад"
    else -> "Установить на VPS"
}

/** Overview sticky / overflow: first install vs refresh of an existing stack. */
internal fun serverOverviewDeployActionLabel(health: HealthUi?): String =
    if (health is HealthUi.NotInstalled) "Установить деплой" else "Обновить деплой"

internal fun serverOverviewDeployConfirmTitle(health: HealthUi?): String =
    if (health is HealthUi.NotInstalled) "Установить деплой?" else "Обновить деплой?"

internal fun serverOverviewDeployConfirmAction(health: HealthUi?): String =
    if (health is HealthUi.NotInstalled) "Установить" else "Обновить"

internal fun serverOverviewDeployIsUpdate(health: HealthUi?): Boolean =
    health !is HealthUi.NotInstalled

internal fun serverDeployScreenTitle(saved: Boolean): String =
    if (saved) "Параметры сервера" else "Деплой"

internal fun serverDeployFormHelp(
    saved: Boolean,
    cascadeEnabled: Boolean,
    expectedVersion: String,
): String {
    if (saved) {
        return "Кнопка «Переустановить деплой» заново зальёт стек версии $expectedVersion на VPS. " +
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

internal fun healthUiIsDown(health: HealthUi?): Boolean =
    health is HealthUi.Unreachable || health is HealthUi.NotInstalled

/** Status line without repeating “актуален” / “нужно обновить” (that lives on the chip). */
internal fun healthStatusLabel(health: HealthUi?): String {
    return when (health) {
        null, HealthUi.Checking -> "● Проверка…"
        is HealthUi.Online -> {
            val ver = health.deployVersion.ifBlank { "—" }
            val base = "● Онлайн · деплой $ver"
            val ping = formatHealthPingMs(health.pingMs)
            if (ping.isNotEmpty()) "$base · $ping" else base
        }
        HealthUi.NotInstalled -> "● Не установлено"
        HealthUi.Unreachable -> "● Нет связи"
    }
}
