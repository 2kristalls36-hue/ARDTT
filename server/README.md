# Сервер ARDTT

Продуктовое имя — **ARDTT** (Amnezia & Raw Dial over TURN Tunnel). Стек: **1.0.36** (`DEPLOY_VERSION`).  
Исходники — `server/` **этого** репозитория (в APK копии нет). Механика установки (из приложения по SSH или `docker compose`): [DEPLOY.md](../docs/DEPLOY.md).  
Лендинг: [../README.md](../README.md). Текущий релиз: [../CHANGELOG.md](../CHANGELOG.md).

Боевой контур — **один контейнер** `ardtt` (см. [архитектуру](../docs/ARCHITECTURE.md) и [легенду](../docs/LEGEND.md)). Процессы внутри те же:

| Процесс | Статус сейчас | Назначение |
|--------|---------------|------------|
| **provision** | рабочий | `/health`, пользователи, `host_id`, AWG-ключи, JSON-профиль |
| **direct** | **AmneziaWG 2.0** | `amneziawg-go` + `awg`, conf из `users.json` |
| **bypass** | **RAW `-listen-raw`** | `wdtt-server` из **qWDTT / SpaceNeuroX** (не classic WDTT), пароли из `users.json`, подсеть `10.9.0.0/24`, DNS клиентам `10.9.0.1` |
| **dns** | **dnsmasq** | шлюзы `10.8.0.1` / `10.9.0.1`; на выходе каскада — `cascade0` |
| **warp** | **WARP egress** | hideIp `/32` → table `51820`. На каскаде правила ставит **выход**; вход — passthrough |
| **cascade** | hop AWG | вход ↔ выход (`10.10.0.0/30`); тот же образ, что `direct` |
| **telemetry** | рабочий | приём debug-логов с Android (`POST /api/upload-log`, порт 9200) |

## Быстрый старт (без Docker)

Нужен Go 1.22+.

```bash
cd server
export ARDTT_PUBLIC_HOST=1.2.3.4
./scripts/create-user.sh alice
# → JSON профиля (с ключами) в stdout

cd provision && go run . -cmd serve -data ../data -public-host "$ARDTT_PUBLIC_HOST"
curl -s http://127.0.0.1:9100/health
```

Call hash звонка на сервер **не** кладётся — только на телефон.

## Docker Compose

Нужны Docker, `NET_ADMIN`, `/dev/net/tun`. Сборка тянет
`amneziawg-go` / `amneziawg-tools` и RAW-сервер Path B (GPL-3, `bypass/wdtt-server/` —
линия [SpaceNeuroX/qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android)).

На VPS клонируйте **тег релиза**, не скользящий `main` (сейчас `v0.5.238` = стек **1.0.36**).
Публичный репозиторий клонируется без PAT; приватный — SSH-ключ или token. Каноническая
раскладка `/opt/ardtt` через `install.sh`: [Путь 2 в DEPLOY.md](../docs/DEPLOY.md#путь-2--git--compose).

```bash
git clone --depth 1 --branch v0.5.238 \
  https://github.com/2kristalls36-hue/ARDTT.git
cd ARDTT/server
cp .env.example .env   # пропишите ARDTT_PUBLIC_HOST; COMPOSE_PROFILES=isolated
docker compose --profile isolated up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh bob
# или: docker exec ardtt provision -cmd create-user -name bob -data /data
```

`direct` слушает UDP `ARDTT_DIRECT_PORT` (default 51820).  
`bypass` слушает UDP `ARDTT_BYPASS_PORT` (default 56003) в режиме RAW/WRAP без DTLS для клиентов Path B.

## Механика работы серверной части (деплой)

### 1) Что разворачивается

При `docker compose --profile isolated up -d --build` поднимается **один** контейнер `ardtt`. Внутри — те же процессы (общая netns контейнера, не хоста):

1. `provision` — источник истины по пользователям, ключам и `host_id`.
2. `direct` — прямой AmneziaWG-контур (`10.8.0.0/24`).
3. `bypass` — обходной RAW-контур (`10.9.0.0/24`).
4. `dns` — DNS-шлюз для клиентов туннелей.
5. `warp` — опциональный egress через Cloudflare WARP.
6. `cascade` — AmneziaWG-hop между входным и выходным VPS (`10.10.0.0/30`).
7. `telemetry` — приём клиентских debug-логов.

Все процессы читают общие данные из `server/data/` (`/data` в контейнере). На хост уходят только опубликованные порты, без `network_mode: host`. Если UDP через Docker DNAT не работает: `ARDTT_NETWORK_MODE=hostnet`.

### 2) Как проходит деплой

Стек ставится из Android (SSH, архив `server/` с GitHub) или клоном тега релиза — [DEPLOY.md](../docs/DEPLOY.md). Дальше одинаково:

1. В `.env` задаётся внешний адрес VPS (`ARDTT_PUBLIC_HOST`) и порты.
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
docker compose --profile isolated ps
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh smoke-test
```

Проверка считается успешной, если:

- контейнер `ardtt` в состоянии `running`;
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
