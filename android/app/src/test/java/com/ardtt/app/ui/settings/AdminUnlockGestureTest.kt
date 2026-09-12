package com.ardtt.app.ui.settings

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminUnlockGestureTest {
    @Test
    fun commitsOnlyNearTheEnd() {
        assertFalse(AdminUnlockGesture.shouldCommit(0f))
        assertFalse(AdminUnlockGesture.shouldCommit(0.5f))
        assertFalse(AdminUnlockGesture.shouldCommit(0.87f))
        assertTrue(AdminUnlockGesture.shouldCommit(0.88f))
        assertTrue(AdminUnlockGesture.shouldCommit(1f))
    }

    @Test
    fun incompleteHintIgnoresTaps() {
        assertFalse(AdminUnlockGesture.shouldHintIncomplete(0f))
        assertFalse(AdminUnlockGesture.shouldHintIncomplete(0.05f))
        assertTrue(AdminUnlockGesture.shouldHintIncomplete(0.2f))
        assertFalse(AdminUnlockGesture.shouldHintIncomplete(0.9f))
    }

    @Test
    fun keyboardCommitsOnlyOnConfirmKeys() {
        assertTrue(AdminUnlockGesture.keyCommits(Key.Enter))
        assertTrue(AdminUnlockGesture.keyCommits(Key.NumPadEnter))
        assertTrue(AdminUnlockGesture.keyCommits(Key.DirectionCenter))
        assertFalse(AdminUnlockGesture.keyCommits(Key.DirectionRight))
        assertFalse(AdminUnlockGesture.keyCommits(Key.Spacebar))
        assertFalse(AdminUnlockGesture.keyCommits(Key.Tab))
    }
}
