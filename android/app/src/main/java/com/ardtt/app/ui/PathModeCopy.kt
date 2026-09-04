package com.ardtt.app.ui

import com.ardtt.app.core.ConnPathMode

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
                "На Wi‑Fi — прямое. В мобильной сети сначала прямое, иначе обход."
            } else {
                "В Wi‑Fi всегда прямое подключение. В мобильной сети — прямое, при недоступности обход."
            }
        }
}
