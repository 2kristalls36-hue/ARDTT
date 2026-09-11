package com.ardtt.app.ui.admin

import com.ardtt.app.deploy.DeployHopTrack
import com.ardtt.app.deploy.ProvisionAdminApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDeployCardLogicTest {
    @Test
    fun hostMetricFormatters() {
        assertEquals("1 ядро", formatHostCpuCores(1f))
        assertEquals("3.5 ядра", formatHostCpuCores(3.5f))
        assertEquals("8 ядер", formatHostCpuCores(8f))
        assertEquals("0.14", formatHostGiB((0.14 * 1024 * 1024 * 1024).toLong()))
        assertEquals("12%", formatHostPercent(12.4f))
        val host = ProvisionAdminApi.HostMetrics(
            memUsedBytes = (0.07 * 1024 * 1024 * 1024).toLong(),
            memTotalBytes = (4.45 * 1024 * 1024 * 1024).toLong(),
            diskUsedBytes = 0,
            diskTotalBytes = 0,
        )
        assertTrue(formatHostMemDetail(host).contains("ГБ"))
        assertEquals("— / — ГБ", formatHostDiskDetail(host))
    }

    @Test
    fun publicHostHiddenWhenSameAsSshHost() {
        assertNull(distinctPublicHost("159.194.225.162", "159.194.225.162"))
        assertNull(distinctPublicHost("159.194.225.162", " 159.194.225.162 "))
        assertNull(distinctPublicHost("Example.Host", "example.host"))
        assertNull(distinctPublicHost("10.0.0.1", ""))
        assertNull(distinctPublicHost("10.0.0.1", "   "))
    }

    @Test
    fun publicHostShownOnlyWhenDifferent() {
        assertEquals(
            "203.0.113.10",
            distinctPublicHost("10.0.0.1", "203.0.113.10"),
        )
        assertEquals(
            "vpn.example",
            distinctPublicHost("10.0.0.1", " vpn.example "),
        )
    }

    @Test
    fun cardTitlePrefersNameThenHost() {
        assertEquals("Edge", serverCardTitle("Edge", "10.0.0.1"))
        assertEquals("10.0.0.1", serverCardTitle("  ", "10.0.0.1"))
        assertEquals("10.0.0.1", serverCardTitle("10.0.0.1", "10.0.0.1"))
        assertEquals(
            "10.0.0.1 → 2.26.125.160",
            serverCardTitle("  ", "10.0.0.1", "10.0.0.1 → 2.26.125.160"),
        )
        assertEquals("Edge", serverCardTitle("Edge", "10.0.0.1", "10.0.0.1 → 2.26.125.160"))
    }

    @Test
    fun cardMetaOmitsHostWhenTitleIsTheHost() {
        assertEquals(
            "",
            serverCardMetaLine("159.194.225.162", "159.194.225.162", "159.194.225.162"),
        )
        assertEquals(
            "10.0.0.1",
            serverCardMetaLine("Edge", "10.0.0.1", "10.0.0.1"),
        )
        assertEquals(
            "10.0.0.1 · pub 203.0.113.10",
            serverCardMetaLine("Edge", "10.0.0.1", "203.0.113.10"),
        )
        assertEquals(
            "pub 203.0.113.10",
            serverCardMetaLine("10.0.0.1", "10.0.0.1", "203.0.113.10"),
        )
    }

    @Test
    fun cascadeCardShowsEntryExitIpSpan() {
        assertNull(
            serverCardCascadeIpSpan("10.0.0.1", "10.0.0.1", cascadeEnabled = false, cascadeHost = "2.26.125.160"),
        )
        assertNull(
            serverCardCascadeIpSpan("10.0.0.1", "10.0.0.1", cascadeEnabled = true, cascadeHost = ""),
        )
        assertEquals(
            "10.0.0.1 → 2.26.125.160",
            serverCardCascadeIpSpan(
                host = "10.0.0.1",
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160:51820",
            ),
        )
        assertEquals(
            "203.0.113.10 → 2.26.125.160",
            serverCardCascadeIpSpan(
                host = "10.0.0.1",
                publicHost = "203.0.113.10",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals(
            "10.0.0.1 → 2.26.125.160",
            serverCardMetaLine(
                name = "Edge",
                host = "10.0.0.1",
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals(
            "",
            serverCardMetaLine(
                name = "10.0.0.1",
                host = "10.0.0.1",
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals(
            "10.0.0.1",
            serverCardMetaLine("Edge", "10.0.0.1", "10.0.0.1", cascadeEnabled = true, cascadeHost = ""),
        )
        assertEquals(
            "203.0.113.10 → 2.26.125.160",
            serverCardMetaLine(
                name = "Edge",
                host = "10.0.0.1",
                publicHost = "203.0.113.10",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals(
            listOf("10.0.0.1", "2.26.125.160"),
            serverCardCascadeHosts(
                host = "10.0.0.1",
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160:22",
            ),
        )
        assertEquals(
            emptyList<String>(),
            serverCardTitleHosts("Edge", "10.0.0.1", listOf("10.0.0.1", "2.26.125.160")),
        )
        assertEquals(
            listOf("10.0.0.1", "2.26.125.160"),
            serverCardTitleHosts("  ", "10.0.0.1", listOf("10.0.0.1", "2.26.125.160")),
        )
        assertEquals(
            listOf("10.0.0.1", "2.26.125.160"),
            serverCardMetaParts(
                name = "Edge",
                host = "10.0.0.1",
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ).hosts,
        )
    }

    @Test
    fun osBadgeVersionStripsDuplicatedName() {
        assertNull(serverOsBadgeVersionText("ubuntu", ""))
        assertNull(serverOsBadgeVersionText("ubuntu", "Ubuntu"))
        assertEquals("24.04.1 LTS", serverOsBadgeVersionText("ubuntu", "Ubuntu 24.04.1 LTS"))
        assertEquals("40", serverOsBadgeVersionText("fedora", "Fedora 40"))
        assertEquals("Pop!_OS 22.04 LTS", serverOsBadgeVersionText("ubuntu", "Pop!_OS 22.04 LTS"))
        assertEquals("12", serverOsBadgeVersionText("debian", "12"))
    }

    @Test
    fun updateButtonHiddenWhenOnlineAndCurrent() {
        val health = HealthUi.Online("1.0.6")
        assertFalse(isDeployOutdated(health, "1.0.6"))
        assertFalse(shouldShowUpdateDeployButton(health, "1.0.6"))
        assertFalse(shouldShowUpdateDeployButton(health, " 1.0.6 "))
    }

    @Test
    fun updateButtonShownWhenOutdatedOfflineOrUnknown() {
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Online("1.0.5"), "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Online(""), "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Unreachable, "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.NotInstalled, "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(HealthUi.Checking, "1.0.6"))
        assertTrue(shouldShowUpdateDeployButton(null, "1.0.6"))
    }

    @Test
    fun freshnessChipOnlyWhenOnlineAndOutdated() {
        assertNull(deployFreshnessChipText(HealthUi.Online("1.0.6"), "1.0.6"))
        assertEquals(
            "Требуется обновление · 1.0.5 → 1.0.6",
            deployFreshnessChipText(HealthUi.Online("1.0.5"), "1.0.6"),
        )
        assertNull(deployFreshnessChipText(HealthUi.Unreachable, "1.0.6"))
        assertNull(deployFreshnessChipText(HealthUi.NotInstalled, "1.0.6"))
        assertNull(deployFreshnessChipText(HealthUi.Checking, "1.0.6"))
        assertNull(deployFreshnessChipText(null, "1.0.6"))
    }

    @Test
    fun deployLineUsesVersionWhenCurrentAndUpdateSentenceWhenBehind() {
        assertEquals("деплой 1.0.6", serverCardDeployText(HealthUi.Online("1.0.6"), "1.0.6"))
        assertEquals(
            "Требуется обновление · 1.0.5 → 1.0.6",
            serverCardDeployText(HealthUi.Online("1.0.5"), "1.0.6"),
        )
        assertNull(serverCardDeployText(HealthUi.Unreachable, "1.0.6"))
        assertNull(serverCardDeployText(HealthUi.NotInstalled, "1.0.6"))
        val current = healthStatusParts(HealthUi.Online("1.0.6"), "1.0.6")
        assertEquals("● Онлайн", current.presence)
        assertEquals("деплой 1.0.6", current.deploy)
        val outdated = healthStatusParts(HealthUi.Online("1.0.5"), "1.0.6")
        assertEquals("Требуется обновление · 1.0.5 → 1.0.6", outdated.deploy)
        assertEquals("● Онлайн", outdated.presence)
        val fromServer = healthStatusParts(
            HealthUi.Online("1.0.45", latestDeployVersion = "1.0.47"),
            "1.0.46",
        )
        assertEquals("Требуется обновление · 1.0.45 → 1.0.47", fromServer.deploy)
        assertEquals(
            "1.0.47",
            effectiveExpectedVersion(
                HealthUi.Online("1.0.45", latestDeployVersion = "1.0.47"),
                "1.0.46",
            ),
        )
        // APK git deploy version wins when newer than VPS-reported Releases tip.
        assertEquals(
            "1.0.51",
            effectiveExpectedVersion(
                HealthUi.Online("1.0.46", latestDeployVersion = "1.0.46"),
                "1.0.51",
            ),
        )
        assertEquals(
            "Требуется обновление · 1.0.46 → 1.0.51",
            serverCardDeployText(
                HealthUi.Online("1.0.46", latestDeployVersion = "1.0.46"),
                "1.0.51",
            ),
        )
    }

    @Test
    fun statusLineDoesNotRepeatFreshnessWords() {
        val current = healthStatusParts(HealthUi.Online("1.0.6"), "1.0.6")
        assertEquals("● Онлайн", current.presence)
        assertEquals("деплой 1.0.6", current.deploy)
        assertFalse(current.deploy.orEmpty().contains("актуален"))
        assertFalse(current.deploy.orEmpty().contains("нужно обновить"))

        val outdated = healthStatusParts(HealthUi.Online("1.0.5"), "1.0.6")
        assertEquals("Требуется обновление · 1.0.5 → 1.0.6", outdated.deploy)
        assertFalse(outdated.deploy.orEmpty().contains("актуален"))
        assertFalse(outdated.deploy.orEmpty().contains("нужно обновить"))
    }

    @Test
    fun statusLineShowsPingInsteadOfDeployAge() {
        val withPing = healthStatusParts(HealthUi.Online("1.0.12", pingMs = 42L), "1.0.12")
        assertEquals("деплой 1.0.12", withPing.deploy)
        assertEquals("42 мс", withPing.pingLabel)
        assertFalse(withPing.deploy.orEmpty().contains("назад"))
        assertFalse(withPing.deploy.orEmpty().contains("мин"))
    }

    @Test
    fun pingFormatterOmitsNonPositive() {
        assertEquals("", formatHealthPingMs(-1L))
        assertEquals("", formatHealthPingMs(0L))
        assertEquals("1 мс", formatHealthPingMs(1L))
    }

    @Test
    fun healthUiKeepsPingFromProbe() {
        val online = healthUiOf(
            ProvisionAdminApi.HealthInfo(ok = true, deployVersion = "1.0.12", pingMs = 18L),
        )
        assertEquals(HealthUi.Online("1.0.12", 18L), online)
        assertEquals(
            HealthUi.Online("1.0.12", 18L, latestDeployVersion = "1.0.47"),
            healthUiOf(
                ProvisionAdminApi.HealthInfo(
                    ok = true,
                    deployVersion = "1.0.12",
                    latestDeployVersion = "1.0.47",
                    pingMs = 18L,
                ),
            ),
        )
        assertEquals(HealthUi.Unreachable, healthUiOf(null))
        assertEquals(
            HealthUi.Unreachable,
            healthUiOf(ProvisionAdminApi.HealthInfo(ok = false, pingMs = 9L)),
        )
    }

    @Test
    fun failedHealthSplitsNotInstalledAndUnreachable() {
        assertEquals(
            HealthUi.NotInstalled,
            healthUiFromProbes(null, sshAuthOk = true),
        )
        assertEquals(
            HealthUi.Unreachable,
            healthUiFromProbes(null, sshAuthOk = false),
        )
        assertEquals(
            HealthUi.NotInstalled,
            healthUiFromProbes(
                ProvisionAdminApi.HealthInfo(ok = false, pingMs = 9L),
                sshAuthOk = true,
            ),
        )
        val online = healthUiFromProbes(
            ProvisionAdminApi.HealthInfo(ok = true, deployVersion = "1.0.12", pingMs = 18L),
            sshAuthOk = false,
        )
        assertEquals(HealthUi.Online("1.0.12", 18L), online)
    }

    @Test
    fun statusLineSplitsNotInstalledAndNoConnection() {
        val notInstalled = healthStatusParts(HealthUi.NotInstalled).presence
        val unreachable = healthStatusParts(HealthUi.Unreachable).presence
        assertEquals("● Не установлено", notInstalled)
        assertEquals("● Нет связи", unreachable)
        assertFalse(notInstalled.contains("нет связи", ignoreCase = true))
        assertFalse(unreachable.contains("установ", ignoreCase = true))
    }

    @Test
    fun savedCardActionIsAlwaysReinstall() {
        assertEquals("Переустановить деплой", serverDeployActionLabel(saved = true, cascadeEnabled = false))
        assertEquals("Переустановить деплой", serverDeployActionLabel(saved = true, cascadeEnabled = true))
    }

    @Test
    fun overviewDeployActionDependsOnInstallState() {
        assertEquals("Установить", serverOverviewDeployActionLabel(HealthUi.NotInstalled))
        assertEquals("Обновить", serverOverviewDeployActionLabel(HealthUi.Unreachable))
        assertEquals("Обновить", serverOverviewDeployActionLabel(HealthUi.Online("1.0.5")))
        assertEquals("Обновить", serverOverviewDeployActionLabel(HealthUi.Checking))
        assertEquals(
            ServerOverviewPrimaryAction.Check,
            serverOverviewPrimaryAction(HealthUi.Online("1.0.45"), "1.0.45"),
        )
        assertEquals(
            ServerOverviewPrimaryAction.Update,
            serverOverviewPrimaryAction(HealthUi.Online("1.0.44"), "1.0.45"),
        )
        assertEquals(
            ServerOverviewPrimaryAction.Install,
            serverOverviewPrimaryAction(HealthUi.NotInstalled, "1.0.45"),
        )
        assertEquals("Проверить", serverOverviewPrimaryLabel(ServerOverviewPrimaryAction.Check))
        assertEquals("Установить деплой?", serverOverviewDeployConfirmTitle(HealthUi.NotInstalled))
        assertEquals("Обновить деплой?", serverOverviewDeployConfirmTitle(HealthUi.Online("1.0.5")))
        assertEquals("Установить", serverOverviewDeployConfirmAction(HealthUi.NotInstalled))
        assertEquals("Обновить", serverOverviewDeployConfirmAction(HealthUi.Unreachable))
        assertFalse(serverOverviewDeployIsUpdate(HealthUi.NotInstalled))
        assertTrue(serverOverviewDeployIsUpdate(HealthUi.Unreachable))
        assertTrue(serverOverviewDeployIsUpdate(HealthUi.Checking))
    }

    @Test
    fun newCardActionDependsOnCascade() {
        assertEquals("Установить на VPS", serverDeployActionLabel(saved = false, cascadeEnabled = false))
        assertEquals("Установить каскад", serverDeployActionLabel(saved = false, cascadeEnabled = true))
        assertEquals(
            "Второй сервер — выход в интернет и WARP. SSH к нему идёт через первый VPS. Клиенты живут на первом.",
            cascadeDeploySwitchSubtitle(),
        )
        assertEquals("Адрес, как его видит VPS 1", cascadeExitHostPlaceholder())
    }

    @Test
    fun deployScreenTitleDependsOnSavedCard() {
        assertEquals("Параметры сервера", serverDeployScreenTitle(saved = true))
        assertEquals("Деплой", serverDeployScreenTitle(saved = false))
    }

    @Test
    fun deployFormHelpMentionsSheetOnSavedCard() {
        val saved = serverDeployFormHelp(
            saved = true,
            cascadeEnabled = false,
            expectedVersion = "1.0.29",
        )
        assertTrue(saved.contains("GitHub Releases"))
        assertTrue(saved.contains("1.0.29"))
        assertTrue(saved.contains("снизу"))
        val fresh = serverDeployFormHelp(
            saved = false,
            cascadeEnabled = true,
            expectedVersion = "1.0.29",
        )
        assertTrue(fresh.contains("Установить каскад"))
        assertFalse(fresh.contains("Переустановить"))
    }

    @Test
    fun reinstallConfirmNamesHostAndVersion() {
        assertEquals(
            "На 10.0.0.1 будет запущен fetch-and-install: VPS сам скачает стек версии 1.0.29 из GitHub Releases и поставит его. Телефон только запускает скрипт по SSH.",
            serverReinstallConfirmBody("10.0.0.1", "1.0.29"),
        )
        assertEquals(
            "На VPS будет запущен fetch-and-install: VPS сам скачает стек версии 1.0.29 из GitHub Releases и поставит его. Телефон только запускает скрипт по SSH.",
            serverReinstallConfirmBody("  ", "1.0.29"),
        )
    }

    @Test
    fun progressSheetTitleWhileBusy() {
        assertEquals(
            "Установка деплоя…",
            deployProgressSheetTitle(busy = true, isUpdate = false, status = "что угодно"),
        )
        assertEquals(
            "Обновление деплоя…",
            deployProgressSheetTitle(busy = true, isUpdate = true, status = ""),
        )
        assertEquals(
            "Удаление деплоя…",
            deployProgressSheetTitle(
                busy = true,
                isUpdate = false,
                status = "",
                isUninstall = true,
            ),
        )
    }

    @Test
    fun cascadeSlotsAreVps1ThenVps2AndOnlyDoneIsGreenPhase() {
        val track = DeployHopTrack(
            cascade = true,
            entryHost = "45.129.2.3",
            exitHost = "2.26.125.160",
            activeHost = "2.26.125.160",
        )
        val installingExit = cascadeDeploySlots(track, failed = false, finishedSuccess = false)
        assertEquals("VPS 1", installingExit[0].title)
        assertEquals("45.129.2.3", installingExit[0].host)
        assertEquals(DeploySlotPhase.Pending, installingExit[0].phase)
        assertEquals("VPS 2", installingExit[1].title)
        assertEquals("2.26.125.160", installingExit[1].host)
        assertEquals(DeploySlotPhase.Active, installingExit[1].phase)
        assertEquals("Ожидание", deploySlotStatusText(installingExit[0].phase, false, false))
        assertEquals("Идёт установка", deploySlotStatusText(installingExit[1].phase, false, false))

        val afterExit = cascadeDeploySlots(
            track.copy(exitDone = true, activeHost = "45.129.2.3"),
            failed = false,
            finishedSuccess = false,
        )
        assertEquals(DeploySlotPhase.Active, afterExit[0].phase)
        assertEquals(DeploySlotPhase.Done, afterExit[1].phase)
        assertEquals("Идёт обновление", deploySlotStatusText(afterExit[0].phase, isUpdate = true, isUninstall = false))
        assertEquals("Готово", deploySlotStatusText(afterExit[1].phase, false, false))

        val finished = cascadeDeploySlots(
            track.copy(entryDone = true, exitDone = true, activeHost = ""),
            failed = false,
            finishedSuccess = true,
        )
        assertEquals(DeploySlotPhase.Done, finished[0].phase)
        assertEquals(DeploySlotPhase.Done, finished[1].phase)

        val failedExit = cascadeDeploySlots(
            track.copy(activeHost = "2.26.125.160", exitDone = false, entryDone = false),
            failed = true,
            finishedSuccess = false,
        )
        assertEquals(DeploySlotPhase.Skipped, failedExit[0].phase)
        assertEquals(DeploySlotPhase.Failed, failedExit[1].phase)
        assertEquals("Не начиналась", deploySlotStatusText(failedExit[0].phase, false, false))

        assertTrue(cascadeDeploySlots(DeployHopTrack(), failed = false, finishedSuccess = false).isEmpty())
        assertTrue(deployProgressFinishedSuccess(busy = false, status = "Каскад установлен"))
        assertFalse(deployProgressFinishedSuccess(busy = true, status = "Каскад установлен"))
        assertTrue(deployProgressFailed(busy = false, status = "Ошибка: SSH"))
        assertFalse(deployProgressFailed(busy = true, status = "Ошибка: SSH"))
    }

    @Test
    fun cascadeSshUserDefaultsToRootAndKeyCanReplacePassword() {
        assertEquals("root", deploySshUserOrRoot(""))
        assertEquals("root", deploySshUserOrRoot("  "))
        assertEquals("ubuntu", deploySshUserOrRoot("ubuntu"))
        assertTrue(deploySshSecretMissing(password = "", privateKeyPem = ""))
        assertFalse(deploySshSecretMissing(password = "x", privateKeyPem = ""))
        assertFalse(deploySshSecretMissing(password = "", privateKeyPem = "PEM"))
        assertEquals("Деплой уже идёт", deployBusyIssue().summary)
        assertEquals(com.ardtt.app.deploy.DeployIssue.BUSY, deployBusyIssue().code)
        assertFalse(deployBusyIssue().summary.startsWith("Ошибка"))
    }

    @Test
    fun progressSheetTitleAfterFinish() {
        assertEquals(
            "Готово",
            deployProgressSheetTitle(busy = false, isUpdate = true, status = "Обновление завершено"),
        )
        assertEquals(
            "Не завершено",
            deployProgressSheetTitle(busy = false, isUpdate = false, status = "Ошибка: timeout"),
        )
        assertEquals(
            "Не завершено",
            deployProgressSheetTitle(
                busy = false,
                isUpdate = false,
                status = "VPS2: не удалось поставить Docker Engine из архива.",
                failure = com.ardtt.app.deploy.DeployIssue.of(
                    com.ardtt.app.deploy.DeployIssue.DOCKER_MISSING,
                    "docker",
                    hopRole = "exit",
                ),
            ),
        )
        assertEquals(
            "Проверка узлов…",
            deployProgressSheetTitle(
                busy = true,
                isUpdate = false,
                status = null,
                isPreflight = true,
            ),
        )
        assertEquals(
            "Готово",
            deployProgressSheetTitle(busy = false, isUpdate = false, status = null),
        )
        val dockerSummary = "VPS2: не удалось поставить Docker Engine из архива. " +
            "Установка ARDTT на VPS1 ещё не запускалась."
        assertFalse(deployProgressFailed(busy = false, status = dockerSummary, failure = null))
        assertTrue(
            deployProgressFailed(
                busy = false,
                status = dockerSummary,
                failure = com.ardtt.app.deploy.DeployIssue.of(
                    com.ardtt.app.deploy.DeployIssue.DOCKER_MISSING,
                    "docker",
                    hopRole = "exit",
                ),
            ),
        )
    }

    @Test
    fun deleteConfirmWarnsThatStackIsWipedOnVps() {
        assertEquals(
            "Деинсталляция сервера?",
            serverDeleteConfirmTitle(ServerRemoveKind.Uninstall),
        )
        assertEquals(
            "Деинсталлировать",
            serverDeleteConfirmAction(ServerRemoveKind.Uninstall),
        )
        assertEquals(
            "Деинсталляция сервера",
            serverRemoveMenuLabel(ServerRemoveKind.Uninstall),
        )
        val standalone = serverDeleteConfirmBody("10.0.0.1", kind = ServerRemoveKind.Uninstall)
        assertTrue(standalone.contains("10.0.0.1"))
        assertTrue(standalone.contains("/opt/ardtt"))
        assertTrue(standalone.contains("Серверы"))
        assertTrue(standalone.contains("необратимо"))
        assertFalse(standalone.contains("останутся без изменений"))
        val cascade = serverDeleteConfirmBody(
            host = "10.0.0.1",
            cascadeEnabled = true,
            cascadeHost = "2.26.125.160",
            kind = ServerRemoveKind.Uninstall,
        )
        assertTrue(cascade.contains("входного сервера 10.0.0.1"))
        assertTrue(cascade.contains("выходного 2.26.125.160"))
    }

    @Test
    fun deleteCardConfirmRemovesLocalEntryOnly() {
        assertEquals(
            "Удалить карточку сервера",
            serverRemoveMenuLabel(ServerRemoveKind.Card),
        )
        assertEquals(
            "Удалить карточку сервера?",
            serverDeleteConfirmTitle(ServerRemoveKind.Card),
        )
        assertEquals(
            "Удалить карточку",
            serverDeleteConfirmAction(ServerRemoveKind.Card),
        )
        val card = serverDeleteConfirmBody("10.0.0.1", kind = ServerRemoveKind.Card)
        assertTrue(card.contains("только из приложения"))
        assertTrue(card.contains("10.0.0.1"))
        assertTrue(card.contains("деинсталляция не выполняется"))
        assertFalse(card.contains("/opt/ardtt"))
    }

    @Test
    fun deleteFinishedLeavesOnlyAfterSuccessfulUninstall() {
        assertFalse(serverDeleteFinishedShouldLeave(busy = true, status = "Стек снят"))
        assertFalse(serverDeleteFinishedShouldLeave(busy = false, status = null))
        assertFalse(serverDeleteFinishedShouldLeave(busy = false, status = "Ошибка: SSH"))
        assertFalse(serverDeleteFinishedShouldLeave(busy = false, status = "Отменено"))
        assertTrue(
            serverDeleteFinishedShouldLeave(
                busy = false,
                status = "Стек снят с 10.0.0.1. Карточка удалена.",
            ),
        )
    }

    @Test
    fun pingLatencyTierBands() {
        assertNull(pingLatencyTier(-1L))
        assertNull(pingLatencyTier(0L))
        assertEquals(PingLatencyTier.Good, pingLatencyTier(1L))
        assertEquals(PingLatencyTier.Good, pingLatencyTier(80L))
        assertEquals(PingLatencyTier.Fair, pingLatencyTier(81L))
        assertEquals(PingLatencyTier.Fair, pingLatencyTier(200L))
        assertEquals(PingLatencyTier.Poor, pingLatencyTier(201L))
    }

    @Test
    fun healthStatusPartsSplitOnlineLine() {
        val parts = healthStatusParts(HealthUi.Online("1.0.12", pingMs = 42L), "1.0.12")
        assertEquals("● Онлайн", parts.presence)
        assertEquals("деплой 1.0.12", parts.deploy)
        assertEquals("42 мс", parts.pingLabel)
        assertEquals("● Нет связи", healthStatusParts(HealthUi.Unreachable).presence)
        assertNull(healthStatusParts(HealthUi.Unreachable).deploy)
    }

}
