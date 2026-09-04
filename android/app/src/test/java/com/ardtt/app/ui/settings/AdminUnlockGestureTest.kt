package com.ardtt.app.ui.settings

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
}
