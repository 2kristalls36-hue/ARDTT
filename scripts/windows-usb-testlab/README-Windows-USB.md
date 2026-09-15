# Лаборатория USB-тестов ARDTT (Windows 11 + Android)

Скрипты для диагностики VPN на **физическом** телефоне через Windows ADB. Алгоритмы Auto/БС/RAW они не меняют.

Облачный Linux-агент **не** ставит программы на ваш ПК и **не** видит USB. Эта папка — готовая лаборатория: на Windows её доводит локальный Cursor Agent / PowerShell.

Передача с облака: [HANDOFF-LOCAL-AGENT.md](HANDOFF-LOCAL-AGENT.md).

## Каталоги

| Где | Зачем |
|---|---|
| Репозиторий `scripts/windows-usb-testlab/` | Канонические скрипты (в git, без секретов) |
| `%LOCALAPPDATA%\ARDTT-TestLab` | Конфиг, APK, sessions, отчёты (не коммитить) |
| WSL `~/ardtt-testlab/src/ARDTT` | Отдельный Linux-checkout для сборки |
| WSL `~/Android/Sdk` | Linux SDK/NDK |

Не кладите в git `lab-config.json` с serial, журналы, APK, keystore.

## Команды (Windows PowerShell)

Из корня репозитория или после `cd scripts\windows-usb-testlab`:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
cd scripts\windows-usb-testlab
.\Setup-ARDTT-TestLab.ps1
.\Doctor-ARDTT.ps1
.\Fetch-PreviewApk.ps1
.\Install-ARDTT.ps1 -ApkPath "$env:LOCALAPPDATA\ARDTT-TestLab\apk\ardtt-0.5.265-3f14a8c-arm64-v8a.apk"
.\Start-ARDTT-Diagnostics.ps1 -Scenario S00
.\Mark-ARDTT-Event.ps1 -Note "wifi-on-no-ap"
.\Capture-ARDTT-State.ps1 -Label mid
.\Stop-ARDTT-Diagnostics.ps1
.\Restore-ARDTT-TestState.ps1
.\Test-ARDTT-Scenario.ps1 -Scenario S00
.\Test-ARDTT-Doze.ps1
```

Linux/WSL (сборка):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
export PATH="$HOME/ardtt-testlab/tools/go/bin:$PATH"
bash scripts/windows-usb-testlab/Install-LinuxToolchain.sh
bash scripts/windows-usb-testlab/Doctor-ARDTT.sh --json-out /tmp/ardtt-doctor.json
bash scripts/windows-usb-testlab/Build-ARDTT.sh --abi arm64-v8a --variant debug --force-native
```

Тесты скриптов без телефона:

```bash
bash scripts/windows-usb-testlab/tests/run.sh
```

## Безопасная установка APK

`Install-ARDTT.ps1` делает `adb install -r` только после сравнения. При `INSTALL_FAILED_UPDATE_INCOMPATIBLE` или другой подписи **не** вызывает uninstall/clear. Debug и release в этом проекте подписаны разными ключами — debug рядом с установленным release не ставится.

**Не ставьте GitHub `releases/latest`.** Сейчас это `v0.5.264` / `versionCode` **283** — тот же код, что уже на телефоне, Android не считает это обновлением. Кнопка обновления в приложении тоже смотрит на этот релиз (и на устаревший `https://45.129.2.3/update.json` с 0.5.244). Нужный файл — signed Preview APK **0.5.265 / 284**:

- Run: https://github.com/2kristalls36-hue/ARDTT/actions/runs/34944525811
- Artifact: `ardtt-0.5.265-3f14a8c-arm64-v8a` → `app-arm64-v8a-release.apk`
- SHA-256: `8ba45dd66a13bafeb4faec3ee8a114a0d31c35e0c94a06141637929ca34d0642`
- Подпись release `e07400a728…` (как у v0.5.264)

`Fetch-PreviewApk.ps1` / `Fetch-PreviewApk.sh` сохраняют файл как `ardtt-0.5.265-3f14a8c-arm64-v8a.apk` (не generic `app-arm64-v8a-release.apk`) и отказываются от SHA `c6e9a361…` / артефакта `51cf7ce`. `Install-ARDTT.ps1` падает, если после `adb install -r` на устройстве всё ещё `versionCode` &lt; 284.

Launcher: `com.ardtt.app/.MainActivity`. Системный диалог VPN принимает пользователь.

## Диагностика

- Logcat **не очищается** автоматически. Метка — `Mark-ARDTT-Event.ps1` / `log -t ArdttLab`.
- Ищите в logcat теги `ConnMgr`, `VpnTunnel`: `auto-stage path_decision`, `Connect resolved`, `vpn_stop_cmd`, `service_stopped`, `call_recreate`, `on_revoke`.
- В приложении: **Диагностика → Журнал → Поделиться**; вкладка **Тест** пишет телеметрию (не отправляйте её без отдельной просьбы).
- USB tethering должен быть выключен.
- Интерактивный режим (scrcpy/снимки) ≠ фоновый/Doze. Принудительный idle — не доказательство естественного засыпания.

## Сценарии S00–S12

Каталог: `scenarios.json`. Автоматически прогоняется только smoke **S00** при живом USB. Остальные помечаются `PENDING`/`BLOCKED`, пока нет SIM/БС/профиля. Надпись «МТС» и один недоступный сайт не являются ground truth для белых списков.

## Типовые проблемы

| Симптом | Что сделать |
|---|---|
| `unauthorized` | Разблокировать телефон, подтвердить RSA именно этого ПК |
| Нет device | Кабель с data, не хаб; OEM USB driver, не «универсальный ADB installer» |
| WSL нужен reboot | `setup-state.json` + `RESUME.txt`; `Setup-ARDTT-TestLab.ps1 -Resume` |
| Сборка ищет NDK windows | Сборка только в WSL Linux SDK; USB остаётся на Windows adb |
| Нет `libclient.so` в APK | `--force-native`: preBuild пропускает сборку, если .so уже лежит |
| Preview APK из CI | `Fetch-PreviewApk.ps1` — только 0.5.265/284. `pull_request` собирает **merge commit**, не чистый head. `releases/latest` не использовать |

## Что лаборатория не делает

Не fetch-ит release-keystore с VPS, не публикует релиз, не шлёт телеметрию, не сбрасывает телефон, не меняет Auto/таймауты.
