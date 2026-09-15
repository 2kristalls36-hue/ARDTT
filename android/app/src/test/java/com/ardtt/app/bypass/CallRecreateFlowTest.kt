package com.ardtt.app.bypass

import com.ardtt.app.core.ConnState
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manager-facing recreate path: production generator + identity gate + apply plan.
 * HTTP is replaced at [VkApiTransport], the same boundary ConnectionManager uses
 * via [VkCallHashGenerator.generateOutcome] / [generateCallHash].
 */
class CallRecreateFlowTest {
    private val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"

    private fun identity(
        sessionEpoch: Long = 1L,
        generation: Long = 4L,
        wantsConnected: Boolean = true,
        requestId: Long? = 8L,
    ) = CallRecreateIdentity(
        sessionEpoch = sessionEpoch,
        generation = generation,
        profileId = "p",
        callEpoch = 2L,
        requestId = requestId,
        wantsConnected = wantsConnected,
    )

    private fun oauthThen(start: VkApiTransport): VkApiTransport = VkApiTransport { url, cookie, ua ->
        if (url.contains("oauth.vk.com")) {
            VkHttpResponse(302, "", "https://oauth.vk.com/blank.html#access_token=tok")
        } else {
            start.get(url, cookie, ua)
        }
    }

    private fun planOf(
        outcome: CallHashOutcome,
        captured: CallRecreateIdentity = identity(),
        live: CallRecreateIdentity = identity(),
        networkAttempts: Int = 0,
        underlayAllowsOps: Boolean = true,
        silent: Boolean = true,
    ): Pair<CallRecreateDecision, CallRecreateApplyPlan> {
        val decision = evaluateCallRecreateResult(captured, live, outcome, networkAttempts)
        return decision to planCallRecreateApply(decision, underlayAllowsOps, silent)
    }

    @Test
    fun oauthTimeoutDoesNotEscapeLaunchOrDropIntent() = runBlocking {
        var uncaught = 0
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught++ }
        try {
            val jobParent = SupervisorJob()
            val scope = CoroutineScope(jobParent + Dispatchers.Unconfined)
            var plan: CallRecreateApplyPlan? = null
            val child = scope.launch {
                val outcome = generateCallHash(
                    hasSession = true,
                    userAgent = "ua",
                    cookieHeader = "remixsid=1",
                    transport = VkApiTransport { _, _, _ -> throw SocketTimeoutException("oauth") },
                )
                plan = planOf(outcome).second
            }
            child.join()
            assertTrue(child.isCompleted)
            assertFalse(child.isCancelled)
            assertEquals(0, uncaught)
            val applied = plan!!
            assertTrue(applied.keepWantsConnected)
            assertFalse(applied.stopTunnel)
            assertFalse(applied.endUserAttempt)
            assertEquals(ConnState.Recovering, applied.uiState)
            assertNull(applied.saveHash)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun callsStartIoExceptionKeepsIntent() = runBlocking {
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = oauthThen { _, _, _ -> throw IOException("calls.start") },
        ) as CallHashOutcome.Failure
        val plan = planOf(outcome).second
        assertEquals(CallHashErrorKind.TransientNetwork, outcome.error.kind)
        assertTrue(plan.keepWantsConnected)
        assertFalse(plan.stopTunnel)
        assertFalse(plan.endUserAttempt)
    }

    @Test
    fun vkErrorAndBadJsonDoNotCrashOrStop() = runBlocking {
        val errorObj = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = oauthThen { _, _, _ ->
                VkHttpResponse(200, """{"error":{"error_code":10,"error_msg":"Internal server error"}}""")
            },
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.TransientNetwork, errorObj.error.kind)
        assertEquals(10, errorObj.error.apiCode)
        val errorPlan = planOf(errorObj).second
        assertFalse(errorPlan.stopTunnel)
        assertTrue(errorPlan.keepWantsConnected)

        val badJson = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = oauthThen { _, _, _ -> VkHttpResponse(200, "{not json") },
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.InvalidResponse, badJson.error.kind)
        val jsonPlan = planOf(badJson).second
        assertEquals(ConnState.NeedsUserAction, jsonPlan.uiState)
        assertFalse(jsonPlan.stopTunnel)
        assertFalse(jsonPlan.endUserAttempt)

        val empty = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = oauthThen { _, _, _ ->
                VkHttpResponse(200, """{"response":{"join_link":""}}""")
            },
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.InvalidResponse, empty.error.kind)
        assertFalse(planOf(empty).second.stopTunnel)
    }

    @Test
    fun legalFailureUsesSamePlanRoute() {
        val outcome = CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.TransientNetwork,
                phase = CallHashPhase.OAuth,
                message = "timeout",
            ),
        )
        val plan = planOf(outcome).second
        assertEquals("wait_retry", plan.decisionName)
        assertFalse(plan.stopTunnel)
        assertTrue(plan.keepWantsConnected)
    }

    @Test
    fun cancellationDuringOauthIsNotAuth() {
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
            val plan = planOf(
                outcome = CallHashOutcome.Failure(
                    CallHashFailure(
                        kind = CallHashErrorKind.AuthRequired,
                        phase = CallHashPhase.OAuth,
                        message = "login",
                    ),
                ),
                live = identity(wantsConnected = false),
            ).second
            assertEquals("ignore_cancelled", plan.decisionName)
            assertNull(plan.prompt)
            assertEquals(0, uncaught)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun staleSessionADoesNotMutateSessionB() = runBlocking {
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = oauthThen { _, _, _ ->
                VkHttpResponse(
                    200,
                    """{"response":{"join_link":"https://vk.com/call/join/$hash"}}""",
                )
            },
        ) as CallHashOutcome.Success
        val plan = planOf(
            outcome = outcome,
            captured = identity(sessionEpoch = 1L, requestId = 8L, generation = 4L),
            live = identity(sessionEpoch = 2L, requestId = 9L, generation = 5L),
        ).second
        assertEquals("ignore_stale", plan.decisionName)
        assertNull(plan.saveHash)
        assertFalse(plan.reconnectBypass)
        assertFalse(plan.stopTunnel)
        assertFalse(plan.endUserAttempt)
    }

    @Test
    fun currentSessionSuccessAppliesHashOnce() = runBlocking {
        val outcome = generateCallHash(
            hasSession = true,
            userAgent = "ua",
            cookieHeader = "remixsid=1",
            transport = oauthThen { _, _, _ ->
                VkHttpResponse(
                    200,
                    """{"response":{"join_link":"https://vk.com/call/join/$hash"}}""",
                )
            },
        ) as CallHashOutcome.Success
        val plan = planOf(outcome).second
        assertEquals(hash, plan.saveHash)
        assertTrue(plan.reconnectBypass)
        assertEquals("apply_hash", plan.decisionName)
    }
}
