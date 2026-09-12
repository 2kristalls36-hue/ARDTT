package com.ardtt.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryWakeLockTest {
    private class Recorder {
        val acquired = mutableListOf<Long>()
        var releases = 0
        val gate = RecoveryWakeLockGate(
            onAcquire = { acquired += it },
            onRelease = { releases += 1 },
        )
    }

    @Test
    fun holdCoversTheTimerPlusSlackOrIsNotTakenAtAll() {
        assertEquals(
            2_000L + RECOVERY_WAKELOCK_SLACK_MS,
            recoveryWakeLockTimeoutMs(2_000L),
        )
        assertEquals(RECOVERY_WAKELOCK_SLACK_MS, recoveryWakeLockTimeoutMs(0L))
        assertEquals(RECOVERY_WAKELOCK_SLACK_MS, recoveryWakeLockTimeoutMs(-5L))
        assertEquals(
            RECOVERY_WAKELOCK_MAX_MS,
            recoveryWakeLockTimeoutMs(RECOVERY_WAKELOCK_MAX_MS - RECOVERY_WAKELOCK_SLACK_MS),
        )
        assertNull(recoveryWakeLockTimeoutMs(RECOVERY_WAKELOCK_MAX_MS))
        assertNull(recoveryWakeLockTimeoutMs(RecoverySettings.directReevalDelayMs(4)))
        assertNull(recoveryWakeLockTimeoutMs(RecoverySettings.WIFI_UPGRADE_SETTLE_MAX_MS * 3))
    }

    @Test
    fun aTimerLongerThanTheCeilingTakesNoHold() {
        val r = Recorder()
        val token = r.gate.acquire(RecoverySettings.directReevalDelayMs(4))
        assertFalse(r.gate.isHeld)
        assertTrue(r.acquired.isEmpty())
        r.gate.release(token)
        assertEquals(0, r.releases)
    }

    @Test
    fun anUncoverableTimerDropsTheHoldItReplaces() {
        val r = Recorder()
        val short = r.gate.acquire(2_000L)
        r.gate.acquire(RecoverySettings.directReevalDelayMs(4))
        assertFalse(r.gate.isHeld)
        assertEquals(1, r.releases)
        r.gate.release(short)
        assertEquals(1, r.releases)
    }

    @Test
    fun reArmingFromInsideTheCallbackKeepsTheSuccessorHold() {
        val r = Recorder()
        val first = r.gate.acquire(2_000L)
        // Timer re-arms from its own callback before the predecessor's finally runs.
        val second = r.gate.acquire(5_000L)
        r.gate.release(first)
        assertTrue(r.gate.isHeld)
        assertEquals(0, r.releases)
        r.gate.release(second)
        assertFalse(r.gate.isHeld)
        assertEquals(1, r.releases)
        assertEquals(
            listOf(2_000L + RECOVERY_WAKELOCK_SLACK_MS, 5_000L + RECOVERY_WAKELOCK_SLACK_MS),
            r.acquired,
        )
    }

    @Test
    fun releaseIsIdempotent() {
        val r = Recorder()
        val token = r.gate.acquire(1_000L)
        r.gate.release(token)
        r.gate.release(token)
        r.gate.releaseNow()
        assertEquals(1, r.releases)
    }

    @Test
    fun releaseNowDropsAnUnfinishedHold() {
        val r = Recorder()
        r.gate.acquire(60_000L)
        r.gate.releaseNow()
        assertFalse(r.gate.isHeld)
        assertEquals(1, r.releases)
    }
}
