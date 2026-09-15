# Передача локальному агенту (Windows 11 + USB)

Скопируйте блок ниже в локальный Cursor Agent Mode на целевом ПК.

---

Ты локальный агент на Windows 11 с доступом к терминалу и USB. Облачный агент Cursor (Linux VM, 15.09.2026) **уже** выполнил всё, что можно без этого ПК. Не начинай лабораторию с нуля и не переписывай скрипты, если они есть в checkout.

## Задача, которую тебе передают

Промпт: подготовка среды тестирования ARDTT на физическом Android по USB (Auto/БС, обрывы, фон/Doze). Исходный длинный промпт — `ARDTT_Grok_4_6_Windows11_USB_Test_Environment.md`. Продуктовые алгоритмы Auto/БС/RAW **не менять**. Keystore с VPS **не** скачивать. Uninstall / clear data / сброс телефона **не** делать.

Проблемы, для которых нужна среда (гипотезы, не факты):

1. Auto на мобильной сети с БС при включённом Wi-Fi radio без AP; UI мог показать «через Wi-Fi» при операторе МТС.
2. Самопроизвольные обрывы (фон, экран выкл, смена сетей).
3. Отличить ошибку надписи, неверный physical network, мёртвый туннель при живом VPN-сервисе и смерть процесса.

Надпись «МТС» и один недоступный сайт ≠ ground truth для БС. Старую запись Wi-Fi не объявлять установленным фактом.

## Checkout

- Репозиторий: https://github.com/2kristalls36-hue/ARDTT
- Код приложения (PR 217, draft): ветка `auto/auto-whitelist-evidence-f6dc`, SHA `a325b83e11ddf8c7bd42490a88db465b34075538`, пакет `com.ardtt.app`. Для установки поверх GitHub `0.5.264` (283) нужен **0.5.265 / versionCode 284** из этой лаборатории, иначе установщик не видит обновление.
- Лаборатория (уже в git): ветка `auto/windows-usb-testlab-85a8`, PR https://github.com/2kristalls36-hue/ARDTT/pull/221 (base = ветка PR 217). Для телефона берите **Preview APK 0.5.265 / versionCode 284** этой ветки, не GitHub `releases/latest`.
- Каталог: `scripts/windows-usb-testlab/`. Отчёт облака: `setup-report.md`. Доказательства: `evidence/`.

Работай из `auto/windows-usb-testlab-85a8` (она содержит SHA приложения + скрипты). Не переключайся на `main`.

## Что облако УЖЕ сделало (не повторять без причины)

Скрипты лаборатории написаны и лежат в git (не TODO):

| Файл | Назначение |
|---|---|
| `Setup-ARDTT-TestLab.ps1` | Windows + Resume; на Linux вызывает toolchain |
| `Install-LinuxToolchain.sh` | JDK/Go 1.25.14/SDK 35/NDK 27.0.12077973/CMake 3.22.1, хеши официальные |
| `Doctor-ARDTT.ps1` / `Doctor-ARDTT.sh` | Windows USB и Linux toolchain |
| `Build-ARDTT.ps1` / `Build-ARDTT.sh` | WSL/Linux сборка + `build-manifest.json` |
| `Fetch-PreviewApk.sh` / `Fetch-PreviewApk.ps1` | CI Preview **0.5.265/284**; отказ от GitHub `v0.5.264` |
| `Install-ARDTT.ps1` | только `adb install -r` после сравнения подписи; **нет** uninstall |
| `Start/Mark/Capture/Stop-ARDTT-Diagnostics.ps1` | сессия, logcat без `-c`, только свои PID |
| `Test-ARDTT-Scenario.ps1` | S00 авто; S01–S12 PENDING без живых условий |
| `Test-ARDTT-Doze.ps1` + `Restore-ARDTT-TestState.ps1` | force-idle с restore в `finally` |
| `lib/redact.py`, `pack-session.sh` | маскирование секретов перед share-архивом |
| `scenarios.json`, `lab-config.example.json`, `versions.env` | каталог и пины |
| `diagnostic-fields-plan.md` | план доп. полей лога, **не внедрять** без отдельного решения |

Юнит-тесты без телефона: `bash scripts/windows-usb-testlab/tests/run.sh` — все зелёные на облаке и в GitHub job «Lab script tests».

Linux toolchain на облачной VM (не на вашем ПК):

- Ubuntu 24.04 x86_64, OpenJDK 21.0.10, Go 1.25.14
- NDK 27.0.12077973 `linux-x86_64`, clang `aarch64-linux-android28-clang`
- Хеш Go 1.27.0 из Makefile libwg-go сверен с go.dev: `675c26c449cbb18fc24b74650de1eabbae6e16f64326fd85a283fb3b58280685`

Сборка debug на SHA `a325b83e` (UTC 2026-09-15T06:56:49Z):

- `BUILD SUCCESSFUL`, ABI `arm64-v8a`
- в APK: `lib/arm64-v8a/libclient.so`, `lib/arm64-v8a/libwg-go.so`
- файл `app-arm64-v8a-debug.apk`, SHA-256 `7a5447f34f62c8dd3ec48f0fa645ba837fca48f97ada8059e4fb3089e8c9916b`
- подпись Android Debug `fb59b8ea4a4edf1c3d32b19d612d0823e88ba4ca52a4a68b22fc5f1f0ee378e2`
- APK **не в git** (80 МБ). На облачной VM он был в `~/ardtt-testlab/apk/` — на Windows его нет, соберите заново в WSL или возьмите CI.

Preview CI APK для телефона (release, не latest GitHub Release):

- run https://github.com/2kristalls36-hue/ARDTT/actions/runs/34944525811
- artifact `ardtt-0.5.265-3f14a8c-arm64-v8a` — **merge commit** `3f14a8c`, не чистый head
- `app-arm64-v8a-release.apk` SHA-256 `8ba45dd66a13bafeb4faec3ee8a114a0d31c35e0c94a06141637929ca34d0642`
- `versionName=0.5.265` **`versionCode=284`** — это и есть обновление поверх GitHub `v0.5.264` / 283
- release cert `e07400a728fd0dc1c6c84874a4bea5b157b8f3e7015ecef3d21ed0bc0fa245fa` (CN=ARDTT) — **несовместима** с debug
- native libs в CI APK есть
- кнопка «Обновить» в приложении **не** поставит этот файл: GitHub `releases/latest` всё ещё 0.5.264/283, VPS `update.json` — 0.5.244/262

Старый Preview PR 217 (run 34856621087, `0.5.264` / 283) **не ставить** — Android оставит уже установленный пакет.

```powershell
.\scripts\windows-usb-testlab\Fetch-PreviewApk.ps1
.\scripts\windows-usb-testlab\Install-ARDTT.ps1 -ApkPath "$env:LOCALAPPDATA\ARDTT-TestLab\apk\app-arm64-v8a-release.apk"
```

## Что облако НЕ сделало (это твоя работа)

- winget / Git / gh / scrcpy / Windows platform-tools / OEM USB driver
- WSL2 Ubuntu на этом ПК, отдельный checkout `~/ardtt-testlab/src/ARDTT`, Linux SDK там
- `adb devices` → состояние `device`, serial в `lab-config.json`
- скриншот/scrcpy на живом телефоне
- установка APK на устройство (сначала снимок установленного `com.ardtt.app`: version/подпись/debuggable/VPN)
- S00 на железе; S01–S12 при реальной сети/профиле
- отправка телеметрии, push релиза, смена Auto

## Как работать

1. Проверь, что это Windows (`$env:OS -eq 'Windows_NT'`), иначе остановись.
2. Кабель data, не хаб; USB tethering выключен.
3. Не удаляй приложение при `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
4. Для телефона с release используй Preview APK **0.5.265 (284)** run https://github.com/2kristalls36-hue/ARDTT/actions/runs/34944525811 (артефакт `ardtt-0.5.265-3f14a8c-arm64-v8a`). Debug и GitHub `v0.5.264` (283) не заменяют друг друга как «обновление».
5. Logcat не чистить (`-c`). Метка — `Mark-ARDTT-Event.ps1`. В logcat теги `ConnMgr` / `VpnTunnel`.
6. Интерактив (scrcpy) ≠ фон/Doze. Forced idle ≠ естественный сон.
7. Секреты в share-архив только через `pack-session.sh`.
8. Обновляй `setup-report.md` фактами **этого** ПК.

Команды:

```powershell
cd scripts\windows-usb-testlab
Set-ExecutionPolicy -Scope Process Bypass
.\Setup-ARDTT-TestLab.ps1
.\Doctor-ARDTT.ps1
.\Test-ARDTT-Scenario.ps1 -Scenario S00
```

При необходимости reboot WSL: `.\Setup-ARDTT-TestLab.ps1 -Resume`.

Итог пользователю: модель телефона, установленная версия и provenance APK, какие сценарии реально прогнаны, что ещё PENDING/BLOCKED, где sessions/архив.
