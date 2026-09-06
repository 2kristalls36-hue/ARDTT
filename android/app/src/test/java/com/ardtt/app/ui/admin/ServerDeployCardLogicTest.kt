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
            "SSH 22",
            serverCardMetaLine("159.194.225.162", "159.194.225.162", 22, "159.194.225.162"),
        )
        assertEquals(
            "10.0.0.1 · SSH 22",
            serverCardMetaLine("Edge", "10.0.0.1", 22, "10.0.0.1"),
        )
        assertEquals(
            "10.0.0.1 · SSH 22 · pub 203.0.113.10",
            serverCardMetaLine("Edge", "10.0.0.1", 22, "203.0.113.10"),
        )
        assertEquals(
            "SSH 2200 · pub 203.0.113.10",
            serverCardMetaLine("10.0.0.1", "10.0.0.1", 2200, "203.0.113.10"),
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
            "10.0.0.1 → 2.26.125.160 · SSH 22",
            serverCardMetaLine(
                name = "Edge",
                host = "10.0.0.1",
                sshPort = 22,
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals(
            "SSH 22",
            serverCardMetaLine(
                name = "10.0.0.1",
                host = "10.0.0.1",
                sshPort = 22,
                publicHost = "10.0.0.1",
                cascadeEnabled = true,
                cascadeHost = "2.26.125.160",
            ),
        )
        assertEquals(
            "10.0.0.1 · SSH 22",
            serverCardMetaLine("Edge", "10.0.0.1", 22, "10.0.0.1", cascadeEnabled = true, cascadeHost = ""),
        )
        assertEquals(
            "203.0.113.10 → 2.26.125.160 · SSH 22",
            serverCardMetaLine(
                name = "Edge",
                host = "10.0.0.1",
                sshPort = 22,
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
                sshPort = 22,
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
    }

    @Test
    fun statusLineDoesNotRepeatFreshnessWords() {
        val current = healthStatusParts(HealthUi.Online("1.0.6"))
        assertEquals("● Онлайн", current.presence)
        assertEquals("деплой 1.0.6", current.deploy)
        assertFalse(current.deploy.orEmpty().contains("актуален"))
        assertFalse(current.deploy.orEmpty().contains("нужно обновить"))

        val outdated = healthStatusParts(HealthUi.Online("1.0.5"))
        assertEquals("деплой 1.0.5", outdated.deploy)
        assertFalse(outdated.deploy.orEmpty().contains("актуален"))
        assertFalse(outdated.deploy.orEmpty().contains("нужно обновить"))
    }

    @Test
    fun statusLineShowsPingInsteadOfDeployAge() {
        val withPing = healthStatusParts(HealthUi.Online("1.0.12", pingMs = 42L))
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
        assertEquals("Установить деплой", serverOverviewDeployActionLabel(HealthUi.NotInstalled))
        assertEquals("Обновить деплой", serverOverviewDeployActionLabel(HealthUi.Unreachable))
        assertEquals("Обновить деплой", serverOverviewDeployActionLabel(HealthUi.Online("1.0.5")))
        assertEquals("Обновить деплой", serverOverviewDeployActionLabel(HealthUi.Checking))
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
        assertTrue(saved.contains("репозитория"))
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
            "Стек версии 1.0.29 будет снова скачан из репозитория и залит на 10.0.0.1 по указанным SSH-данным.",
            serverReinstallConfirmBody("10.0.0.1", "1.0.29"),
        )
        assertEquals(
            "Стек версии 1.0.29 будет снова скачан из репозитория и залит на VPS по указанным SSH-данным.",
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
    }

    @Test
    fun progressSheetTitleAfterFinish() {
        assertEquals(
            "Готово",
            deployProgressSheetTitle(busy = false, isUpdate = true, status = "Обновление завершено"),
        )
        assertEquals(
            "Ошибка",
            deployProgressSheetTitle(busy = false, isUpdate = false, status = "Ошибка: timeout"),
        )
        assertEquals(
            "Готово",
            deployProgressSheetTitle(busy = false, isUpdate = false, status = null),
        )
    }

    @Test
    fun deleteConfirmWarnsThatStackIsWipedOnVps() {
        assertEquals("Удалить сервер?", serverDeleteConfirmTitle())
        assertEquals("Удалить", serverDeleteConfirmAction())
        val standalone = serverDeleteConfirmBody("10.0.0.1")
        assertTrue(standalone.contains("10.0.0.1"))
        assertTrue(standalone.contains("/opt/ardtt"))
        assertTrue(standalone.contains("Сервера"))
        assertTrue(standalone.contains("необратимо"))
        assertFalse(standalone.contains("останутся без изменений"))
        val cascade = serverDeleteConfirmBody(
            host = "10.0.0.1",
            cascadeEnabled = true,
            cascadeHost = "2.26.125.160",
        )
        assertTrue(cascade.contains("входного сервера 10.0.0.1"))
        assertTrue(cascade.contains("выходного 2.26.125.160"))
    }

    @Test
    fun deleteConfirmWhenOfflineRemovesCardOnly() {
        assertFalse(serverDeleteIsOffline(HealthUi.Online("1.0.35")))
        assertFalse(serverDeleteIsOffline(HealthUi.NotInstalled))
        assertFalse(serverDeleteIsOffline(HealthUi.Checking))
        assertTrue(serverDeleteIsOffline(HealthUi.Unreachable))
        assertEquals("Нет связи с сервером", serverDeleteConfirmTitle(offline = true))
        assertEquals("Удалить карточку", serverDeleteConfirmAction(offline = true))
        val offline = serverDeleteConfirmBody("10.0.0.1", offline = true)
        assertTrue(offline.contains("не будет выполнено"))
        assertTrue(offline.contains("нет соединения"))
        assertTrue(offline.contains("10.0.0.1"))
        assertTrue(offline.contains("без деинсталляции"))
        assertFalse(offline.contains("/opt/ardtt"))
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
        val parts = healthStatusParts(HealthUi.Online("1.0.12", pingMs = 42L))
        assertEquals("● Онлайн", parts.presence)
        assertEquals("деплой 1.0.12", parts.deploy)
        assertEquals("42 мс", parts.pingLabel)
        assertEquals("● Нет связи", healthStatusParts(HealthUi.Unreachable).presence)
        assertNull(healthStatusParts(HealthUi.Unreachable).deploy)
    }

}
