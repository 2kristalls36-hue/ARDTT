package com.ardtt.app.bypass

import com.ardtt.app.core.CallValidity
import com.ardtt.app.core.ConnState
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHashOutcomeTest {
    private val hash = "LrSXnSsyDo_yNx28kZQp9GBC-8T7xjCAx4D0tJ2paLI"

    private fun identity(
        sessionEpoch: Long = 1L,
        generation: Long = 4L,
        wantsConnected: Boolean = true,
        requestId: Long? = 8L,
        callEpoch: Long = 2L,
        profileId: String? = "p",
    ) = CallRecreateIdentity(
        sessionEpoch = sessionEpoch,
        generation = generation,
        profileId = profileId,
        callEpoch = callEpoch,
        requestId = requestId,
        wantsConnected = wantsConnected,
    )

    @Test
    fun timeoutIsTransientAndKeepsIntent() {
        val failure = classifyCallHashThrowable(
            SocketTimeoutException("timeout"),
            CallHashPhase.OAuth,
        )
        assertEquals(CallHashErrorKind.TransientNetwork, failure.kind)
        assertEquals(CallHashPhase.OAuth, failure.phase)
        assertFalse(failure.userMessage.contains("логин", ignoreCase = true))
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = CallHashOutcome.Failure(failure),
            networkAttempts = 0,
        )
        val retry = decision as CallRecreateDecision.WaitAndRetry
        assertEquals(CallValidity.UnknownDueToNetwork, retry.validity)
        assertTrue(retry.delayMs > 0L)
    }

    @Test
    fun ioExceptionOnCallsStartDoesNotStop() {
        val failure = classifyCallHashThrowable(
            IOException("closed"),
            CallHashPhase.CallsStart,
        )
        assertEquals(CallHashErrorKind.TransientNetwork, failure.kind)
        val exhausted = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = CallHashOutcome.Failure(failure),
            networkAttempts = 2,
        ) as CallRecreateDecision.UserAction
        assertFalse(exhausted.stopTunnel)
        assertEquals(CallRecreatePrompt.Ask, exhausted.prompt)
        assertEquals(TRANSIENT_NETWORK_MESSAGE, exhausted.message)
        assertFalse(exhausted.message.contains("логин", ignoreCase = true))
    }

    @Test
    fun vkErrorJsonPreservesApiCode() {
        val outcome = parseCallsStartBody(
            """{"error":{"error_code":5,"error_msg":"User authorization failed"}}""",
        ) as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.AuthRequired, outcome.error.kind)
        assertEquals(5, outcome.error.apiCode)
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = outcome,
            networkAttempts = 0,
        ) as CallRecreateDecision.UserAction
        assertEquals(CallRecreatePrompt.NeedLogin, decision.prompt)
        assertFalse(decision.stopTunnel)
    }

    @Test
    fun invalidJsonAndEmptyJoinLinkAreInvalidResponse() {
        val badJson = parseCallsStartBody("{not json") as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.InvalidResponse, badJson.error.kind)
        val empty = parseCallsStartBody("""{"response":{"join_link":""}}""") as CallHashOutcome.Failure
        assertEquals(CallHashErrorKind.InvalidResponse, empty.error.kind)
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = empty,
            networkAttempts = 0,
        ) as CallRecreateDecision.UserAction
        assertFalse(decision.stopTunnel)
        assertEquals(CallRecreatePrompt.Ask, decision.prompt)
    }

    @Test
    fun legalFailureDoesNotStopForTransientNetwork() {
        val outcome = CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.TransientNetwork,
                phase = CallHashPhase.OAuth,
                message = "timeout",
            ),
        )
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = outcome,
            networkAttempts = 0,
        )
        assertTrue(decision is CallRecreateDecision.WaitAndRetry)
    }

    @Test
    fun wantsConnectedFalseIsCancelNotAuth() {
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(wantsConnected = false),
            outcome = CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.Session,
                    message = "login",
                ),
            ),
            networkAttempts = 0,
        )
        assertEquals(CallRecreateDecision.IgnoreCancelled, decision)
    }

    @Test
    fun staleSessionDoesNotApplyHash() {
        val decision = evaluateCallRecreateResult(
            captured = identity(sessionEpoch = 1L, requestId = 8L),
            live = identity(sessionEpoch = 2L, requestId = 9L, generation = 5L),
            outcome = CallHashOutcome.Success(hash),
            networkAttempts = 0,
        )
        assertEquals(CallRecreateDecision.IgnoreStale, decision)
    }

    @Test
    fun successOfCurrentSessionAppliesOnce() {
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = CallHashOutcome.Success(hash),
            networkAttempts = 2,
        ) as CallRecreateDecision.ApplyHash
        assertEquals(hash, decision.hash)
    }

    @Test
    fun managerPlanKeepsIntentOnTransientNetwork() {
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = CallHashOutcome.Failure(
                classifyCallHashThrowable(SocketTimeoutException("timeout"), CallHashPhase.OAuth),
            ),
            networkAttempts = 0,
        )
        val plan = planCallRecreateApply(
            decision = decision,
            underlayAllowsOps = true,
            silentRecreateInFlight = true,
        )
        assertTrue(plan.keepWantsConnected)
        assertFalse(plan.stopTunnel)
        assertFalse(plan.endUserAttempt)
        assertEquals(ConnState.Recovering, plan.uiState)
        assertNull(plan.dispatchValidity)
        assertTrue((plan.retryDelayMs ?: 0L) > 0L)
        assertNull(plan.saveHash)
    }

    @Test
    fun managerPlanDoesNotStopOnLegalFailure() {
        val outcome = CallHashOutcome.Failure(
            CallHashFailure(
                kind = CallHashErrorKind.TransientNetwork,
                phase = CallHashPhase.CallsStart,
                message = "io",
            ),
        )
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = outcome,
            networkAttempts = 0,
        )
        val plan = planCallRecreateApply(decision, underlayAllowsOps = false, silentRecreateInFlight = true)
        assertTrue(plan.keepWantsConnected)
        assertFalse(plan.stopTunnel)
        assertEquals(ConnState.WaitingForNetwork, plan.uiState)
        assertEquals(CallValidity.UnknownDueToNetwork, plan.dispatchValidity)
    }

    @Test
    fun managerPlanStaleSuccessDoesNotSaveHash() {
        val decision = evaluateCallRecreateResult(
            captured = identity(sessionEpoch = 1L, requestId = 8L),
            live = identity(sessionEpoch = 2L, requestId = 9L, generation = 5L),
            outcome = CallHashOutcome.Success(hash),
            networkAttempts = 0,
        )
        val plan = planCallRecreateApply(decision, underlayAllowsOps = true, silentRecreateInFlight = true)
        assertEquals("ignore_stale", plan.decisionName)
        assertNull(plan.saveHash)
        assertFalse(plan.reconnectBypass)
        assertFalse(plan.stopTunnel)
        assertFalse(plan.endUserAttempt)
    }

    @Test
    fun managerPlanCancelIsNotAuth() {
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(wantsConnected = false),
            outcome = CallHashOutcome.Failure(
                CallHashFailure(
                    kind = CallHashErrorKind.AuthRequired,
                    phase = CallHashPhase.OAuth,
                    message = "login",
                ),
            ),
            networkAttempts = 0,
        )
        val plan = planCallRecreateApply(decision, underlayAllowsOps = true, silentRecreateInFlight = true)
        assertEquals("ignore_cancelled", plan.decisionName)
        assertNull(plan.prompt)
        assertFalse(plan.endUserAttempt)
        assertFalse(plan.stopTunnel)
    }

    @Test
    fun managerPlanSuccessAppliesHashOnce() {
        val decision = evaluateCallRecreateResult(
            captured = identity(),
            live = identity(),
            outcome = CallHashOutcome.Success(hash),
            networkAttempts = 1,
        )
        val plan = planCallRecreateApply(decision, underlayAllowsOps = true, silentRecreateInFlight = true)
        assertEquals(hash, plan.saveHash)
        assertTrue(plan.reconnectBypass)
        assertTrue(plan.keepWantsConnected)
    }

    @Test
    fun silentConfirmedDeadDoesNotRedispatch() {
        val decision = CallRecreateDecision.UserAction(
            prompt = CallRecreatePrompt.Ask,
            message = INVALID_RESPONSE_MESSAGE,
            validity = CallValidity.ConfirmedDead,
            stopTunnel = false,
        )
        val plan = planCallRecreateApply(decision, underlayAllowsOps = true, silentRecreateInFlight = true)
        assertNull(plan.dispatchValidity)
        assertFalse(plan.stopTunnel)
        assertEquals(ConnState.NeedsUserAction, plan.uiState)
    }

    @Test
    fun cancellationExceptionIsRethrown() {
        var uncaught = 0
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught++ }
        try {
            runBlocking(Dispatchers.Unconfined) {
                val job = launch {
                    classifyCallHashThrowable(CancellationException("stop"), CallHashPhase.OAuth)
                }
                job.join()
            }
        } catch (_: CancellationException) {
            // runBlocking may surface the child cancel
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(0, uncaught)
    }
}
