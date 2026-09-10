package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.DeployBundle
import com.ardtt.app.deploy.DeployHop
import com.ardtt.app.deploy.DeployHopTrack
import com.ardtt.app.deploy.DeployIssue
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
        /** From provision `/health` when the VPS knows a newer stack release. */
        val latestDeployVersion: String = "",
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
        return HealthUi.Online(
            deployVersion = info.deployVersion,
            pingMs = info.pingMs,
            latestDeployVersion = info.latestDeployVersion,
        )
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
 * Card status: presence sits under the OS badge; deploy is left, ping is right.
 * An outdated online host replaces the version with the update sentence.
 */
internal data class HealthStatusParts(
    val presence: String,
    val deploy: String? = null,
    val pingMs: Long = -1L,
) {
    val pingLabel: String get() = formatHealthPingMs(pingMs)
}

internal fun effectiveExpectedVersion(health: HealthUi?, fallback: String): String {
    val fromServer = (health as? HealthUi.Online)?.latestDeployVersion?.trim().orEmpty()
    if (fromServer.isNotEmpty()) return fromServer
    return fallback.trim()
}

internal fun healthStatusParts(
    health: HealthUi?,
    expectedVersion: String = "",
): HealthStatusParts {
    val expected = effectiveExpectedVersion(health, expectedVersion)
    return when (health) {
        null, HealthUi.Checking -> HealthStatusParts("● Проверка…")
        is HealthUi.Online -> HealthStatusParts(
            presence = "● Онлайн",
            deploy = serverCardDeployText(health, expected),
            pingMs = health.pingMs,
        )
        HealthUi.NotInstalled -> HealthStatusParts("● Не установлено")
        HealthUi.Unreachable -> HealthStatusParts("● Нет связи")
    }
}

/** Version when current; the update sentence when the stack is behind. */
internal fun serverCardDeployText(health: HealthUi?, expectedVersion: String): String? {
    val online = health as? HealthUi.Online ?: return null
    return deployFreshnessChipText(online, expectedVersion)
        ?: "деплой ${online.deployVersion.ifBlank { "—" }}"
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
internal fun serverCardCascadeHosts(
    host: String,
    publicHost: String,
    cascadeEnabled: Boolean,
    cascadeHost: String,
): List<String> {
    if (!cascadeEnabled) return emptyList()
    val entry = (
        DeployHop.host(publicHost)
            ?: publicHost.trim().ifBlank { null }
            ?: DeployHop.host(host)
            ?: host.trim().ifBlank { null }
        ) ?: return emptyList()
    val exit = DeployHop.host(cascadeHost)?.takeIf { it.isNotBlank() } ?: return emptyList()
    if (entry.equals(exit, ignoreCase = true)) return emptyList()
    return listOf(entry, exit)
}

internal fun serverCardCascadeIpSpan(
    host: String,
    publicHost: String,
    cascadeEnabled: Boolean,
    cascadeHost: String,
): String? = serverCardCascadeHosts(host, publicHost, cascadeEnabled, cascadeHost)
    .takeIf { it.size >= 2 }
    ?.joinToString(" → ")

/** IPs painted as gray chips in the title when the card has no custom name. */
internal fun serverCardTitleHosts(
    name: String,
    host: String,
    cascadeHosts: List<String>,
): List<String> {
    val trimmed = name.trim()
    val ssh = host.trim()
    if (trimmed.isNotEmpty() && !trimmed.equals(ssh, ignoreCase = true)) return emptyList()
    return cascadeHosts.takeIf { it.size >= 2 } ?: listOfNotNull(ssh.ifBlank { null })
}

internal data class ServerCardMetaParts(
    val hosts: List<String>,
    val sshPort: Int,
    val pubHost: String? = null,
)

/** SSH / pub facts under the title — never repeats the title IP. Cascade is `ip → ip`. */
internal fun serverCardMetaParts(
    name: String,
    host: String,
    sshPort: Int,
    publicHost: String,
    cascadeEnabled: Boolean = false,
    cascadeHost: String = "",
): ServerCardMetaParts {
    val cascade = serverCardCascadeHosts(host, publicHost, cascadeEnabled, cascadeHost)
    val title = serverCardTitle(name, host, cascade.takeIf { it.size >= 2 }?.joinToString(" → "))
    val ssh = host.trim()
    if (cascade.isNotEmpty()) {
        val hosts = if (cascade.joinToString(" → ").equals(title, ignoreCase = true)) {
            emptyList()
        } else {
            cascade
        }
        return ServerCardMetaParts(hosts = hosts, sshPort = sshPort)
    }
    val showSshHost = ssh.isNotEmpty() && !ssh.equals(title, ignoreCase = true)
    return ServerCardMetaParts(
        hosts = if (showSshHost) listOf(ssh) else emptyList(),
        sshPort = sshPort,
        pubHost = distinctPublicHost(ssh, publicHost),
    )
}

internal fun ServerCardMetaParts.asLine(): String = buildList {
    when {
        hosts.size >= 2 -> add(hosts.joinToString(" → "))
        hosts.size == 1 -> add(hosts[0])
    }
    add("SSH $sshPort")
    pubHost?.let { add("pub $it") }
}.joinToString(" · ")

/** SSH / pub facts under the title — never repeats the title IP. Cascade shows `ip → ip`. */
internal fun serverCardMetaLine(
    name: String,
    host: String,
    sshPort: Int,
    publicHost: String,
    cascadeEnabled: Boolean = false,
    cascadeHost: String = "",
): String = serverCardMetaParts(
    name = name,
    host = host,
    sshPort = sshPort,
    publicHost = publicHost,
    cascadeEnabled = cascadeEnabled,
    cascadeHost = cascadeHost,
).asLine()

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
    val expected = effectiveExpectedVersion(health, expectedVersion)
    return !DeployBundle.isCurrent(online.deployVersion, expected)
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

internal fun cascadeDeploySwitchSubtitle(): String =
    "Второй сервер — выход в интернет и WARP. SSH к нему идёт через первый VPS. Клиенты живут на первом."

internal fun cascadeExitHostPlaceholder(): String = "Адрес, как его видит VPS 1"

internal enum class ServerOverviewPrimaryAction {
    Check,
    Install,
    Update,
}

internal fun serverOverviewPrimaryAction(
    health: HealthUi?,
    expectedVersion: String,
): ServerOverviewPrimaryAction {
    val expected = effectiveExpectedVersion(health, expectedVersion)
    return when (health) {
        HealthUi.NotInstalled -> ServerOverviewPrimaryAction.Install
        is HealthUi.Online -> if (DeployBundle.isCurrent(health.deployVersion, expected)) {
            ServerOverviewPrimaryAction.Check
        } else {
            ServerOverviewPrimaryAction.Update
        }
        else -> ServerOverviewPrimaryAction.Check
    }
}

internal fun serverOverviewPrimaryLabel(action: ServerOverviewPrimaryAction): String = when (action) {
    ServerOverviewPrimaryAction.Check -> "Проверить"
    ServerOverviewPrimaryAction.Install -> "Установить"
    ServerOverviewPrimaryAction.Update -> "Обновить"
}

/** Overview sticky / overflow: first install vs refresh of an existing stack. */
internal fun serverOverviewDeployActionLabel(health: HealthUi?): String =
    if (health is HealthUi.NotInstalled) "Установить" else "Обновить"

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
        return "Кнопка «Переустановить деплой» запустит на VPS fetch-and-install для стека $expectedVersion " +
            "(пакет скачает сам сервер с GitHub Releases). Ход установки откроется снизу."
    }
    val action = serverDeployActionLabel(saved = false, cascadeEnabled = cascadeEnabled)
    return "«Сохранить» только добавляет VPS в список. Установка стека — кнопка «$action»."
}

internal fun serverReinstallConfirmBody(host: String, expectedVersion: String): String {
    val target = host.trim().ifBlank { "VPS" }
    return "На $target будет запущен fetch-and-install: VPS сам скачает стек версии $expectedVersion из GitHub Releases и поставит его. Телефон только запускает скрипт по SSH."
}

/** Same default as the first VPS SSH user field. */
internal fun deploySshUserOrRoot(raw: String): String = raw.trim().ifBlank { "root" }

internal fun deploySshSecretMissing(password: String, privateKeyPem: String): Boolean =
    password.isBlank() && privateKeyPem.isBlank()

internal fun deployBusyIssue(): DeployIssue =
    DeployIssue.of(DeployIssue.BUSY, "Деплой уже идёт")

internal fun deployProgressSheetTitle(
    busy: Boolean,
    isUpdate: Boolean,
    status: String?,
    isUninstall: Boolean = false,
    failure: DeployIssue? = null,
    isPreflight: Boolean = false,
): String = when {
    busy && isUninstall -> "Удаление деплоя…"
    busy && isPreflight -> "Проверка узлов…"
    busy && isUpdate -> "Обновление деплоя…"
    busy -> "Установка деплоя…"
    failure != null && !failure.isCancelled -> "Не завершено"
    status?.let { DeployIssue.looksFailed(it) } == true -> "Не завершено"
    else -> "Готово"
}

internal fun deployProgressFinishedSuccess(
    busy: Boolean,
    status: String?,
    failure: DeployIssue? = null,
): Boolean {
    if (busy) return false
    if (failure != null && !failure.isCancelled) return false
    val text = status?.trim().orEmpty()
    if (text.isEmpty()) return false
    if (text == "Отменено") return false
    if (DeployIssue.looksFailed(text)) return false
    return true
}

internal fun deployProgressFailed(
    busy: Boolean,
    status: String?,
    failure: DeployIssue? = null,
): Boolean {
    if (busy) return false
    if (failure != null) return !failure.isCancelled
    return DeployIssue.looksFailed(status)
}

internal enum class DeploySlotPhase {
    Pending,
    Active,
    Done,
    Failed,
    Skipped,
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
    DeploySlotPhase.Skipped -> "Не начиналась"
}

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
    if (failed) return DeploySlotPhase.Skipped
    if (active) return DeploySlotPhase.Active
    return DeploySlotPhase.Pending
}

internal fun serverDeleteIsOffline(health: HealthUi?): Boolean =
    health is HealthUi.Unreachable

internal fun serverDeleteConfirmTitle(offline: Boolean = false): String =
    if (offline) "Нет связи с сервером" else "Удалить сервер?"

internal fun serverDeleteConfirmAction(offline: Boolean = false): String =
    if (offline) "Удалить карточку" else "Удалить"

internal fun serverDeleteConfirmBody(
    host: String,
    cascadeEnabled: Boolean = false,
    cascadeHost: String = "",
    offline: Boolean = false,
): String {
    val entry = host.trim().ifBlank { "VPS" }
    if (offline) {
        return "Удаление деплоя не будет выполнено: нет соединения с сервером $entry. " +
            "Стек на VPS останется установленным. Удалить только карточку из приложения, " +
            "без деинсталляции самого деплоя?"
    }
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
        "После успешного снятия стека карточка исчезнет из вкладки «Серверы». Действие необратимо."
}

/** Leave the overview only after a finished uninstall that actually removed the card. */
internal fun serverDeleteFinishedShouldLeave(busy: Boolean, status: String?): Boolean {
    if (busy) return false
    val text = status?.trim().orEmpty()
    if (text.isEmpty()) return false
    if (text == "Отменено") return false
    if (DeployIssue.looksFailed(text)) return false
    return true
}

/**
 * Update sentence for an online host that is not current.
 * Shown in place of the deploy version — not as a fourth card line.
 */
internal fun deployFreshnessChipText(health: HealthUi?, expectedVersion: String): String? {
    val online = health as? HealthUi.Online ?: return null
    val expected = effectiveExpectedVersion(health, expectedVersion)
    if (DeployBundle.isCurrent(online.deployVersion, expected)) return null
    val installed = online.deployVersion.trim().ifBlank { "—" }
    val want = expected.ifBlank { "—" }
    return "Требуется обновление · $installed → $want"
}

