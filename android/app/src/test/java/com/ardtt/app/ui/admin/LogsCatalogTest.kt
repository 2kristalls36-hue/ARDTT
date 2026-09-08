package com.ardtt.app.ui.admin

import com.ardtt.app.core.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsCatalogTest {
    private fun entry(id: Int, level: AppLog.Level, tag: String, message: String): AppLog.Entry =
        AppLog.Entry(id = id, timeMs = 0L, tag = tag, message = message, level = level)

    @Test
    fun searchAndLevelFilterAreIndependent() {
        val rows = listOf(
            entry(1, AppLog.Level.I, "Tunnel", "connected"),
            entry(2, AppLog.Level.E, "Tunnel", "handshake failed"),
            entry(3, AppLog.Level.W, "Deploy", "slow"),
        )
        assertEquals(2, LogsCatalog.visible(rows, "Tunnel", null).size)
        assertEquals(1, LogsCatalog.visible(rows, "Tunnel", AppLog.Level.E).size)
        assertTrue(LogsCatalog.visible(rows, "missing", null).isEmpty())
    }

    @Test
    fun newLinesDoNotDisableFollow() {
        assertTrue(LogsCatalog.shouldFollow(followEnabled = true))
        assertFalse(LogsCatalog.shouldFollow(followEnabled = false))
        assertTrue(
            LogsCatalog.followAfterUserGesture(
                currentlyFollowing = true,
                atEnd = false,
                userScrollingAwayFromEnd = false,
            ),
        )
        assertFalse(
            LogsCatalog.followAfterUserGesture(
                currentlyFollowing = true,
                atEnd = false,
                userScrollingAwayFromEnd = true,
            ),
        )
        assertTrue(
            LogsCatalog.followAfterUserGesture(
                currentlyFollowing = false,
                atEnd = true,
                userScrollingAwayFromEnd = false,
            ),
        )
    }

    @Test
    fun levelLabelsAreFullWords() {
        assertEquals("Уровень: все", LogsCatalog.levelFilterLabel(null))
        assertEquals("Информация", LogsCatalog.levelName(AppLog.Level.I))
        assertEquals("Предупреждения", LogsCatalog.levelName(AppLog.Level.W))
        assertEquals("Ошибки", LogsCatalog.levelName(AppLog.Level.E))
    }

    @Test
    fun unseenCountUsesIdsAcrossAFullRing() {
        val visible = listOf(
            entry(98, AppLog.Level.I, "A", "old"),
            entry(99, AppLog.Level.I, "A", "mid"),
            entry(100, AppLog.Level.I, "A", "new"),
        )
        assertEquals(2, LogsCatalog.unseenCount(lastSeenId = 98, visible = visible))
        assertEquals(0, LogsCatalog.unseenCount(lastSeenId = 100, visible = visible))
    }
}
