package com.ardtt.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BypassMethodOrderTest {
    @Test
    fun bypassMethodPutsSavedCodeAboveActionsAndLoginAboveEnd() {
        assertEquals(
            listOf(
                BypassMethodBlock.CodeNotice,
                BypassMethodBlock.CodeActions,
                BypassMethodBlock.LoginNotice,
                BypassMethodBlock.SessionAction,
            ),
            bypassMethodBlockOrder(),
        )
    }

    @Test
    fun codeNoticeIsTheSavedLineWhenAHashExists() {
        assertEquals(
            BypassMethodCopy.CODE_SAVED,
            bypassCodeNotice(
                vpnActive = false,
                transient = null,
                hasCallHash = true,
                hasProfile = true,
            ),
        )
        assertEquals(
            BypassMethodCopy.CODE_MISSING,
            bypassCodeNotice(
                vpnActive = false,
                transient = null,
                hasCallHash = false,
                hasProfile = true,
            ),
        )
        assertEquals(
            BypassMethodCopy.NEED_PROFILE,
            bypassCodeNotice(
                vpnActive = false,
                transient = null,
                hasCallHash = false,
                hasProfile = false,
            ),
        )
        assertEquals(
            BypassMethodCopy.VPN_LOCKED,
            bypassCodeNotice(
                vpnActive = true,
                transient = null,
                hasCallHash = true,
                hasProfile = true,
            ),
        )
        assertEquals(
            "Код звонка сохранён.",
            bypassCodeNotice(
                vpnActive = false,
                transient = "Код звонка сохранён.",
                hasCallHash = true,
                hasProfile = true,
            ),
        )
    }

    @Test
    fun loginNoticeSitsOnTheSignedInLine() {
        assertEquals(BypassMethodCopy.LOGIN_DONE, bypassLoginNotice(loggedIn = true, transient = null))
        assertNull(bypassLoginNotice(loggedIn = false, transient = null))
        assertEquals(
            "Сессия ВКонтакте завершена.",
            bypassLoginNotice(loggedIn = false, transient = "Сессия ВКонтакте завершена."),
        )
    }
}
