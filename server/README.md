# Сервер ARDTT

Продуктовое имя — **ARDTT**. Стек: **1.0.28** (`DEPLOY_VERSION`).  
Механика установки (из приложения по SSH или `docker compose`): [DEPLOY.md](../docs/DEPLOY.md).  
Лендинг: [../README.md](../README.md). Текущий релиз: [../CHANGELOG.md](../CHANGELOG.md).

Compose-стек из шести сервисов (см. [архитектуру](../docs/ARCHITECTURE.md) и [легенду](../docs/LEGEND.md)):

| Сервис | Статус сейчас | Назначение |
|--------|---------------|------------|
| **provision** | рабочий | `/health`, пользователи, `host_id`, AWG-ключи, JSON-профиль |
| **direct** | **AmneziaWG 2.0** | `amneziawg-go` + `awg`, conf из `users.json` |
| **bypass** | **RAW `-listen-raw`** | `wdtt-server` из **qWDTT / SpaceNeuroX** (не classic WDTT), пароли из `users.json`, подсеть `10.9.0.0/24`, DNS клиентам `10.9.0.1` |
| **dns** | **dnsmasq** | шлюзы `10.8.0.1` / `10.9.0.1`; upstream `1.1.1.1`/`1.0.0.1` через main |
| **warp** | **WARP egress** | hideIp `/32` → table `51820`. На каскаде правила ставит **выход**; вход — passthrough |
| **telemetry** | рабочий | приём debug-логов с Android (`POST /api/upload-log`, порт 9200) |

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
`amneziawg-go` / `amneziawg-tools` и RAW-сервер Path B (GPL-3, `bypass/wdtt-server/` —
линия [SpaceNeuroX/qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android)).

```bash
cd server
cp .env.example .env   # пропишите NVPN_PUBLIC_HOST
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh bob
```

`direct` слушает UDP `NVPN_DIRECT_PORT` (default 51820).  
`bypass` слушает UDP `NVPN_BYPASS_PORT` (default 56003) в режиме RAW/WRAP без DTLS для клиентов Path B.

## Механика работы серверной части (деплой)

### 1) Что разворачивается

При `docker compose up -d --build` поднимается единый серверный контур из контейнеров:

1. `provision` — источник истины по пользователям, ключам и `host_id`.
2. `direct` — прямой AmneziaWG-контур (`10.8.0.0/24`).
3. `bypass` — обходной RAW-контур (`10.9.0.0/24`).
4. `dns` — DNS-шлюз для клиентов туннелей.
5. `warp` — опциональный egress через Cloudflare WARP.
6. `telemetry` — приём клиентских debug-логов.

Все сервисы читают общие данные из `server/data/`, чтобы прямой и обходной пути работали согласованно для одного и того же пользователя.

### 2) Как проходит деплой

1. В `.env` задаётся внешний адрес VPS (`NVPN_PUBLIC_HOST`) и порты.
2. Compose собирает и запускает контейнеры.
3. `provision` генерирует/использует серверные ключи и публикует `/health`.
4. Администратор создаёт пользователя (`./scripts/create-user.sh <name>`), и в `users.json` фиксируется `host_id`.
5. Пользователь получает профиль с двумя endpoint'ами: direct и bypass.

Итог: один профиль, два клиентских пути до одного VPS.

### 3) Как трафик обрабатывается на сервере

- **Direct path:** клиент `10.8.0.{id}` -> `direct` -> NAT в интернет.
- **Bypass path:** клиент `10.9.0.{id}` -> `bypass` -> NAT в интернет.
- **Hide IP (если включён):** policy routing уводит пользовательский egress в `warp0` (кроме DNS-потока).
- **DNS:** запросы клиентов идут на локальный `dns`-шлюз (`10.8.0.1`/`10.9.0.1`) и резолвятся через upstream.

### 4) Операционный чек-лист после деплоя

```bash
cd server
docker compose ps
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh smoke-test
```

Проверка считается успешной, если:

- все контейнеры в состоянии `running`;
- `/health` возвращает успешный ответ;
- пользователь создаётся без ошибок и получает валидный JSON-профиль.

## Данные

```
server/data/
  config.json         # подсети, порты, server AWG keys
  users.json          # пользователи, host_id, ключи, пароли bypass
  server_public.key   # удобный экспорт публичного ключа
```

Не коммитьте `data/` с боевыми секретами (уже в `.gitignore`).
