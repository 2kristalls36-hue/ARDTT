package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployIssueTest {
    @Test
    fun diskFullFromInstallerCode() {
        val issue = DeployIssue.fromInstallerLine(
            raw = "ARDTT_ERROR|code=DISK_FULL|message=Мало места на /opt/ardtt: свободно 2430 МБ",
            hopRole = "entry",
            hopHost = "1.2.3.4",
            entryInstallStarted = false,
        )
        assertEquals(DeployIssue.DISK_FULL, issue.code)
        assertTrue(DeployIssue.offersDiskCleanup(issue))
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
        assertTrue(DeployIssue.offersDiskCleanup(issue))
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
            "VPS2: не удалось поставить Docker Engine из архива. Установка ARDTT на VPS1 ещё не запускалась.",
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
        assertEquals("VPS2: не удалось поставить Docker Engine из архива.", issue.summary)
    }

    @Test
    fun looksFailedIgnoresPlainUserSummary() {
        val summary = "VPS2: не удалось поставить Docker Engine из архива. " +
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
    fun runtimeBundleIsIncludedInPackage() {
        assertTrue(DeployRuntimeBundle.INCLUDED)
        assertTrue(DeployRuntimeBundle.canPrepare("ubuntu", "26.04", "x86_64"))
        assertTrue(
            DeployRuntimeBundle.missingRuntimeMessage("ubuntu", "26.04", "x86_64")
                .contains("ставится из архива"),
        )
    }
}
