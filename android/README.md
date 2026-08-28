# Android-клиент ARDTT

Jetpack Compose. Отображаемое имя: **ARDTT** (Amnezia + RAW Dial via TURN).  
`applicationId` пока `com.nonamevpn.app` (совместимость / установка рядом с официальной AmneziaWG), minSdk 28.

Легенда: [../docs/LEGEND.md](../docs/LEGEND.md).

## Иконка

Круглый знак: белая стилизованная **A** (Amnezia) на тёмно-синем (`#0B1F4A`) с тонкой белой обводкой — `mipmap-*/ic_launcher(_round).png`, adaptive `mipmap-anydpi-v26`.

## Сборка

```bash
cd android
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

`assembleRelease` даёт **unsigned** `app-release-unsigned.apk` — Android отклоняет его как повреждённый; для релиза нужен `signingConfig` в `build.gradle` или подпись через `apksigner`.

## Модули

- `app` — UI, ConnectionManager, VpnTunnelService, bypass session
- `tunnel` — AmneziaWG userspace (`libwg-go`)
- `go_client` — Path B RAW (qWDTT / SpaceNeuroX) → `libclient.so`
- **Режим тестирования:** полная телеметрия, JSONL, upload на VPS — [../docs/TELEMETRY.md](../docs/TELEMETRY.md)
- **Обновления:** Настройки → «Обновления» читает `http://159.194.225.162:8088/update.json`,
  скачивает APK внутри приложения, проверяет SHA-256 и запускает системный установщик.

После деплоя сервера на VPS обычно остаётся `/opt/nonamevpn/stack/` (исторический путь каталога) и рабочие образы.
