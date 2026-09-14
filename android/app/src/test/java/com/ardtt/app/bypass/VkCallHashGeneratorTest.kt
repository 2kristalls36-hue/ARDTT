package com.ardtt.app.bypass

import com.ardtt.app.core.CallCreateOp
import com.ardtt.app.core.UserActionKind
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCallHashGeneratorTest {
    private val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"

    @Test
    fun oauthTimeoutIsOutcomeNotThrow() = runBlocking {
        var uncaught = 0
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught++ }
        try {
            val outcome = generateCallHash(
                hasSession = true,
                userAgent = "ua",
                cookieHeader = "remixsid=1",
                transport = VkApiTransport { _, _, _ -> throw SocketTimeoutException("oauth") },
            )
            val failure = outcome as CallHashOutcome.Failure
            assertEquals(CallHashErrorKind.TransientNetwork, failure.error.kind)
            assertEquals(CallHashPhase.OAuth, failure.error.phase)
            assertEquals(0, uncaught)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun callsStartIoExceptionIsTransient() = runBlocking {
        val transport = VkApiTransport { url, _, _ ->
            if (url.contains("oauth.vk.com")) {
                VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
            } else {
                throw IOException("calls.start")
            }
        }
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = transport,
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.TransientNetwork, outcome.error.kind)
        assertEquals(CallHashPhase.CallsStart, outcome.error.phase)
    }

    @Test
    fun callsStartErrorObjectIsFailure() = runBlocking {
        val transport = VkApiTransport { url, _, _ ->
            if (url.contains("oauth.vk.com")) {
                VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
            } else {
                VkHttpResponse(
                    200,
                    """{"error":{"error_code":10,"error_msg":"Internal server error"}}""",
                )
            }
        }
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = transport,
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.TransientNetwork, outcome.error.kind)
        assertEquals(10, outcome.error.apiCode)
        assertEquals(200, outcome.error.httpCode)
    }

    @Test
    fun successReturnsHash() = runBlocking {
        val transport = VkApiTransport { url, _, _ ->
            if (url.contains("oauth.vk.com")) {
                VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
            } else {
                VkHttpResponse(
                    200,
                    """{"response":{"join_link":"https://vk.com/call/join/$hash"}}""",
                )
            }
        }
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = transport,
        ) as CallHashOutcome.Success
        assertEquals(hash, outcome.hash)
    }

    @Test
    fun cancellationPropagates() {
        var uncaught = 0
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught++ }
        try {
            runBlocking {
                generateCallHash(
                    hasSession = true,
                    userAgent = "ua",
                    cookieHeader = "remixsid=1",
                    transport = VkApiTransport { _, _, _ -> throw CancellationException("stop") },
                )
            }
            error("expected cancel")
        } catch (_: CancellationException) {
            assertEquals(0, uncaught)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun oauthHttpErrorsAreTransientNotAuth() = runBlocking {
        for (code in intArrayOf(429, 502, 503, 504)) {
            for (body in arrayOf("", "<html>busy</html>")) {
                val outcome = generateCallHash(
                    hasSession = true,
                    userAgent = "ua",
                    cookieHeader = "remixsid=1",
                    transport = VkApiTransport { _, _, _ ->
                        VkHttpResponse(code, body, retryAfterMs = if (code == 429) 3_000L else null)
                    },
                ) as CallHashOutcome.Failure
                assertEquals("oauth $code $body", CallHashErrorKind.TransientNetwork, outcome.error.kind)
                assertEquals(code, outcome.error.httpCode)
                assertEquals(CallHashPhase.OAuth, outcome.error.phase)
                assertNotEquals(CallHashErrorKind.AuthRequired, outcome.error.kind)
            }
        }
    }

    @Test
    fun callsStartHttpErrorsAreTransientBeforeBodyParse() = runBlocking {
        val oauthOk = VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
        for (code in intArrayOf(429, 502, 503, 504)) {
            for (body in arrayOf("", "<html>unavailable</html>")) {
                val outcome = generateCallHash(
                    hasSession = true,
                    userAgent = "ua",
                    cookieHeader = "remixsid=1",
                    transport = VkApiTransport { url, _, _ ->
                        if (url.contains("oauth.vk.com")) oauthOk
                        else VkHttpResponse(code, body, retryAfterMs = 4_000L)
                    },
                ) as CallHashOutcome.Failure
                assertEquals("start $code", CallHashErrorKind.TransientNetwork, outcome.error.kind)
                assertEquals(code, outcome.error.httpCode)
                assertEquals(CallHashPhase.CallsStart, outcome.error.phase)
                assertEquals(4_000L, outcome.error.retryAfterMs)
            }
        }
    }

    @Test
    fun oauthRedirectAndApiSuccessStillWork() = runBlocking {
        val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = VkApiTransport { url, _, _ ->
                if (url.contains("oauth.vk.com")) {
                    VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
                } else {
                    VkHttpResponse(
                        200,
                        """{"response":{"join_link":"https://vk.com/call/join/$hash"}}""",
                    )
                }
            },
        ) as CallHashOutcome.Success
        assertEquals(hash, outcome.hash)
    }

    @Test
    fun explicitAuthAndCaptchaStayUserErrors() = runBlocking {
        val oauthOk = VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
        val auth = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = VkApiTransport { url, _, _ ->
                if (url.contains("oauth.vk.com")) oauthOk
                else VkHttpResponse(
                    200,
                    """{"error":{"error_code":5,"error_msg":"User authorization failed"}}""",
                )
            },
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.AuthRequired, auth.error.kind)
        val captcha = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = VkApiTransport { url, _, _ ->
                if (url.contains("oauth.vk.com")) oauthOk
                else VkHttpResponse(
                    200,
                    """{"error":{"error_code":14,"error_msg":"Captcha needed"}}""",
                )
            },
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.Captcha, captcha.error.kind)
        val forbidden = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = VkApiTransport { _, _, _ -> VkHttpResponse(403, "<html>forbid</html>") },
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.TransientNetwork, forbidden.error.kind)
        assertEquals(403, forbidden.error.httpCode)
    }

    @Test
    fun http503RecoveryEventIsBackoffNotAuth() = runBlocking {
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = VkApiTransport { _, _, _ ->
                VkHttpResponse(503, "<html>down</html>", retryAfterMs = 5_000L)
            },
        ) as CallHashOutcome.Failure
        val identity = CallRecreateIdentity(
            sessionEpoch = 1L,
            generation = 4L,
            profileId = "p",
            callEpoch = 2L,
            requestId = 8L,
            wantsConnected = true,
        )
        val event = callRecreateChangedFromOutcome(
            captured = identity,
            live = identity,
            outcome = outcome,
            holdService = true,
            networkAttempts = 0,
            underlayAllowsOps = true,
        )!!
        assertEquals(CallCreateOp.Backoff, event.op)
        assertTrue((event.retryDelayMs ?: 0L) >= 5_000L)
        assertNotEquals(UserActionKind.SignIn, event.userAction)
    }
}
