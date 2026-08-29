package com.nonamevpn.app.ui

/** User-facing Hide-IP / egress labels (no WARP / «Прямой»). */
internal object HideIpCopy {
    const val SERVER_CHIP = "Адрес сервера"
    const val HIDDEN_CHIP = "Скрытый адрес"
    const val STATUS_HIDDEN = "Скрытый адрес"
    const val SOFT_HIDDEN = "Исходящий адрес скрыт."

    fun subtitle(hidden: Boolean): String =
        if (hidden) SOFT_HIDDEN else "Выход с адреса сервера."
}
