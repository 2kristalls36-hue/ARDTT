package com.nonamevpn.app.unlock

/**
 * User-visible copy for the first-launch device confirmation screen.
 * Keep wording formal; do not mention testing, alpha, leaks, or APK.
 */
object DeviceUnlockCopy {
    const val SUBTITLE = "Подтверждение доступа · однократно на этом устройстве"

    const val INTRO =
        "Для начала работы необходимо подтвердить доступ к приложению на данном устройстве."

    const val STEPS =
        "1. Скопируйте код устройства и направьте его для получения кода подтверждения.\n" +
            "2. Введите полученный шестизначный код в поле ниже.\n" +
            "3. Подключение к сети не требуется. После установки обновлений повторный ввод " +
            "не запрашивается, за исключением удаления приложения или очистки его данных."

    const val DEVICE_CODE_TITLE = "Код устройства"
    const val COPY_CODE = "Скопировать код"
    const val CODE_COPIED = "Код устройства скопирован"

    const val CONFIRMATION_TITLE = "Код подтверждения"
    const val CONFIRMATION_PLACEHOLDER = "Шесть цифр"
    const val CONFIRM = "Подтвердить"

    const val FOOTNOTE =
        "Код действует только на этом устройстве. На другом устройстве формируется отдельный код."

    const val WRONG_CODE = "Код указан неверно"
    const val LOCKED_PREFIX = "Превышено допустимое число попыток. Повторите ввод через "
    const val WRONG_AND_WAIT_PREFIX = "Код указан неверно. Повторите попытку через "
    const val CONNECT_BLOCKED = "Необходимо подтвердить доступ к приложению"

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
