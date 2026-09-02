# Android-клиент

Jetpack Compose · minSdk 28 · targetSdk 35 · имя на экране **ARDTT**.

## Требования

- Android SDK, JDK 17, NDK (см. `android/app/build.gradle.kts`, `ndkVersion`)
- Go 1.25+ — для сборки `libclient.so` (Path B)
- Linux/macOS/WSL для нативной сборки

## Сборка debug

```bash
cd android
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Debug и release подписаны **разными** ключами — переход между ними требует переустановки.

## Сборка release

```bash
# keystore (не в git): android/keystore.properties + keystore/ardtt-release.keystore
cp android/keystore.properties.example android/keystore.properties

./scripts/build-release-apk.sh
```

- APK: `android/app/build/outputs/apk/release/app-release.apk`
- AAB: `android/app/build/outputs/bundle/release/app-release.aab`

## Модули

| Путь | Назначение |
|------|------------|
| `app/` | UI, VPN, deploy, profiles, settings, telemetry |
| `tunnel/` | AmneziaWG userspace, JNI → `libwg-go` |
| `go_client/` | Path B RAW client → `libclient.so` |

Сборка bypass-клиента:

```bash
./scripts/build-bypass-client.sh
```

## Deploy-бандл в APK

Перед `preBuild` каталог `server/` упаковывается в assets:

```bash
./scripts/pack-deploy-assets.sh
# → android/app/src/main/assets/deploy/stack.tar.gz.bin
# → install.sh, DEPLOY_VERSION
```

## Вкладки приложения

| Вкладка | Доступ | Функции |
|---------|--------|---------|
| Туннель | все | Подключение, маршрут, hide IP |
| Серверы | admin | SSH, деплoy VPS |
| Профили | все | Import/export |
| Исключения | все | Apps / hosts split tunnel |
| Журналы | admin + testing | Телеметрия |
| Настройки | все | Тема, уведомления, донат, admin unlock |

## OTA-обновления

`BuildConfig.UPDATE_MANIFEST_URL` — URL `update.json` на вашем VPS:

```json
{
  "versionCode": 182,
  "versionName": "0.5.164-ui",
  "apkUrl": "https://your-host/ardtt-latest.apk",
  "sha256": "...",
  "donateUrl": "https://spasibomir.ru/pay/34807"
}
```

Настройки → блок «Обновление» → скачивание и установка APK.

## Донаты

Ссылка: `strings.xml` → `donate_url` или `donateUrl` в `update.json`.  
Настройки → **Поддержать проект** (напоминание отключить VPN перед оплатой).

## applicationId

Сейчас `com.nonamevpn.app` — совместимость с ранними сборками и установка рядом с AmneziaWG.  
Отображаемое имя: **ARDTT**.

## Иконка

Монограмма **AR** на красном `#FF1800`. Исходники: `docs/assets/brand/`.

## Тестирование

Режим тестирования (admin): JSONL-логи, upload на telemetry-сервис VPS.  
Сборка smoke: см. `docs/PHONE_INTEGRATE.md` в полном дереве исходников.
