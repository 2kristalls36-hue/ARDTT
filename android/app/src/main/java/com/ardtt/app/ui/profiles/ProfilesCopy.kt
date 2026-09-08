package com.ardtt.app.ui.profiles

internal fun profilesCountSubtitle(
    count: Int,
    countLabel: String,
    activeName: String?,
    locked: Boolean,
    emptyText: String = "Импортируйте JSON с сервера",
    lockedText: String = "Соединение активно · смена профиля недоступна",
): String = when {
    count <= 0 -> emptyText
    locked -> lockedText
    else -> "$countLabel · активен: ${activeName ?: "—"}"
}
