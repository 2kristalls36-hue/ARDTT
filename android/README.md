# Android-клиент ARDTT

Jetpack Compose. Продуктовое имя: **ARDTT** (Amnezia & Raw Dial over TURN Tunnel).  
Текущая сборка: **0.5.250** (`versionCode` 268).  
`applicationId` / namespace — `com.ardtt.app`, minSdk 28. Смена с `com.nonamevpn.app` требует переустановки APK (это уже другое приложение для Android).

Лендинг репозитория: [../README.md](../README.md). Легенда: [../docs/LEGEND.md](../docs/LEGEND.md). Текущий релиз: [../CHANGELOG.md](../CHANGELOG.md).

## Иконка

Белое **AR** над бирюзовым **DTT** на `#031D3B`. На minSdk 28 лаунчер берёт `mipmap-anydpi-v26/*.xml`, не density-PNG.

- Квадрат: `ic_launcher.xml` + буквы в `ic_launcher_foreground` (форму даёт маска OEM). Пластину со сквирклом в adaptive не кладём — маска обрежет серебряную рамку.
- Круг: `ic_launcher_round.xml` + `ic_launcher_round_foreground` (диск во внутренних 72 dp).
- Виджет: `ic_logo_full` — сквиркл с прозрачными углами.
- QS / уведомление: `ic_tile_custom` и `ic_stat_connected` — только буквы, SystemUI красит по альфе.
- Тема: `ic_launcher_monochrome` — белые буквы в mipmap 108 dp (как foreground), не один PNG 256 px.

Исходники: `docs/assets/brand/ardtt-icon-source.png`, `ardtt-icon-round-source.png`. Пересборка: `python3 scripts/generate-launcher-icons.py`.

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

### Сборка и GitHub Releases

Каждый push в `main` (и тег `v*`) запускает [`.github/workflows/android-build.yml`](../.github/workflows/android-build.yml). Куда попадает APK, решает `versionName`:

| `versionName` | Куда кладётся сборка |
|---|---|
| есть `test` (например `0.5.247-test`) | только **Actions → Artifacts** (30 дней), GitHub Release **не** создаётся |
| без `test` (например `0.5.247`) | GitHub Release `v<versionName>` + копия в Artifacts |

PR всегда собирает Preview APK в артефакты (`.github/workflows/android-branch-apk.yml`), без Releases.

Ручной прогон: Actions → **Android build** → Run workflow. Поле `publish_release`: `auto` (как в таблице), `always` (форс в Releases), `never` (только артефакт).

1. Собирает подписанные `ardtt-<versionName>-*.apk` (R8 + сжатие ресурсов; стек VPS в APK не кладётся)
2. Пакует `ardtt-stack-<DEPLOY_VERSION>.tar.gz` из `server/` (`scripts/pack-stack.sh`)
3. Стабильная версия: публикует GitHub Release с тегом `v<versionName>`
4. Кладёт рядом `ardtt-update.json` и `SHA256SUMS.txt`

**Секреты репозитория** (Settings → Secrets → Actions):

| Secret | Значение |
|--------|----------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 android/keystore/ardtt-release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | из `keystore.properties` |
| `ANDROID_KEY_ALIAS` | `ardtt` |
| `ANDROID_KEY_PASSWORD` | из `keystore.properties` |

Приложение проверяет обновления через **публичный GitHub Releases API** (как qWDTT),
с fallback на старый `update.json` на VPS. PAT не нужен и в APK не вшивается.

Стек на VPS ставится из вкладки **Серверы**: телефон скачивает `server/` с GitHub и заливает по SSH. Тот же стек — клоном тега релиза на машине с Docker — [docs/DEPLOY.md](../docs/DEPLOY.md). Clone с VPS токен не требует.

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
- **Обновления:** GitHub Releases (`2kristalls36-hue/ARDTT`), fallback — `https://45.129.2.3/update.json`. Клиент скачивает APK, проверяет SHA-256 и запускает системный установщик. Публичный репозиторий читается без PAT.

После деплоя сервера на VPS стек лежит в `/opt/ardtt/stack/` и рабочие образы.

Архив `server/` в APK больше не кладётся. Релизный актив `ardtt-stack-<DEPLOY_VERSION>.tar.gz` собирает `scripts/pack-stack.sh` и на стабильной версии публикует GitHub Release. Механика и повторный деплой: [docs/DEPLOY.md](../docs/DEPLOY.md).
