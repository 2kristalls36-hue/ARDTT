# Сервер ARDTT

Продуктовое имя — **ARDTT** (Amnezia & Raw Dial over TURN Tunnel). Стек: **1.0.51** (`DEPLOY_VERSION`).  
Продуктовая установка — готовый архив `ardtt-server-<версия>-linux-<amd64|arm64>.tar.gz` из GitHub Releases: [DEPLOY.md](../docs/DEPLOY.md).  
Лендинг: [../README.md](../README.md). Текущий релиз: [../CHANGELOG.md](../CHANGELOG.md).

На чистом VPS пакет ставит Docker Engine из `vendor/docker.tgz`. Пакет не вызывает сетевой установщик Engine, не делает `docker pull` и не собирает образ. Рабочий Docker не переустанавливается.

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

Нужны Linux amd64/arm64, `/dev/net/tun`, python3, iptables. Docker Engine — из архива, если его ещё нет.

```bash
# Актив с https://github.com/2kristalls36-hue/ARDTT/releases
# Сверьте внешнюю SHA-256 с digest / SHA256SUMS релиза.
export ARDTT_PUBLIC_HOST=IP_VPS
export ARDTT_PACKAGE=/opt/ardtt/incoming/ardtt-server-1.0.46-linux-amd64.tar.gz
export ARDTT_PACKAGE_SHA256='…'
# после safe-extract:
bash /opt/ardtt/staging/install.sh
curl -s http://127.0.0.1:9100/health
curl -s http://127.0.0.1:9100/ready
```

Обновление — тот же путь. Откат: `ARDTT_ACTION=rollback`.  
Удаление только этого экземпляра: `ARDTT_ACTION=uninstall` (`ARDTT_PURGE_DATA=1` стирает data/).

Host published порты (Direct, Bypass, provision, telemetry) задаются в `.env`; listen внутри контейнера: 51820 / 56003 / 9100 / 9200.

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
/opt/ardtt/data/          # после install.sh
  users.json
  config.json
  warp/
server/data/              # только локальная разработка
```

Не коммитьте `data/` с боевыми секретами.
