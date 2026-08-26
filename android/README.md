# Android-клиент nonameVPN

Каркас Jetpack Compose (`applicationId`: `com.nonamevpn.app`).

## Режимы UI

| Режим | Как | Экраны |
|-------|-----|--------|
| **Пользователь** (default) | После установки | Туннель, Настройки |
| **Админ** | Настройки → PIN (≥4 цифр) | + Серверы, Деплой, Логи |

Пользовательский экран туннеля: статус (заглушка), Connect, «Скрыть IP», импорт профиля (заглушка).  
AWG / RAW / probe / VpnService — следующие итерации.

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
  MainActivity.kt
  settings/AppSettingsRepository.kt   # DataStore: admin PIN, hideIp
  ui/
    AppRoot.kt                        # нижняя навигация user/admin
    tunnel/TunnelScreen.kt
    settings/SettingsScreen.kt
    admin/AdminPlaceholders.kt
    theme/Theme.kt
```
