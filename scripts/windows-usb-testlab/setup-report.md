# Отчёт готовности лаборатории ARDTT (15.09.2026)

Среда выполнения этого отчёта: **Cursor Cloud, Ubuntu 24.04 x86_64**, не Windows 11 и не USB-телефон пользователя. Промпт это прямо требует: не притворяться, что настраивается целевой ПК.

Целевой код: PR **#217** `auto/auto-whitelist-evidence-f6dc`, SHA `a325b83e11ddf8c7bd42490a88db465b34075538` (открыт, draft, Preview APK CI зелёный).

## Таблица приёмки

| Проверка | Статус | Доказательство / причина |
|---|---|---|
| Windows-инструменты и пути | BLOCKED | Нет Windows (`uname` Linux). Скрипты `Setup-ARDTT-TestLab.ps1` / `Doctor-ARDTT.ps1` готовы для локального ПК. |
| WSL2 и Linux toolchain | PASS (Linux host) | JDK 21.0.10, Go 1.25.14, SDK 35, NDK 27.0.12077973 linux-x86_64, CMake 3.22.1, cmdline-tools 22.0. Хеш Go 1.27.0 для libwg-go совпал с go.dev. См. `evidence/cloud-linux-doctor.json`. |
| Точный checkout | PASS | `git rev-parse HEAD` = `a325b83e11ddf8c7bd42490a88db465b34075538`, совпадает с head PR217. |
| Нативные библиотеки и APK | PASS (debug, эта VM) | `assembleDebug -PtargetAbis=arm64-v8a`, в APK есть `libclient.so` и `libwg-go.so`. SHA-256 `7a5447f34f62c8dd3ec48f0fa645ba837fca48f97ada8059e4fb3089e8c9916b`. Подпись **Android Debug**. |
| Preview CI APK (release) | PASS (скачан) | Run [34856621087](https://github.com/2kristalls36-hue/ARDTT/actions/runs/34856621087), artifact `ardtt-0.5.264-51cf7ce-arm64-v8a` — merge commit, не чистый head. SHA-256 `c6e9a361d1f12a6d943c9babbfdefdb37827f95c9d18a07a1e76251785ea20fd`, cert `e07400a728…`. |
| USB и выбранный телефон | BLOCKED | Нет устройства `adb devices` в состоянии `device`. |
| Управление/снимок экрана | BLOCKED | Нет USB; PNG-путь проверен на mock-adb (`tests/test-capture-state.sh`). |
| Установка нужного APK | BLOCKED | Нет телефона. Debug и CI release подписаны **разными** сертификатами; `Install-ARDTT.ps1` не будет uninstall. |
| Сбор и остановка журналов | PASS (mock) | `tests/test-session.sh`: start/mark/stop убивает только свои PID. |
| Короткий smoke test S00 | BLOCKED | Нужен USB. |
| Сетевые сценарии S01–S12 | PENDING | Каталог в `scenarios.json`; автопрогон без живой сети/профиля запрещён. |
| Восстановление настроек | NOT NEEDED | Doze на этой VM не включался. Скрипты Restore/Doze есть, finally вызывает restore. |
| Юнит-тесты лаборатории | PASS | `bash scripts/windows-usb-testlab/tests/run.sh` |

Готовность «диагностировать установленный APK на телефоне»: **нет** (нет USB).  
Готовность «локально пересобрать выбранный revision на Linux x86_64»: **да** (на этой VM). Совместимость подписи с телефоном пользователя **не доказана**.

## Команды после появления Windows+USB

См. [README-Windows-USB.md](README-Windows-USB.md) и [LOCAL-WINDOWS-AGENT.md](LOCAL-WINDOWS-AGENT.md).

```text
scripts/windows-usb-testlab/Setup-ARDTT-TestLab.ps1
scripts/windows-usb-testlab/Doctor-ARDTT.ps1
scripts/windows-usb-testlab/Start-ARDTT-Diagnostics.ps1 -Scenario S00
scripts/windows-usb-testlab/Mark-ARDTT-Event.ps1 -Note "..."
scripts/windows-usb-testlab/Capture-ARDTT-State.ps1
scripts/windows-usb-testlab/Stop-ARDTT-Diagnostics.ps1
scripts/windows-usb-testlab/Restore-ARDTT-TestState.ps1
```

APK этой сессии **не** в git (80 МБ debug). На VM: `~/ardtt-testlab/apk/app-arm64-v8a-debug.apk`. Для телефона с release-подписью используйте Preview APK из CI или локальный `assembleRelease` с keystore пользователя (keystore с VPS лаборатория не скачивает).
