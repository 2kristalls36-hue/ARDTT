package com.nonamevpn.app.ui

/**
 * Quick Settings profile tile: tap cycles the next saved profile,
 * long-press (tile preferences) opens a picker.
 */
internal enum class QsProfileClickAction {
    Locked,
    OpenProfiles,
    Cycle,
}

internal fun qsProfileClickAction(profileCount: Int, sessionLocked: Boolean): QsProfileClickAction = when {
    sessionLocked -> QsProfileClickAction.Locked
    profileCount >= 2 -> QsProfileClickAction.Cycle
    else -> QsProfileClickAction.OpenProfiles
}

internal fun qsProfileCanPick(profileCount: Int, sessionLocked: Boolean): Boolean =
    !sessionLocked && profileCount >= 2

internal fun qsProfileTileLabel(activeName: String?): String {
    val name = activeName?.trim().orEmpty()
    return name.ifBlank { "Профиль" }
}

internal fun qsProfileTileSubtitle(profileCount: Int, sessionLocked: Boolean): String = when {
    sessionLocked -> "Сессия"
    profileCount <= 0 -> "Нет профилей"
    profileCount == 1 -> "Активен"
    else -> "Сменить"
}

internal fun qsToggleTileSubtitle(
    hasCallHash: Boolean,
    running: Boolean,
    profileName: String?,
): String {
    if (qsTileOpensCallHashSettings(hasCallHash, running)) return "Код звонка"
    val name = profileName?.trim().orEmpty()
    if (name.isNotEmpty()) return name
    return if (running) "Подключено" else "Отключено"
}

internal fun qsProfilePickerLabels(names: List<String>): Array<String> =
    names.map { it.trim().ifBlank { "Профиль" } }.toTypedArray()

internal fun qsProfilePickerCheckedIndex(ids: List<String>, activeId: String?): Int {
    val index = ids.indexOfFirst { it == activeId }
    return if (index >= 0) index else 0
}

internal fun nextProfileId(ids: List<String>, activeId: String?): String? {
    if (ids.size <= 1) return null
    val current = ids.indexOfFirst { it == activeId }.let { if (it < 0) 0 else it }
    return ids[(current + 1) % ids.size]
}
