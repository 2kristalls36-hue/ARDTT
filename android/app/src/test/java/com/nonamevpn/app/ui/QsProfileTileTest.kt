package com.nonamevpn.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QsProfileTileTest {
    @Test
    fun clickCyclesWhenTwoOrMoreProfilesAndIdle() {
        assertEquals(
            QsProfileClickAction.Cycle,
            qsProfileClickAction(profileCount = 2, sessionLocked = false),
        )
        assertEquals(
            QsProfileClickAction.Cycle,
            qsProfileClickAction(profileCount = 5, sessionLocked = false),
        )
    }

    @Test
    fun clickOpensAppWhenFewerThanTwoProfiles() {
        assertEquals(
            QsProfileClickAction.OpenProfiles,
            qsProfileClickAction(profileCount = 0, sessionLocked = false),
        )
        assertEquals(
            QsProfileClickAction.OpenProfiles,
            qsProfileClickAction(profileCount = 1, sessionLocked = false),
        )
    }

    @Test
    fun clickLockedDuringVpnSession() {
        assertEquals(
            QsProfileClickAction.Locked,
            qsProfileClickAction(profileCount = 3, sessionLocked = true),
        )
        assertFalse(qsProfileCanPick(profileCount = 3, sessionLocked = true))
        assertTrue(qsProfileCanPick(profileCount = 3, sessionLocked = false))
        assertFalse(qsProfileCanPick(profileCount = 1, sessionLocked = false))
    }

    @Test
    fun nextProfileWrapsAround() {
        assertEquals("b", nextProfileId(listOf("a", "b", "c"), "a"))
        assertEquals("c", nextProfileId(listOf("a", "b", "c"), "b"))
        assertEquals("a", nextProfileId(listOf("a", "b", "c"), "c"))
        assertEquals("b", nextProfileId(listOf("a", "b"), "a"))
        assertNull(nextProfileId(listOf("a"), "a"))
        assertNull(nextProfileId(emptyList(), null))
        assertEquals("b", nextProfileId(listOf("a", "b"), "missing"))
    }

    @Test
    fun tileCopy() {
        assertEquals("Дом", qsProfileTileLabel("Дом"))
        assertEquals("Профиль", qsProfileTileLabel("  "))
        assertEquals("Сессия", qsProfileTileSubtitle(3, sessionLocked = true))
        assertEquals("Нет профилей", qsProfileTileSubtitle(0, sessionLocked = false))
        assertEquals("Активен", qsProfileTileSubtitle(1, sessionLocked = false))
        assertEquals("Сменить", qsProfileTileSubtitle(2, sessionLocked = false))
    }

    @Test
    fun toggleSubtitlePrefersProfileName() {
        assertEquals("Код звонка", qsToggleTileSubtitle(hasCallHash = false, running = false, profileName = "Дом"))
        assertEquals("Дом", qsToggleTileSubtitle(hasCallHash = true, running = true, profileName = "Дом"))
        assertEquals("Дом", qsToggleTileSubtitle(hasCallHash = true, running = false, profileName = " Дом "))
        assertEquals("Подключено", qsToggleTileSubtitle(hasCallHash = true, running = true, profileName = null))
        assertEquals("Отключено", qsToggleTileSubtitle(hasCallHash = true, running = false, profileName = "  "))
    }

    @Test
    fun pickerLabelsAndCheckedIndex() {
        assertArrayEquals(
            arrayOf("Дом", "Профиль", "Офис"),
            qsProfilePickerLabels(listOf("Дом", "  ", "Офис")),
        )
        assertEquals(1, qsProfilePickerCheckedIndex(listOf("a", "b", "c"), "b"))
        assertEquals(0, qsProfilePickerCheckedIndex(listOf("a", "b"), "missing"))
        assertEquals(0, qsProfilePickerCheckedIndex(emptyList(), null))
    }
}
