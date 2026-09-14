package com.ardtt.app.bypass

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
