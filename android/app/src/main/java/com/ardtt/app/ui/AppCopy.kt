package com.ardtt.app.ui

import com.ardtt.app.core.ConnPathMode

/**
 * User-facing strings shared by more than one screen.
 *
 * Convention for the whole UI layer: one `internal object <Domain>Copy` per
 * domain, `const val` in UPPER_SNAKE for fixed strings, functions for strings
 * that depend on state. Feature-only wording stays in that feature's package
 * (for example `ui.tunnel.TunnelStatusCopy`).
 */

/** Chip labels and help for Авто / Прямое / Обход (Settings + Tunnel). */
internal object PathModeCopy {
    const val AUTO = "Авто"
    const val DIRECT = "Прямое"
    const val BYPASS = "Обход"

    fun help(pathMode: String, hasCallHash: Boolean, compact: Boolean): String =
        when (ConnPathMode.fromSetting(pathMode)) {
            ConnPathMode.Direct -> if (compact) {
                "Только прямое подключение."
            } else {
                "Используется только прямое подключение."
            }
            ConnPathMode.Bypass -> when {
                hasCallHash && compact -> "Только обход. Код звонка — в настройках."
                hasCallHash -> "Используется только обход. Требуется код звонка."
                compact -> "Код звонка не задан. Нажмите «Обход», чтобы открыть карточку."
                else -> "Код звонка не задан. Нажмите «Обход», чтобы перейти к карточке метода обхода."
            }
            ConnPathMode.Auto -> if (compact) {
                "На Wi‑Fi — прямое. В мобильной сети при белом списке — обход."
            } else {
                "В Wi‑Fi всегда прямое подключение. В мобильной сети при белом списке оператора — обход, иначе прямое."
            }
        }
}

/** Hide-IP / egress labels (no WARP / «Прямой»). */
internal object HideIpCopy {
    const val SERVER_CHIP = "Мой IP"
    const val HIDDEN_CHIP = "Инкогнито"
    const val STATUS_HIDDEN = "Инкогнито"
    const val SOFT_HIDDEN = "Исходящий адрес скрыт."

    fun subtitle(hidden: Boolean): String =
        if (hidden) SOFT_HIDDEN else "Выход с адреса сервера."
}

/** Bypass dial strategy chips. */
internal object DialPathCopy {
    const val AUTO = "Авто"
    const val VK_CALLS = "vkcalls"
    const val LEGACY = "Капча"
}

/** Appearance chips. Values match `AppSettingsRepository.normalizeThemeMode`. */
internal object ThemeModeCopy {
    const val SYSTEM = "Система"
    const val LIGHT = "Светлая"
    const val DARK = "Тёмная"

    val OPTIONS: List<Pair<String, String>> = listOf(
        "system" to SYSTEM,
        "light" to LIGHT,
        "dark" to DARK,
    )
}
