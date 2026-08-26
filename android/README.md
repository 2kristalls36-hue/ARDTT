# Android-клиент nonameVPN

Jetpack Compose (`applicationId`: `com.nonamevpn.app`), minSdk 28.

## Уже есть

- Parallel **NetworkProbe** + **ConnectionManager**
- **VpnTunnelService** — один VpnService, бэкенды Direct / Bypass
- Импорт профиля JSON (provision)
- **Bypass scaffold:** `CallHashStore`, `AutoVkDialer`, `WrapCrypto`, `BypassSession`
- **Админ-деплой:** SSH (JSch) → upload `stack.tar.gz` + `install.sh` → Docker Compose на VPS
- Настройки: тихий recreate, экономика workers

## Ещё нет (следующий слой)

- Native AmneziaWG GoBackend в DirectBackend
- Реальный HTTP/TLS для vkcalls + TURN Allocate TCP + packet pump RAW
- WebView для создания звонка / legacy captcha

## Деплой с телефона

1. Настройки → PIN админа → вкладка **Деплой** (или **Серверы** → Деплой).
2. Host, SSH user/port, пароль или PEM-ключ, публичный host.
3. «Установить на VPS» загружает актуальный `assets/deploy/stack.tar.gz` и гоняет `install.sh`.

Обновить архив стека после правок `server/`:

```bash
chmod +x scripts/pack-deploy-assets.sh
./scripts/pack-deploy-assets.sh
```

## Сборка

```bash
cd android
./gradlew :app:assembleDebug
```

## Структура

```
core/       NetworkProbe, ConnectionManager, VpnTunnelService
tunnel/     TunnelBackend, DirectBackend, BypassBackend
bypass/     WrapCrypto, CallHashStore, VkDialer, BypassSession
profile/    VpnProfile JSON
ui/         Tunnel / Settings / Admin
```
