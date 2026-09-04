# Android-клиент ARDTT

Jetpack Compose. Отображаемое имя: **ARDTT** (Amnezia + RAW Dial via TURN).  
`applicationId` пока `com.nonamevpn.app` (совместимость / установка рядом с официальной AmneziaWG), minSdk 28.

Легенда: [../docs/LEGEND.md](../docs/LEGEND.md).

## Иконка

Круглый и квадратный знак: белое **AR** над оранжевым **DTT** на тёмно-угольном поле (`#181E25`) — `mipmap-*/ic_launcher(_round).png`, adaptive foreground `mipmap-*/ic_launcher_foreground.png`, монохром `drawable/ic_launcher_monochrome.png`. Исходник — `docs/assets/brand/ardtt-icon-source.png`. Пересборка плотностей: `python3 scripts/generate-launcher-icons.py`.

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

### GitHub Releases (автоматически из `main`)

Каждый push в `main` (и тег `v*`) запускает workflow
[`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml):

1. Собирает подписанный `ardtt-<versionName>.apk`
2. Публикует GitHub Release с тегом `v<versionName>`
3. Кладёт рядом `ardtt-update.json` и `SHA256SUMS.txt`
4. Если задан секрет `ARDTT_DIST_SSH_PASSWORD` — копирует arm64 APK на VPS (`https://45.129.2.3/update.json`). Без этого шага телефон **не увидит** обновление: репозиторий приватный, а в APK нет GitHub-токена.

**Секреты репозитория** (Settings → Secrets → Actions):

| Secret | Значение |
|--------|----------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 android/keystore/ardtt-release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | из `keystore.properties` |
| `ANDROID_KEY_ALIAS` | `ardtt` |
| `ANDROID_KEY_PASSWORD` | из `keystore.properties` |
| `GITHUB_RELEASE_READ_TOKEN` | read-only PAT с `Contents: Read` для приватного репозитория (вшивается в release APK) |
| `ARDTT_DIST_SSH_PASSWORD` | root-пароль `45.129.2.3` — публикует fallback `update.json` для телефонов без GitHub-токена |

Приложение проверяет обновления через **GitHub Releases API** (как qWDTT),
с fallback на старый `update.json` на VPS. Для **приватного** репозитория без токена GitHub API отвечает 404 — нужен `GITHUB_RELEASE_READ_TOKEN` в CI **или** актуальный `https://45.129.2.3/update.json`. Вручную: `ARDTT_SSH_PASSWORD='…' ./scripts/publish-vps-update.sh dist/ardtt-<ver>-arm64-v8a.apk`.

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
- **Обновления:** сначала GitHub Releases (`2kristalls36-hue/nonameVPN`, нужен вшитый `GITHUB_API_TOKEN`), иначе fallback — `https://45.129.2.3/update.json`.
  Телефон показывает карточку только если `versionCode` в манифесте **больше** установленного. Скачивает APK внутри приложения, проверяет SHA-256 и запускает системный установщик.

После деплоя сервера на VPS обычно остаётся `/opt/nonamevpn/stack/` (исторический путь каталога) и рабочие образы.

Сборка APK перед `preBuild` упаковывает `server/` в `assets/deploy/stack.tar.gz.bin` (`packDeployAssets`). Вручную: `./scripts/pack-deploy-assets.sh`. Механика: [docs/DEPLOY.md](../docs/DEPLOY.md).
