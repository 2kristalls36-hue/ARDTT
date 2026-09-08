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

    fun shouldFollow(
        followEnabled: Boolean,
        atEnd: Boolean,
    ): Boolean = followEnabled && atEnd

    fun unseenCount(
        lastSeenId: Int,
        visible: List<AppLog.Entry>,
    ): Int = visible.count { it.id > lastSeenId }
}
