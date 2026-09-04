package com.ardtt.app.deploy

/**
 * Shade copy for the deploy foreground service. Kept free of Android types
 * so titles / progress stay unit-testable.
 */
object DeployShade {
    const val PROGRESS_MAX = 100

    fun title(isUpdate: Boolean, isUninstall: Boolean = false): String = when {
        isUninstall -> "Удаление деплоя"
        isUpdate -> "Обновление деплоя"
        else -> "Установка деплоя"
    }

    fun progressPercent(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * PROGRESS_MAX).toInt()

    fun contentText(step: String, hostLabel: String, isUninstall: Boolean = false): String {
        val trimmed = step.trim()
        return trimmed.ifBlank { hostLabel }.ifBlank {
            if (isUninstall) "Идёт удаление…" else "Идёт установка…"
        }
    }

    fun finishedText(success: Boolean, message: String): String {
        val trimmed = message.trim()
        return when {
            trimmed.isNotBlank() -> trimmed
            success -> "Готово"
            else -> "Ошибка"
        }
    }
}
