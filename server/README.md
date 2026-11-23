# Сервер ARDTT

Продуктовое имя — **ARDTT** (Amnezia & Raw Dial over TURN Tunnel). Стек: `DEPLOY_VERSION`.  
Установка — деплой v2: `deploy/ardtt-deploy.sh` скачивает с GitHub Releases образ `ardtt-server-<версия>-linux-<arch>.image.tar.gz` и запускает один контейнер ([DEPLOY.md](../docs/DEPLOY.md), [ADR 0006](../docs/adr/0006-deploy-v2.md)).  
Лендинг: [../README.md](../README.md). Текущий релиз: [../CHANGELOG.md](../CHANGELOG.md).

Боевой контур — **один контейнер** (см. [архитектуру](../docs/ARCHITECTURE.md) и [легенду](../docs/LEGEND.md)):

| Процесс | Назначение |
|--------|------------|
| **provision** | `/health`, `/ready`, пользователи, `host_id`, AWG-ключи, JSON-профиль |
| **direct** | AmneziaWG 2.0 (`amneziawg-go` + `awg`), `10.8.0.0/24` |
| **bypass** | `wdtt-server -listen-raw`, `10.9.0.0/24` |
| **dns** | dnsmasq на шлюзах; на выходе каскада — `cascade0` |
| **warp** | hideIp `/32` → table `51820`. На каскаде правила ставит **выход** |
| **cascade** | hop AWG вход ↔ выход (`10.10.0.0/30`) |
| **telemetry** | `POST /api/upload-log`, порт контейнера 9200 |

## Установка (продукт)

Нужны Linux amd64/arm64, `/dev/net/tun`, root. Docker — имеющийся или из репозитория дистрибутива.

```bash
curl -fsSLO https://github.com/2kristalls36-hue/ARDTT/releases/latest/download/ardtt-deploy.sh
sudo ARDTT_PUBLIC_HOST=IP_VPS ARDTT_DEPLOY_VERSION=2.0.0 ARDTT_AUTO_PORTS=1 bash ardtt-deploy.sh
curl -s http://127.0.0.1:9100/health
curl -s -H "Authorization: Bearer $(tr -d '[:space:]' </opt/ardtt/data/admin.token)" http://127.0.0.1:9100/v1/users
```

Обновление — тот же запуск с новой версией; при неудачной проверке готовности возвращается предыдущая.  
Удаление только этого экземпляра: `ARDTT_ACTION=uninstall` (`ARDTT_PURGE_DATA=1` стирает data/).

## Сборка из исходников (разработчик)

Не для VPS. Тянет Debian и pinned GitHub tarball'ы (см. `third-party.lock.json`).

```bash
cd server
cp .env.example .env
docker compose -f docker-compose.dev.yml up -d --build
```

Production `docker-compose.yml` без `build:` и с `pull_policy: never`.

## Быстрый старт (без Docker)

Нужен Go 1.22+.

```bash
cd server
export ARDTT_PUBLIC_HOST=1.2.3.4
./scripts/create-user.sh alice
cd provision && go run . -cmd serve -data ../data -public-host "$ARDTT_PUBLIC_HOST"
curl -s http://127.0.0.1:9100/health
```

## Данные

```
/opt/ardtt/data/          # после установки
  users.json
  config.json
  warp/
server/data/              # только локальная разработка
```

Не коммитьте `data/` с боевыми секретами.
