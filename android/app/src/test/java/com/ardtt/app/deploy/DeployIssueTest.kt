package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployIssueTest {
    @Test
    fun insufficientDiskShowsInstallerNumbersWithoutCleanup() {
        val issue = DeployIssue.fromInstallerLine(
            raw = "ARDTT_ERROR|code=INSUFFICIENT_DISK|Мало места на /opt/ardtt: свободно 100 Б",
            hopRole = "entry",
            hopHost = "1.2.3.4",
            entryInstallStarted = false,
        )
        assertEquals(DeployIssue.INSUFFICIENT_DISK, issue.code)
        // Deploy v2 never cleans the host: no «retry with cleanup».
        assertFalse(DeployIssue.offersDiskCleanup(issue))
        assertEquals("VPS1: Мало места на /opt/ardtt: свободно 100 Б", issue.summary)
    }

    @Test
    fun diskFullFromInstallerCode() {
        val issue = DeployIssue.fromInstallerLine(
            raw = "ARDTT_ERROR|code=DISK_FULL|message=Мало места на /opt/ardtt: свободно 2430 МБ",
            hopRole = "entry",
            hopHost = "1.2.3.4",
            entryInstallStarted = false,
        )
        assertEquals(DeployIssue.DISK_FULL, issue.code)
        assertTrue(issue.summary.contains("мало места", ignoreCase = true))
    }

    @Test
    fun diskFullInferredFromLegacyMessage() {
        val issue = DeployIssue.fromInstallerLine(
            raw = "Мало места на /opt/ardtt: свободно 2430 МБ (нужно ≥2500 МБ).",
            hopRole = "entry",
            hopHost = "1.2.3.4",
            entryInstallStarted = true,
        )
        assertEquals(DeployIssue.DISK_FULL, issue.code)
        assertFalse(DeployIssue.offersDiskCleanup(issue))
    }

    @Test
    fun parseArdttErrorSplitsCodeAndMessage() {
        val (code, message) = DeployIssue.parseArdttError(
            "ARDTT_ERROR|code=DOCKER_MISSING|message=Docker CLI не найден (command -v docker).",
        )
        assertEquals(DeployIssue.DOCKER_MISSING, code)
        assertEquals("Docker CLI не найден (command -v docker).", message)
    }

    @Test
    fun parseArdttErrorAcceptsBareCodePayload() {
        val (code, message) = DeployIssue.parseArdttError(
            "code=DOCKER_NOT_RUNNING|daemon down",
        )
        assertEquals(DeployIssue.DOCKER_NOT_RUNNING, code)
        assertEquals("daemon down", message)
    }

    @Test
    fun parseArdttErrorWithoutCodeKeepsRaw() {
        val (code, message) = DeployIssue.parseArdttError("ssh: connect timed out")
        assertNull(code)
        assertEquals("ssh: connect timed out", message)
    }

    @Test
    fun dockerMissingOnExitMentionsVps1NotStarted() {
        val issue = DeployIssue.of(
            code = DeployIssue.DOCKER_MISSING,
            message = "docker-cli-missing",
            hopRole = "exit",
            hopHost = "144.31.215.4",
            entryInstallStarted = false,
        )
        assertEquals(
            "VPS2: в репозиториях этой системы нет Docker (RHEL/Alma/Rocky). " +
                "Установите Docker Engine вручную и повторите. Установка ARDTT на VPS1 ещё не запускалась.",
            issue.summary,
        )
    }

    @Test
    fun dockerMissingOnExitOmitsVps1NoteAfterEntryStarted() {
        val issue = DeployIssue.of(
            code = DeployIssue.DOCKER_MISSING,
            message = "docker",
            hopRole = "exit",
            entryInstallStarted = true,
        )
        assertFalse(issue.summary.contains("VPS1"))
    }

    @Test
    fun readinessFailureKeepsSentenceAndMovesDiagnosticsToDetail() {
        val raw = "ARDTT_ERROR|code=READINESS_FAILED|Новая версия 2.0.1 не прошла проверку готовности — " +
            "возвращена 2.0.0. state=running exit=0 oom=false not ready: dns журнал: [warp] boot"
        val issue = DeployIssue.fromInstallerLine(raw, hopRole = "entry", hopHost = "h", entryInstallStarted = true)
        assertEquals(DeployIssue.READINESS_FAILED, issue.code)
        assertEquals(
            "VPS1: Новая версия 2.0.1 не прошла проверку готовности — возвращена 2.0.0.",
            issue.summary,
        )
        assertTrue(issue.detail.contains("журнал:"))
    }

    @Test
    fun unknownInstallerCodeShowsInstallerMessage() {
        val issue = DeployIssue.fromInstallerLine(
            "ARDTT_ERROR|code=PORT_BUSY|Порт 9300/tcp (telemetry) занят другим процессом.",
            hopRole = "exit",
            hopHost = "h",
            entryInstallStarted = false,
        )
        assertEquals("PORT_BUSY", issue.code)
        assertTrue(issue.summary.startsWith("VPS2: Порт 9300/tcp (telemetry) занят"))
    }

    @Test
    fun looksFailedIgnoresPlainUserSummary() {
        val summary = "VPS2: в репозиториях этой системы нет Docker. " +
            "Установка ARDTT на VPS1 ещё не запускалась."
        assertFalse(DeployIssue.looksFailed(summary))
        assertTrue(DeployIssue.looksFailed("Ошибка: timeout"))
        assertTrue(DeployIssue.looksFailed("ARDTT_ERROR|code=SSH_FAILED|message=x"))
        assertFalse(DeployIssue.looksFailed("Готово"))
        assertFalse(DeployIssue.looksFailed("Отменено"))
    }

    @Test
    fun redactLogStripsSecretsAndKeys() {
        val raw = """
            password=super-secret
            token=ghp_abc
            -----BEGIN OPENSSH PRIVATE KEY-----
            AAAA
            -----END OPENSSH PRIVATE KEY-----
            docker missing
        """.trimIndent()
        val redacted = DeployIssue.redactLog(raw)
        assertFalse(redacted.contains("super-secret"))
        assertFalse(redacted.contains("ghp_abc"))
        assertFalse(redacted.contains("AAAA"))
        assertTrue(redacted.contains("password=***"))
        assertTrue(redacted.contains("token=***"))
        assertTrue(redacted.contains("[redacted-key]"))
        assertTrue(redacted.contains("docker missing"))
    }

    @Test
    fun redactLogKeepsFieldsAfterAdminToken() {
        val raw = "ARDTT_DONE|admin_token=0123456789abcdef|container=ardtt|telemetry_token=ghp_secret"
        val redacted = DeployIssue.redactLog(raw)
        assertFalse(redacted.contains("0123456789abcdef"))
        assertFalse(redacted.contains("ghp_secret"))
        assertTrue(redacted.contains("admin_token=***"))
        assertTrue(redacted.contains("container=ardtt"))
        assertTrue(redacted.contains("telemetry_token=***"))
    }

    @Test
    fun dockerComesFromDistroRepository() {
        assertTrue(DeployRuntimeBundle.INCLUDED)
        assertTrue(DeployRuntimeBundle.canPrepare("ubuntu", "26.04", "x86_64"))
        assertTrue(
            DeployRuntimeBundle.missingRuntimeMessage("ubuntu", "26.04", "x86_64")
                .contains("репозитория дистрибутива"),
        )
    }
}
