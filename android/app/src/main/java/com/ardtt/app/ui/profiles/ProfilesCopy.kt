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

/** Fixed strings of the Profiles tab. */
internal object ProfilesCopy {
    const val EMPTY_TITLE = "Профилей пока нет"
    const val EMPTY_BODY = "Импортируйте JSON пользователя или создайте клиента на вкладке «Серверы»."
    const val EMPTY_ACTION = "Добавить профиль"
    const val DELETE_TITLE = "Удалить профиль?"

    fun deleteBody(name: String, active: Boolean): String = buildString {
        append("«").append(name.ifBlank { "Профиль" }).append("» будет удалён с устройства.")
        if (active) append(" Это активный профиль: туннель переключится на следующий сохранённый или останется без профиля.")
        append(" Восстановить можно только повторным импортом.")
    }
}
