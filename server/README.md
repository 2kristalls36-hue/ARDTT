# Сервер nonameVPN

Compose-стек из четырёх сервисов (см. [архитектуру](../docs/ARCHITECTURE.md)):

| Сервис | Статус сейчас | Назначение |
|--------|---------------|------------|
| **provision** | рабочий | `/health`, пользователи, `host_id`, AWG-ключи, JSON-профиль |
| **direct** | **AmneziaWG 2.0** | `amneziawg-go` + `awg`, conf из `users.json` |
| **bypass** | **RAW `-listen-raw`** | `wdtt-server` (qWDTT), пароли из `users.json`, подсеть `10.9.0.0/24` |
| **warp** | stub | hide-IP egress (wireproxy→tun2socks) |

## Быстрый старт (без Docker)

Нужен Go 1.22+.

```bash
cd server
export NVPN_PUBLIC_HOST=1.2.3.4
./scripts/create-user.sh alice
# → JSON профиля (с ключами) в stdout

cd provision && go run . -cmd serve -data ../data -public-host "$NVPN_PUBLIC_HOST"
curl -s http://127.0.0.1:9100/health
```

Call hash звонка на сервер **не** кладётся — только на телефон.

## Docker Compose

Нужны Docker, `NET_ADMIN`, `/dev/net/tun`. Сборка тянет
`amneziawg-go` / `amneziawg-tools` и исходники qWDTT server (GPL-3).

```bash
cd server
cp .env.example .env   # пропишите NVPN_PUBLIC_HOST
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh bob
```

`direct` слушает UDP `NVPN_DIRECT_PORT` (default 51820).  
`bypass` слушает UDP `NVPN_BYPASS_PORT` (default 56003) в режиме RAW/WRAP без DTLS для клиентов Path B.

## Данные

```
server/data/
  config.json         # подсети, порты, server AWG keys
  users.json          # пользователи, host_id, ключи, пароли bypass
  server_public.key   # удобный экспорт публичного ключа
```

Не коммитьте `data/` с боевыми секретами (уже в `.gitignore`).
