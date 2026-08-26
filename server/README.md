# Сервер nonameVPN

Compose-стек из четырёх сервисов (см. [архитектуру](../docs/ARCHITECTURE.md)):

| Сервис | Статус сейчас | Назначение |
|--------|---------------|------------|
| **provision** | рабочий каркас | `/health`, пользователи, `host_id`, черновик профиля |
| **direct** | stub | AmneziaWG 2.0 (`awg0`) |
| **bypass** | stub | RAW/WRAP listener |
| **warp** | stub | hide-IP egress (wireproxy→tun2socks) |

## Быстрый старт (provision)

Нужен Go 1.22+.

```bash
cd server
export NVPN_PUBLIC_HOST=1.2.3.4   # ваш VPS
chmod +x scripts/create-user.sh
./scripts/create-user.sh alice
# → JSON профиля в stdout, запись в data/users.json

# HTTP API
cd provision && go run . -cmd serve -data ../data -public-host "$NVPN_PUBLIC_HOST"
curl -s http://127.0.0.1:9100/health
curl -s -X POST http://127.0.0.1:9100/v1/users -d '{"name":"bob"}' -H 'Content-Type: application/json'
```

Поля AWG-ключей в профиле пока пустые — их заполнит следующий этап (упаковка AmneziaWG).  
Call hash звонка на сервер **не** кладётся — только на телефон.

## Docker Compose

```bash
cd server
cp .env.example .env   # пропишите NVPN_PUBLIC_HOST
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
```

`network_mode: host` и `NET_ADMIN` + `/dev/net/tun` — для реальных TUN; stubs пока просто держат контейнеры живыми.

## Данные

```
server/data/
  config.json   # подсети и порты
  users.json    # пользователи и host_id
```

Не коммитьте `data/` с боевыми секретами (уже в `.gitignore`).
