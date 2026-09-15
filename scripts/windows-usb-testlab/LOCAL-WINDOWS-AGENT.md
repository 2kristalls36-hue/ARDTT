# Как продолжить на Windows 11 (локальный Cursor Agent)

Этот промпт пришёл **облачному** агенту на Linux. Он не может:

- поставить winget/scrcpy/драйвер на ваш ПК;
- увидеть телефон по USB;
- подтвердить RSA / VPN / рабочий профиль.

Он подготовил скрипты в `scripts/windows-usb-testlab/` и проверил Linux-сборку/тесты ядра.

## Что сделать на целевом ПК

1. Откройте **локальный** Cursor Agent Mode (не Cloud) в checkout ARDTT.
2. Обновите ветку с лабораторией (PR к `auto/auto-whitelist-evidence-f6dc`) либо cherry-pick каталог `scripts/windows-usb-testlab`.
3. Вставьте **целиком** файл `scripts/windows-usb-testlab/HANDOFF-LOCAL-AGENT.md` (объём уже сделанной облачной работы + что делать на ПК).
4. При необходимости приложите исходный длинный промпт `ARDTT_Grok_4_6_Windows11_USB_Test_Environment.md`. Не просите агента заново писать скрипты лаборатории.
5. Нужны: терминал Windows, интернет, телефон с USB debugging.
6. APK: сначала `LOCAL-NEXT-INSTALL.md`. Не ставить 51cf7ce / 0.5.264 повторно.

Ручное участие по-прежнему нужно только для UAC, reboot, RSA, VPN-диалога и паролей.
