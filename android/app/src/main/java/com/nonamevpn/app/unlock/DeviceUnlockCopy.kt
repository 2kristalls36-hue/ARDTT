package com.nonamevpn.app.unlock

/**
 * User-visible copy for the first-launch device confirmation screen.
 * Keep wording formal and short so it fits the layout.
 */
object DeviceUnlockCopy {
    const val SUBTITLE = "Подтверждение доступа"

    const val INTRO =
        "Подтвердите доступ на этом устройстве."

    const val STEPS =
        "Скопируйте код устройства и введите полученный шестизначный код. " +
            "Сеть не требуется. Повторно — только после удаления приложения."

    const val DEVICE_CODE_TITLE = "Код устройства"
    const val COPY_CODE = "Копировать"
    const val CODE_COPIED = "Код скопирован"

    const val CONFIRMATION_TITLE = "Код подтверждения"
    const val CONFIRMATION_PLACEHOLDER = "Шесть цифр"
    const val CONFIRM = "Подтвердить"

    const val FOOTNOTE = "На другом устройстве код будет другим."

    const val WRONG_CODE = "Код указан неверно"
    const val LOCKED_PREFIX = "Слишком много попыток. Повтор через "
    const val WRONG_AND_WAIT_PREFIX = "Неверный код. Повтор через "
    const val CONNECT_BLOCKED = "Подтвердите доступ к приложению"

    fun wrongCode(lockMs: Long): String =
        if (lockMs > 0L) {
            WRONG_AND_WAIT_PREFIX + AlphaGate.formatLockRemaining(lockMs)
        } else {
            WRONG_CODE
        }

    fun locked(remainingMs: Long): String =
        LOCKED_PREFIX + AlphaGate.formatLockRemaining(remainingMs)

    val userFacingStrings: List<String> = listOf(
        SUBTITLE,
        INTRO,
        STEPS,
        DEVICE_CODE_TITLE,
        COPY_CODE,
        CODE_COPIED,
        CONFIRMATION_TITLE,
        CONFIRMATION_PLACEHOLDER,
        CONFIRM,
        FOOTNOTE,
        WRONG_CODE,
        LOCKED_PREFIX,
        WRONG_AND_WAIT_PREFIX,
        CONNECT_BLOCKED,
    )
}
