package com.ardtt.app.ui.admin

import com.ardtt.app.core.AppLog
import com.ardtt.app.ui.components.control.ArdttButtonSize
import com.ardtt.app.ui.components.control.ArdttButtonVariant
import com.ardtt.app.ui.components.control.ardttButtonMinHeight
import com.ardtt.app.ui.components.control.ardttCompactIconUsesExactMinSize
import com.ardtt.app.ui.theme.ArdttLayout
import com.ardtt.app.ui.theme.ArdttSize
import com.ardtt.app.ui.theme.ArdttSpacing
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

    @Test
    fun journalHeaderActionsAreCopyShareDelete() {
        assertEquals(
            listOf("Копировать", "Расшарить", "Удалить"),
            logsHeaderActionOrder(),
        )
        assertEquals(ArdttLayout.ControlSpacing, logsHeaderActionSpacing())
        assertTrue(logsHeaderActionSpacing() > ArdttSpacing.Hairline)
    }

    @Test
    fun journalActionsLiveInTheTerminalHeader() {
        assertEquals(LogsChromeActionAnchor.TerminalHeader, logsChromeActionAnchor())
        assertFalse(logsShowsInlineActionRow(embedded = true))
        assertFalse(logsShowsInlineActionRow(embedded = false))
        assertFalse(logsActionsInPageHeader(embedded = true))
        assertFalse(logsActionsInPageHeader(embedded = false))
    }

    @Test
    fun terminalHeaderStaysVisibleWithoutSession() {
        assertTrue(logsShowsTerminalHeaderWithoutSession())
        assertEquals(
            LogsChromeUptimePlacement.Start,
            logsChromeUptimePlacement(),
        )
        assertTrue(logsTerminalHeaderAlwaysShowsUptime())
    }

    @Test
    fun terminalHeaderMatchesOriginalSlimTimerStrip() {
        assertEquals(ArdttSize.IconLarge, logsTerminalHeaderIconSize())
        assertTrue(ardttCompactIconUsesExactMinSize())
        assertEquals(
            logsTerminalHeaderIconSize(),
            ardttButtonMinHeight(ArdttButtonVariant.Icon, ArdttButtonSize.Compact),
        )
        assertTrue(
            ardttButtonMinHeight(ArdttButtonVariant.Icon, ArdttButtonSize.Compact) <
                ArdttSize.TouchTarget,
        )
    }

    @Test
    fun journalPageTitleStaysWhileTheFeedScrolls() {
        assertTrue(logsPageTitleStaysWhileScrolling())
    }

    @Test
    fun journalUptimeSitsOnTheLeftFromSessionStartNotLogText() {
        assertEquals("00:00", LogsCopy.UPTIME_IDLE)
        assertEquals(
            "00:00",
            formatJournalUptime(sessionUp = false, startedAtMs = 9_000L, nowMs = 90_000L),
        )
        assertEquals(
            "00:00",
            formatJournalUptime(sessionUp = true, startedAtMs = 0L, nowMs = 90_000L),
        )
        assertEquals(
            "01:05",
            formatJournalUptime(sessionUp = true, startedAtMs = 1_000L, nowMs = 66_000L),
        )
        assertEquals(
            "1:02:03",
            formatJournalUptime(sessionUp = true, startedAtMs = 1_000L, nowMs = 3_724_000L),
        )
    }
}
