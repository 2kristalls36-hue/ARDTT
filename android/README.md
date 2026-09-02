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

Release (подписанный постоянным keystore):

```bash
# один раз: скачать ключ с VPS дистрибуции
./scripts/fetch-release-keystore.sh

./scripts/build-release-apk.sh
# или: cd android && ./gradlew :app:assembleRelease :app:bundleRelease
```

- APK: `android/app/build/outputs/apk/release/app-release.apk`
- AAB: `android/app/build/outputs/bundle/release/app-release.aab`
- Секреты (не в git): `android/keystore.properties` + `android/keystore/ardtt-release.keystore`
- Каноническая копия ключа: `root@45.129.2.3:/opt/ardtt-distribution/secrets/`
- Пример: `android/keystore.properties.example`

Важно: debug и release подписаны разными ключами — для перехода с debug-сборки нужна переустановка приложения.

## Модули

- `app` — UI, ConnectionManager, VpnTunnelService, bypass session
- `tunnel` — AmneziaWG userspace (`libwg-go`)
- `go_client` — Path B RAW (qWDTT / SpaceNeuroX) → `libclient.so`
- **Режим тестирования:** полная телеметрия, JSONL, upload на VPS — [../docs/TELEMETRY.md](../docs/TELEMETRY.md)
- **Обновления:** Настройки → «Обновления» читает `https://45.129.2.3/update.json`,
  скачивает APK внутри приложения, проверяет SHA-256 и запускает системный установщик.

## Донаты (Спасибо Мир)

В **Настройки** появляется блок «Поддержать проект», если задана ссылка одним из способов:

1. **Через `update.json` на VPS** (удобно менять без пересборки APK):

```json
{
  "versionCode": 182,
  "versionName": "0.5.164-ui",
  "apkUrl": "https://45.129.2.3/ardtt-latest.apk",
  "donateUrl": "https://spasibomir.ru/pay/34807"
}
```

2. **В `app/src/main/res/values/strings.xml`** — поле `donate_url` (если в манифесте поля нет).

Ссылку берите в личном кабинете Спасибо Мир: **Профиль → QR-код / Ссылка** (это URL, который зашит в QR).

Перед оплатой приложение напоминает отключить VPN — иначе СБП/банк может отклонить платёж.

После деплоя сервера на VPS обычно остаётся `/opt/nonamevpn/stack/` (исторический путь каталога) и рабочие образы.

Сборка APK перед `preBuild` упаковывает `server/` в `assets/deploy/stack.tar.gz.bin` (`packDeployAssets`). Вручную: `./scripts/pack-deploy-assets.sh`. Механика: [docs/DEPLOY.md](../docs/DEPLOY.md).
