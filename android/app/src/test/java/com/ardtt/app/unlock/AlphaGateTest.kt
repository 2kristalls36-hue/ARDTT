package com.ardtt.app.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaGateTest {
    @Test
    fun normalizesGroupedAndUppercaseHex() {
        assertEquals(
            "a1b2c3d4e5f60718",
            AlphaGate.normalizeChallenge("A1B2-C3D4-E5F6-0718"),
        )
        assertEquals(
            "0123456789abcdef",
            AlphaGate.normalizeChallenge("  0123456789ABCDEF\n"),
        )
        assertNull(AlphaGate.normalizeChallenge("short"))
        assertNull(AlphaGate.normalizeChallenge("0123456789abcdeg"))
        assertNull(AlphaGate.normalizeChallenge("0123456789abcdef00"))
    }

    @Test
    fun formatsDisplayInGroups() {
        assertEquals(
            "0123-4567-89AB-CDEF",
            AlphaGate.formatDisplay("0123456789abcdef"),
        )
    }

    @Test
    fun otpMatchesPythonFixtures() {
        assertEquals("302184", AlphaGate.oneTimeCode("0123456789abcdef"))
        assertEquals("881716", AlphaGate.oneTimeCode("A1B2-C3D4-E5F6-0718"))
        assertTrue(AlphaGate.otpMatches("0123456789abcdef", "302184"))
        assertTrue(AlphaGate.otpMatches("0123456789abcdef", " 302-184 "))
        assertFalse(AlphaGate.otpMatches("0123456789abcdef", "000000"))
        assertFalse(AlphaGate.otpMatches("0123456789abcdef", "30218"))
        assertFalse(AlphaGate.otpMatches("not-hex", "302184"))
    }

    @Test
    fun differentChallengesYieldDifferentCodes() {
        assertNotEquals(
            AlphaGate.oneTimeCode("0123456789abcdef"),
            AlphaGate.oneTimeCode("0123456789abcdee"),
        )
    }

    @Test
    fun newChallengeIsSixteenHexAndStableNormalize() {
        val hex = AlphaGate.newChallengeHex()
        assertEquals(16, hex.length)
        assertEquals(hex, AlphaGate.normalizeChallenge(hex))
        assertEquals(6, AlphaGate.oneTimeCode(hex).length)
    }

    @Test
    fun otpDigitsFromClipboardExtractsCode() {
        assertEquals("302184", AlphaGate.otpDigitsFromClipboard("302184"))
        assertEquals("302184", AlphaGate.otpDigitsFromClipboard(" 302-184 "))
        assertEquals("302184", AlphaGate.otpDigitsFromClipboard("код: 302184."))
        assertEquals("30218", AlphaGate.otpDigitsFromClipboard("30218"))
        assertEquals("123456", AlphaGate.otpDigitsFromClipboard("123456789"))
        assertEquals("", AlphaGate.otpDigitsFromClipboard(null))
        assertEquals("", AlphaGate.otpDigitsFromClipboard("нет цифр"))
    }

    @Test
    fun lockoutSchedule() {
        assertEquals(0L, AlphaGate.lockMsAfterFails(1))
        assertEquals(0L, AlphaGate.lockMsAfterFails(4))
        assertEquals(30_000L, AlphaGate.lockMsAfterFails(5))
        assertEquals(120_000L, AlphaGate.lockMsAfterFails(8))
        assertEquals(900_000L, AlphaGate.lockMsAfterFails(10))
        assertEquals("30 с", AlphaGate.formatLockRemaining(30_000L))
        assertEquals("2 мин", AlphaGate.formatLockRemaining(90_000L))
    }
}
