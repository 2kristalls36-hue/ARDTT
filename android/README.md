# Android-клиент nonameVPN

Jetpack Compose (`applicationId`: `com.nonamevpn.app`), minSdk 28.

## Уже есть

- Parallel **NetworkProbe** + **ConnectionManager**
- **VpnTunnelService** — один VpnService, бэкенды Direct / Bypass
- Импорт профиля JSON (provision)
- **Bypass scaffold:** `CallHashStore` (encrypted), `AutoVkDialer` (vkcalls→legacy), `WrapCrypto` (HKDF+RTP AEAD), `BypassSession`
- Настройки: тихий recreate, экономика workers

## Ещё нет (следующий слой)

- Native AmneziaWG GoBackend в DirectBackend
- Реальный HTTP/TLS для vkcalls + TURN Allocate TCP + packet pump RAW
- WebView для создания звонка / legacy captcha

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
