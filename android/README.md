# Android-клиент nonameVPN

Jetpack Compose (`applicationId`: `com.nonamevpn.app`), minSdk 28.

## Уже есть

- Parallel **NetworkProbe** + **ConnectionManager**
- **VpnTunnelService** — один VpnService, бэкенды Direct / Bypass
- Импорт профиля JSON (provision)
- **Path B (Bypass):** `libclient.so` (qWDTT go_client) — vkcalls → TURN TCP → WRAP → RAW; TUN после RAWCONF
- **Bypass scaffold:** `CallHashStore`, dial policy, `WrapCrypto` (совместим с сервером)
- **Админ-деплой:** SSH (JSch) → upload `stack.tar.gz` + `install.sh` → Docker Compose на VPS
- Настройки: тихий recreate, экономика workers

## Ещё нет (следующий слой)

- Native AmneziaWG GoBackend в DirectBackend (отдельный PR)
- WebView для создания звонка / legacy captcha
- Нативный WARP egress на VPS (сейчас stub)

## Сборка native Path B

```bash
# Нужны ANDROID_HOME / NDK 27+ и Go 1.26+
chmod +x scripts/build-bypass-client.sh
./scripts/build-bypass-client.sh          # arm64-v8a + x86_64 → jniLibs/
cd android && ./gradlew :app:assembleDebug
```

## Деплой с телефона

1. Настройки → PIN админа → вкладка **Деплой** (или **Серверы** → Деплой).
2. Host, SSH user/port, пароль или PEM-ключ, публичный host.
3. «Установить на VPS» загружает актуальный `assets/deploy/stack.tar.gz` и гоняет `install.sh`.

После успеха на VPS остаётся только `/opt/nonamevpn/stack/` (+ data) и рабочие образы:
`stack.tar.gz`, Docker build cache и apt-кэш установщик удаляет сам.

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
