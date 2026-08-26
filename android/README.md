# Android-клиент nonameVPN

Каркас Jetpack Compose (`applicationId`: `com.nonamevpn.app`).

## Режимы UI

| Режим | Как | Экраны |
|-------|-----|--------|
| **Пользователь** (default) | После установки | Туннель, Настройки |
| **Админ** | Настройки → PIN (≥4 цифр) | + Серверы, Деплой, Логи |

## Уже есть

- Parallel **NetworkProbe** + **ConnectionManager** (preselect Direct/Bypass; soft info на OpenNeedBypass)
- **VpnTunnelService** stub (VpnService + notification)
- **Импорт профиля** JSON (формат `provision`) / демо-профиль → endpoints для UDP-lite

## Ещё нет

- Реальные AWG / RAW backends, VK/TURN dial, call hash storage

## Сборка

Нужны JDK 17+ и Android SDK (API 35).

```bash
cd android
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Структура

```
app/src/main/java/com/nonamevpn/app/
  core/          # NetworkProbe, ConnectionManager, VpnTunnelService
  profile/       # VpnProfile JSON + ProfileRepository
  settings/      # DataStore: admin PIN, hideIp
  ui/
    tunnel/TunnelScreen.kt
    settings/SettingsScreen.kt
    admin/…
```
