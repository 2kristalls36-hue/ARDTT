package com.ardtt.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkSessionActionTest {

    @Test
    fun loggedOutShowsAuthorization() {
        val action = vkSessionAction(
            loggedIn = false,
            vpnActive = false,
            busy = false,
            hasProfile = true,
        )
        assertEquals("Авторизация", action.label)
        assertFalse(action.destructive)
        assertTrue(action.enabled)
    }

    @Test
    fun loggedInShowsEndSession() {
        val action = vkSessionAction(
            loggedIn = true,
            vpnActive = false,
            busy = false,
            hasProfile = true,
        )
        assertEquals("Завершить сессию", action.label)
        assertTrue(action.destructive)
        assertTrue(action.enabled)
    }

    @Test
    fun loginNeedsProfile() {
        val action = vkSessionAction(
            loggedIn = false,
            vpnActive = false,
            busy = false,
            hasProfile = false,
        )
        assertEquals("Авторизация", action.label)
        assertFalse(action.enabled)
    }

    @Test
    fun logoutDoesNotNeedProfile() {
        val action = vkSessionAction(
            loggedIn = true,
            vpnActive = false,
            busy = false,
            hasProfile = false,
        )
        assertEquals("Завершить сессию", action.label)
        assertTrue(action.enabled)
    }

    @Test
    fun vpnLocksBothStates() {
        val login = vkSessionAction(
            loggedIn = false,
            vpnActive = true,
            busy = false,
            hasProfile = true,
        )
        val logout = vkSessionAction(
            loggedIn = true,
            vpnActive = true,
            busy = false,
            hasProfile = true,
        )
        assertFalse(login.enabled)
        assertFalse(logout.enabled)
        assertEquals("Авторизация", login.label)
        assertEquals("Завершить сессию", logout.label)
    }

    @Test
    fun busyLocksBothStates() {
        val login = vkSessionAction(
            loggedIn = false,
            vpnActive = false,
            busy = true,
            hasProfile = true,
        )
        val logout = vkSessionAction(
            loggedIn = true,
            vpnActive = false,
            busy = true,
            hasProfile = true,
        )
        assertFalse(login.enabled)
        assertFalse(logout.enabled)
    }
}
