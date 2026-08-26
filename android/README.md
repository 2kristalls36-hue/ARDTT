# Android-клиент AWDTT

Jetpack Compose. Отображаемое имя: **AWDTT** (Amnezia + WDTT).  
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

## Модули

- `app` — UI, ConnectionManager, VpnTunnelService, bypass session
- `tunnel` — AmneziaWG userspace (`libwg-go`)
- `go_client` — WDTT Path B → `libclient.so`

После деплоя сервера на VPS обычно остаётся `/opt/nonamevpn/stack/` (исторический путь каталога) и рабочие образы.
