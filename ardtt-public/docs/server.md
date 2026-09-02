# Сервер

Self-hosted стек ARDTT на одном VPS. Шесть сервисов в Docker Compose.

## Требования VPS

- Linux, Docker + Compose v2
- `NET_ADMIN`, доступ к `/dev/net/tun`
- Открытые порты: **51820/udp**, **56003/udp** (минимум для VPN)
- Опционально: **9100/tcp** (health), **9200/tcp** (telemetry)

## Быстрый старт

```bash
cd server
cp .env.example .env
# NVPN_PUBLIC_HOST=ваш.публичный.ip

docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice
```

Скрипт выводит JSON-профиль для импорта в Android.

## Сервисы

| Сервис | Контейнер | Назначение |
|--------|-----------|------------|
| provision | `nvpn-provision` | Users, keys, profiles, `/health` :9100 |
| direct | `nvpn-direct` | AmneziaWG 2.0, `10.8.0.0/24`, UDP 51820 |
| bypass | `nvpn-bypass` | RAW `-listen-raw`, `10.9.0.0/24`, UDP 56003 |
| dns | `nvpn-dns` | dnsmasq на `10.8.0.1` / `10.9.0.1` |
| warp | `nvpn-warp` | Cloudflare WARP egress |
| telemetry | `nvpn-telemetry` | Debug log upload :9200 |

## Переменные окружения (.env)

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `NVPN_PUBLIC_HOST` | — | Публичный IP/hostname VPS |
| `NVPN_DIRECT_PORT` | 51820 | AmneziaWG |
| `NVPN_BYPASS_PORT` | 56003 | RAW bypass |
| `NVPN_DATA` | `./data` | Секреты и users.json |

Полный список: `server/.env.example`.

## Данные

```
server/data/
  config.json       # подсети, порты, server keys
  users.json        # пользователи, host_id, AWG keys, bypass passwords
```

**Не коммитьте** `data/` с боевыми секретами.

## Проверка после деплоя

```bash
docker compose ps
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh smoke-test
```

## Bypass (RAW)

Сервер: vendored `bypass/wdtt-server/` из линии [SpaceNeuroX/qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0).  
Режим `-listen-raw` — сырые IP-пакеты после WRAP/TURN, **не** WireGuard over DTLS.

Пatch `bypass/patches/raw-subnet.patch` — подсеть `10.9.0.0/24`.

## Direct (AmneziaWG)

Сборка из `amneziawg-go` + `amneziawg-tools` в Docker.  
Sync sidecar следит за `users.json`.

## WARP

При «Скрыть IP» на клиенте — policy routing на VPS отправляет egress пользователя через `warp0`.

## Версия стека

`server/DEPLOY_VERSION` — версия deploy-бандла (сейчас отображается в `/health`).  
Независима от versionName Android-приложения.

## Установка без Docker (dev)

```bash
cd server/provision
export NVPN_PUBLIC_HOST=127.0.0.1
go run . -cmd serve -data ../data -public-host "$NVPN_PUBLIC_HOST"
```

Call hash на сервер **не** хранится.
