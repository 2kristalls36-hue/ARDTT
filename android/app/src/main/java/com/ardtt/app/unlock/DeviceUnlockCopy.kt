package com.ardtt.app.unlock

/**
 * User-visible copy for the first-launch device confirmation screen.
 * Formal, no product jargon (VPN / tunnel).
 */
object DeviceUnlockCopy {
    const val SUBTITLE = "Подтверждение доступа"

    const val INTRO =
        "Приложение доступно в рамках закрытого бета-тестирования. " +
            "Пароль вводится только один раз — повторно вводить его в будущем не нужно."

    const val STEPS =
        "Скопируйте код устройства и отправьте его автору приложения. " +
            "После проверки вы получите шестизначный код доступа — введите его в поле выше."

    const val DEVICE_CODE_TITLE = "Код устройства"
    const val COPY_CODE = "Копировать"
    const val CODE_COPIED = "Код скопирован"

    const val CONFIRMATION_TITLE = "Код подтверждения"
    const val CONFIRMATION_PLACEHOLDER = "Шесть цифр"
    const val CONFIRM = "Подтвердить"

    const val FOOTNOTE =
        "Код устройства действует только на этом телефоне: на другом устройстве он будет другим."

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
