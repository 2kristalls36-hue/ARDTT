# Android-клиент ARDTT

Jetpack Compose. Отображаемое имя: **ARDTT** (Amnezia + RAW Dial via TURN).  
`applicationId` пока `com.nonamevpn.app` (совместимость / установка рядом с официальной AmneziaWG), minSdk 28.

Легенда: [../docs/LEGEND.md](../docs/LEGEND.md).

## Иконка

Круглый и квадратный знак: белая монограмма **AR** на красном (`#FF1800`) — `mipmap-*/ic_launcher(_round).png`, adaptive foreground `mipmap-*/ic_launcher_foreground.png`, монохром `drawable/ic_launcher_monochrome.png`. Исходники — `docs/assets/brand/`.

## Сборка

Debug:

```bash
cd android
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Release (постоянный keystore, тот же ключ что у 0.5.143+):

```bash
./scripts/fetch-release-keystore.sh
./scripts/build-release-apk.sh
```

- APK: `android/app/build/outputs/apk/release/app-release.apk`
- Секреты не в git: `android/keystore.properties` + `android/keystore/ardtt-release.keystore`
- Каноническая копия: `root@45.129.2.3:/opt/ardtt-distribution/secrets/`

Debug и release подписаны разными ключами — переход с debug-сборки требует переустановки. Дальше обновления этим же release-ключом ставятся поверх.

## Модули

- `app` — UI, ConnectionManager, VpnTunnelService, bypass session
- `tunnel` — AmneziaWG userspace (`libwg-go`)
- `go_client` — Path B RAW (qWDTT / SpaceNeuroX) → `libclient.so`
- **Режим тестирования:** полная телеметрия, JSONL, upload на VPS — [../docs/TELEMETRY.md](../docs/TELEMETRY.md)
- **Обновления:** Настройки → «Обновления» читает `https://45.129.2.3/update.json`,
  скачивает APK внутри приложения, проверяет SHA-256 и запускает системный установщик.

После деплоя сервера на VPS обычно остаётся `/opt/nonamevpn/stack/` (исторический путь каталога) и рабочие образы.
