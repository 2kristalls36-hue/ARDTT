package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployHopTrack
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

/** Latency bands for ping coloring (servers card + Network tab). */
internal enum class PingLatencyTier {
    Good,
    Fair,
    Poor,
}

internal fun pingLatencyTier(pingMs: Long): PingLatencyTier? {
    if (pingMs <= 0L) return null
    return when {
        pingMs <= 80L -> PingLatencyTier.Good
        pingMs <= 200L -> PingLatencyTier.Fair
        else -> PingLatencyTier.Poor
    }
}

/**
 * Split status line: presence · deploy · ping.
 * UI lays these out left / center / right with their own colors.
 */
internal data class HealthStatusParts(
    val presence: String,
    val deploy: String? = null,
    val pingMs: Long = -1L,
) {
    val pingLabel: String get() = formatHealthPingMs(pingMs)
}

internal fun healthStatusParts(health: HealthUi?): HealthStatusParts = when (health) {
    null, HealthUi.Checking -> HealthStatusParts("● Проверка…")
    is HealthUi.Online -> HealthStatusParts(
        presence = "● Онлайн",
        deploy = "деплой ${health.deployVersion.ifBlank { "—" }}",
        pingMs = health.pingMs,
    )
    HealthUi.NotInstalled -> HealthStatusParts("● Не установлено")
    HealthUi.Unreachable -> HealthStatusParts("● Нет связи")
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

/** Entry → exit hosts when the card is a cascade with two distinct addresses. */
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
    return "$entry → $exit"
}

/** SSH / pub facts under the title — never repeats the title IP. Cascade shows `ip → ip`. */
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
        return "Кнопка «Переустановить деплой» снова скачает стек версии $expectedVersion из репозитория и зальёт его на VPS. " +
            "Ход установки откроется снизу, как при обновлении деплоя."
    }
    val action = serverDeployActionLabel(saved = false, cascadeEnabled = cascadeEnabled)
    return "«Сохранить» только добавляет VPS в список. Установка стека — кнопка «$action»."
}

internal fun serverReinstallConfirmBody(host: String, expectedVersion: String): String {
    val target = host.trim().ifBlank { "VPS" }
    return "Стек версии $expectedVersion будет снова скачан из репозитория и залит на $target по указанным SSH-данным."
}

/** Same default as the first VPS SSH user field. */
internal fun deploySshUserOrRoot(raw: String): String = raw.trim().ifBlank { "root" }

internal fun deploySshSecretMissing(password: String, privateKeyPem: String): Boolean =
    password.isBlank() && privateKeyPem.isBlank()

internal fun deployProgressSheetTitle(
    busy: Boolean,
    isUpdate: Boolean,
    status: String?,
    isUninstall: Boolean = false,
): String = when {
    busy && isUninstall -> "Удаление деплоя…"
    busy && isUpdate -> "Обновление деплоя…"
    busy -> "Установка деплоя…"
    status?.startsWith("Ошибка") == true -> "Ошибка"
    else -> "Готово"
}

internal enum class DeploySlotPhase {
    Pending,
    Active,
    Done,
    Failed,
}

internal data class DeploySlotView(
    val title: String,
    val host: String,
    val phase: DeploySlotPhase,
)

internal fun deploySlotStatusText(
    phase: DeploySlotPhase,
    isUpdate: Boolean,
    isUninstall: Boolean,
): String = when (phase) {
    DeploySlotPhase.Pending -> "Ожидание"
    DeploySlotPhase.Active -> when {
        isUninstall -> "Идёт удаление"
        isUpdate -> "Идёт обновление"
        else -> "Идёт установка"
    }
    DeploySlotPhase.Done -> "Готово"
    DeploySlotPhase.Failed -> "Ошибка"
}

internal fun deployProgressFinishedSuccess(busy: Boolean, status: String?): Boolean {
    if (busy) return false
    val text = status?.trim().orEmpty()
    if (text.isEmpty()) return false
    if (text.startsWith("Ошибка")) return false
    if (text == "Отменено") return false
    return true
}

internal fun deployProgressFailed(busy: Boolean, status: String?): Boolean =
    !busy && status?.startsWith("Ошибка") == true

internal fun cascadeDeploySlots(
    track: DeployHopTrack,
    failed: Boolean,
    finishedSuccess: Boolean,
): List<DeploySlotView> {
    if (!track.cascade) return emptyList()
    val entry = DeployHop.host(track.entryHost) ?: track.entryHost.trim()
    val exit = DeployHop.host(track.exitHost) ?: track.exitHost.trim()
    if (entry.isBlank() || exit.isBlank()) return emptyList()
    return listOf(
        DeploySlotView(
            title = "VPS 1",
            host = entry,
            phase = deploySlotPhase(
                host = entry,
                done = track.entryDone,
                activeHost = track.activeHost,
                failed = failed,
                finishedSuccess = finishedSuccess,
            ),
        ),
        DeploySlotView(
            title = "VPS 2",
            host = exit,
            phase = deploySlotPhase(
                host = exit,
                done = track.exitDone,
                activeHost = track.activeHost,
                failed = failed,
                finishedSuccess = finishedSuccess,
            ),
        ),
    )
}

internal fun deploySlotPhase(
    host: String,
    done: Boolean,
    activeHost: String,
    failed: Boolean,
    finishedSuccess: Boolean,
): DeploySlotPhase {
    if (finishedSuccess || done) return DeploySlotPhase.Done
    val active = DeployHop.same(host, activeHost)
    if (failed && active) return DeploySlotPhase.Failed
    if (active) return DeploySlotPhase.Active
    return DeploySlotPhase.Pending
}

internal fun serverDeleteConfirmTitle(): String = "Удалить сервер?"

internal fun serverDeleteConfirmBody(
    host: String,
    cascadeEnabled: Boolean = false,
    cascadeHost: String = "",
): String {
    val entry = host.trim().ifBlank { "VPS" }
    val where = if (cascadeEnabled) {
        val exit = cascadeHost.trim()
        if (exit.isNotEmpty()) {
            "входного сервера $entry и выходного $exit"
        } else {
            "сервера $entry"
        }
    } else {
        "сервера $entry"
    }
    return "Стек ARDTT будет удалён с $where: контейнеры Docker, каталог /opt/ardtt и профили клиентов. " +
        "После успешного снятия стека карточка исчезнет из вкладки «Сервера». Действие необратимо."
}

/** Leave the overview only after a finished uninstall that actually removed the card. */
internal fun serverDeleteFinishedShouldLeave(busy: Boolean, status: String?): Boolean {
    if (busy) return false
    val text = status?.trim().orEmpty()
    if (text.isEmpty()) return false
    if (text.startsWith("Ошибка")) return false
    if (text == "Отменено") return false
    return true
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

