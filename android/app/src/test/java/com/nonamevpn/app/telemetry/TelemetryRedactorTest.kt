package com.nonamevpn.app.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRedactorTest {
    @Test
    fun redactsCredentialsFromOperationalLogs() {
        val raw = """
            Authorization: Bearer secret-token
            Cookie: session=secret-cookie
            {"privateKey":"private-value","password":"password-value"}
            https://example.test/?access_token=url-secret&ok=1
            -----BEGIN PRIVATE KEY-----
            pem-secret
            -----END PRIVATE KEY-----
        """.trimIndent()

        val result = TelemetryRedactor.redact(raw)

        listOf(
            "secret-token",
            "secret-cookie",
            "private-value",
            "password-value",
            "url-secret",
            "pem-secret",
        ).forEach { secret -> assertFalse(result.contains(secret)) }
        assertTrue(result.contains("[REDACTED]"))
        assertTrue(result.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun truncatesLongMessages() {
        val result = TelemetryRedactor.redact("x".repeat(100), maxChars = 20)

        assertTrue(result.startsWith("x".repeat(20)))
        assertTrue(result.contains("truncated 80 chars"))
    }
}
