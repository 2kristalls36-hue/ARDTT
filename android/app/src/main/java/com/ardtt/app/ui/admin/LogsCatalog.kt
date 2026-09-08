package com.ardtt.app.ui.admin

import com.ardtt.app.core.AppLog

object LogsCatalog {
    fun matches(entry: AppLog.Entry, query: String, level: AppLog.Level?): Boolean {
        if (level != null && entry.level != level) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        return entry.tag.contains(q, ignoreCase = true) ||
            entry.message.contains(q, ignoreCase = true)
    }

    fun visible(
        entries: List<AppLog.Entry>,
        query: String,
        level: AppLog.Level?,
    ): List<AppLog.Entry> = entries.filter { matches(it, query, level) }

    /** New lines never turn follow off; only a user gesture does. */
    fun shouldFollow(followEnabled: Boolean): Boolean = followEnabled

    fun followAfterUserGesture(
        currentlyFollowing: Boolean,
        atEnd: Boolean,
        userScrollingAwayFromEnd: Boolean,
    ): Boolean {
        if (userScrollingAwayFromEnd) return false
        if (atEnd) return true
        return currentlyFollowing
    }

    fun unseenCount(
        lastSeenId: Int,
        visible: List<AppLog.Entry>,
    ): Int = visible.count { it.id > lastSeenId }

    fun levelFilterLabel(level: AppLog.Level?): String = when (level) {
        null -> "Уровень: все"
        AppLog.Level.I -> "Уровень: информация"
        AppLog.Level.W -> "Уровень: предупреждения"
        AppLog.Level.E -> "Уровень: ошибки"
    }

    fun levelName(level: AppLog.Level): String = when (level) {
        AppLog.Level.I -> "Информация"
        AppLog.Level.W -> "Предупреждения"
        AppLog.Level.E -> "Ошибки"
    }

    fun levelShort(level: AppLog.Level): String = when (level) {
        AppLog.Level.I -> "ИНФО"
        AppLog.Level.W -> "ПРЕД"
        AppLog.Level.E -> "ОШБ"
    }
}
