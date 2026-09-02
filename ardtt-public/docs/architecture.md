# Архитектура

## Высокий уровень

```
┌──────────────┐     UDP 51820      ┌─────────────┐
│   Android    │◄──────────────────►│   direct    │  AmneziaWG 2.0
│  ARDTT app   │                    │  10.8.0.0/24│
│              │     TURN + RAW      ├─────────────┤
│ ConnectionMgr│◄──────────────────►│   bypass    │  wdtt-server -listen-raw
│ VpnTunnelSvc │     UDP 56003      │  10.9.0.0/24│
└──────────────┘                    └──────┬──────┘
                                           │
                                    provision :9100
                                    dns / warp / telemetry
```

## Android

| Компонент | Пакет / путь | Роль |
|-----------|--------------|------|
| UI | `com.nonamevpn.app.ui` | Compose, вкладки: Туннель, Серверы, Профили, Исключения, Журналы, Настройки |
| ConnectionManager | `core/ConnectionManager.kt` | Выбор path, connect/disconnect, live switch |
| VpnTunnelService | `core/VpnTunnelService.kt` | Foreground VPN, TUN, уведомления |
| DirectBackend | `tunnel` module | AmneziaWG через `libwg-go` |
| BypassBackend | `go_client` → `libclient.so` | RAW dial, TURN, WRAP |
| DeployEngine | `deploy/` | SSH → VPS, `install.sh`, progress |
| Profiles | `profile/` | Import/export JSON, host_id |
| Telemetry | `telemetry/` | JSONL, upload (testing mode) |

**Модули Gradle:** `:app`, `:tunnel`.  
**Native:** `libwg-go` (Path A), `libclient.so` (Path B, сборка `scripts/build-bypass-client.sh`).

## Сервер (Docker Compose)

Все сервисы используют `network_mode: host`, `NET_ADMIN`, `/dev/net/tun`.

| Сервис | Образ / бинарь | Данные |
|--------|----------------|--------|
| provision | Go API | `users.json`, генерация ключей AWG и bypass-паролей |
| direct | amneziawg-go + awg | sync из `users.json`, iface `awg0` |
| bypass | wdtt-server (GPL, vendored) | `-listen-raw`, iface `wdttraw0` |
| dns | dnsmasq | шаблон `dnsmasq.conf.tmpl` |
| warp | wgcf + iptables | egress table при hideIp |
| telemetry | Python Flask | `POST /api/upload-log` |

Общий каталог данных: `server/data/` (`config.json`, `users.json`).

## Профиль пользователя

Один JSON содержит:

- Direct: публичный ключ сервера, endpoint `{host}:51820`, ключи клиента AWG
- Bypass: endpoint `{host}:56003`, пароль/peer для RAW
- `host_id` → адреса `10.8.0.{id}` и `10.9.0.{id}`

Call hash **не** входит в профиль на сервере — только на устройстве.

## Выбор пути (ConnectionManager)

1. Probe сети (UDP/TCP до VPS, policy из settings).
2. Режим: **auto** | **direct** | **bypass**.
3. Auto: direct если доступен, иначе bypass (нужен call hash).
4. Live switch без disconnect (опция «кнопки во время соединения»).

## WARP (Hide IP)

Не третий path. Policy routing на VPS: пользовательский egress → `warp0` (Cloudflare), DNS остаётся на локальном dnsmasq.

## Деплой из APK

`preBuild` упаковывает `server/` в `assets/deploy/stack.tar.gz.bin`.  
На VPS: `/opt/nonamevpn/` (исторический путь), `install.sh`, `docker compose up`.

Версия стека: `server/DEPLOY_VERSION` (независимо от versionName APK).

## Лицензии компонентов

| Часть | Лицензия |
|-------|----------|
| AmneziaWG / tunnel | Apache-2.0 |
| bypass / go_client | GPL-3.0 (SpaceNeuroX/qWDTT) |
| ARDTT как целое | GPL-3.0 |

См. [NOTICE](../NOTICE).

## Legacy-имена в коде

| В коде / на VPS | Продуктовое имя |
|-----------------|-----------------|
| `com.nonamevpn.app` | ARDTT (applicationId, совместимость) |
| `NVPN_*`, `nvpn-*` | внутренний префикс env/контейнеров |
| `wdtt-server`, `/opt/nonamevpn` | vendored / исторические пути |
| WDTT | upstream WG-over-TURN, не Path B ARDTT |
