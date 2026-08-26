# Android-клиент nonameVPN

Jetpack Compose (`applicationId`: `com.nonamevpn.app`), minSdk 28.

## Уже есть

- Parallel **NetworkProbe** + **ConnectionManager**
- **VpnTunnelService** — один VpnService, бэкенды Direct / Bypass
- **Path A (Direct):** модуль `:tunnel` с `libwg-go` (AmneziaWG userspace) → `DirectBackend` / `awgTurnOn`
- Импорт профиля JSON (provision)
- **Path B (Bypass):** `libclient.so` (qWDTT go_client) — vkcalls → TURN TCP → WRAP → RAW; TUN после RAWCONF
- **Bypass scaffold:** `CallHashStore`, dial policy, `WrapCrypto` (совместим с сервером)
- **Создать звонок:** WebView вход VK → `calls.start` → hash в `CallHashStore` (Connect остаётся анонимным)
- **Админ-деплой:** SSH (JSch) → upload `stack.tar.gz` + `install.sh` → Docker Compose на VPS
- Настройки: тихий recreate, экономика workers

## Ещё нет (следующий слой)

- Legacy captcha WebView (fallback), если go_client captcha недостаточно
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
app/…/core/       NetworkProbe, ConnectionManager, VpnTunnelService
app/…/tunnel/     TunnelBackend, DirectBackend, BypassBackend, AwgUserspaceConfig
app/…/bypass/     WrapCrypto, CallHashStore, VkDialer, BypassSession
app/…/profile/    VpnProfile JSON
app/…/ui/         Tunnel / Settings / Admin
tunnel/           AmneziaWG libwg-go (JNI) — форк tools из amneziawg-android
```

Сборка `:tunnel` тянет NDK + Go (Makefile `libwg-go` сам скачает toolchain в Gradle cache).
Нужны `ANDROID_HOME` / `local.properties` → `sdk.dir`, NDK 27+.
