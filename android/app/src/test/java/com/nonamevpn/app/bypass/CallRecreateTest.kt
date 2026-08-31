package com.nonamevpn.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecreateTest {

    @Test
    fun classifiesGoCallUnavailableError() {
        assertEquals(
            BypassFatalKind.DeadCall,
            classifyBypassFatalKind(
                "[STREAM 1] [VK Auth] VK Calls path returned non-retryable call error: " +
                    "VK call is unavailable (error_code=951)",
            ),
        )
        assertEquals(
            BypassFatalKind.DeadCall,
            classifyBypassFatalKind("[STREAM 1] CALL_UNAVAILABLE: VK returns error: x (error_code=954)"),
        )
        assertEquals(
            BypassFatalKind.DeadCall,
            classifyBypassFatalKind("call not found"),
        )
        assertEquals(
            BypassFatalKind.DeadCall,
            classifyBypassFatalKind(DEAD_CALL_USER_MESSAGE),
        )
    }

    @Test
    fun classifiesOtherFatals() {
        assertEquals(BypassFatalKind.WrapAuth, classifyBypassFatalKind("FATAL_AUTH неверный пароль"))
        assertEquals(BypassFatalKind.Captcha, classifyBypassFatalKind("CAPTCHA_WAIT_REQUIRED"))
        assertEquals(BypassFatalKind.DialFailed, classifyBypassFatalKind("all vk credentials failed"))
        assertNull(classifyBypassFatalKind("[СТАТИСТИКА] Активных: 3"))
        assertNull(
            classifyBypassFatalKind(
                "Обход недоступен: нет активных каналов. Проверьте код звонка и сеть.",
            ),
        )
        assertNull(classifyBypassFatalKind("error_code=100 anonym token outdated"))
        assertEquals(
            BypassFatalKind.DeadCall,
            classifyBypassFatalKind("legacy getAnonymousToken error_code=9001"),
        )
        assertTrue(isDeadCallMessage(DEAD_CALL_USER_MESSAGE))
        assertFalse(isDeadCallMessage("Нет активных TURN-воркеров — переподключаем обход"))
    }

    @Test
    fun silentRecreateNeedsLiveVkSession() {
        assertEquals(
            DeadCallAction.SilentRecreate,
            decideDeadCallAction(silentRecreate = true, hasVkSession = true, recreateAttempts = 0),
        )
        assertEquals(
            DeadCallAction.NeedVkLogin,
            decideDeadCallAction(silentRecreate = true, hasVkSession = false, recreateAttempts = 0),
        )
    }

    @Test
    fun askUnlessSilent() {
        assertEquals(
            DeadCallAction.AskUser,
            decideDeadCallAction(silentRecreate = false, hasVkSession = true, recreateAttempts = 0),
        )
        assertEquals(
            DeadCallAction.NeedVkLogin,
            decideDeadCallAction(silentRecreate = false, hasVkSession = false, recreateAttempts = 0),
        )
    }

    @Test
    fun oneSilentAttemptThenGiveUp() {
        assertEquals(
            DeadCallAction.GiveUp,
            decideDeadCallAction(silentRecreate = true, hasVkSession = true, recreateAttempts = 1),
        )
    }
}
