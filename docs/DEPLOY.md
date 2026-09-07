# Деплой сервера ARDTT

> [!CAUTION]
> **Ранняя бета.** Деплой-часть (установка и обновление стека на VPS из приложения или с Git) не обещана рабочей. Автор **не несёт ответственности** за результат. Ставите **на свой страх и риск**.

Как ставится и как живёт self-hosted стек **ARDTT** на VPS.  
Клиентская сторона деплоя — вкладка **Серверы** в режиме администратора Android.  
Состав сервисов и смысл Path A/B: [ARCHITECTURE.md](ARCHITECTURE.md), [LEGEND.md](LEGEND.md).

Каталог на диске по умолчанию — `/opt/ardtt` (`ARDTT_INSTALL_DIR`). При обновлении старый `/opt/nonamevpn` переносится сюда.

Версия **стека** (`DEPLOY_VERSION`, сейчас **1.0.41**) независима от `versionName` приложения. Её бампят только когда меняется то, что уезжает на VPS (Compose, `install.sh`, образы сервисов).

---

## Два способа поставить сервер

| Способ | Когда | Что происходит |
|--------|--------|----------------|
| **Из приложения** | Админ с телефоном | Телефон скачивает `server/` с GitHub (релизный `ardtt-stack-*.tar.gz` или архив тега) и заливает по SSH → Compose на хосте |
| **Git + Compose** | На VPS есть shell | `install.sh` с `ARDTT_GIT_REF=<тег>` сам клонирует репозиторий, либо `docker compose` в `server/` |

Оба способа поднимают **один и тот же** стек: единый контейнер `ardtt` (provision + direct + bypass + dns + warp + cascade + telemetry).  
Пользователи и ключи живут в `users.json` и **переживают** повторный деплой.

```
Телефон (админ)
  HTTPS  GitHub Releases / archive (server/)
      │
  SSH (пароль или PEM)
      │  upload  /opt/ardtt/stack.tar.gz
      │  upload  /opt/ardtt/install.sh
      │  env ARDTT_PUBLIC_HOST=… ARDTT_GIT_REF=v0.5.247 bash install.sh
      ▼
    VPS  /opt/ardtt/stack/     ← compose + исходники + Dockerfile
         /opt/ardtt/stack/data ← users.json, ключи, warp state
         контейнер ardtt (своя netns, не host)
      │
      ├─ :51820/udp  AmneziaWG (Path A)
      ├─ :56003/udp  RAW/WRAP  (Path B, после TURN на клиенте)
      ├─ :9100/tcp   provision (/health, пользователи, профили)
      └─ :9200/tcp   telemetry upload
```

---

## Что поднимается

По умолчанию — **один контейнер** `ardtt` в своей netns (`COMPOSE_PROFILES=isolated`).  
TUN, iptables и `ip rule` живут **внутри** контейнера (`NET_ADMIN` + `/dev/net/tun`), а на хост публикуются только порты ARDTT. Так деплой не перехватывает FORWARD/sysctl/ip-rule чужих сервисов на уже работающем VPS.

Запасной режим `ARDTT_NETWORK_MODE=hostnet` — прежний host network, если UDP через Docker DNAT на этой машине не проходит.

| Процесс в `ardtt` | Образ | Роль |
|-----------|--------|------|
| `provision` | `server/Dockerfile` (stage) | Источник истины: `host_id`, AWG-ключи, пароли bypass, JSON-профиль, `GET /health` с `deployVersion` |
| `direct` | то же | AmneziaWG 2.0 (`amneziawg-go` + `awg`) на `awg0`, подсеть `10.8.0.0/24` |
| `bypass` | то же | `wdtt-server -listen-raw` на `wdttraw0`, подсеть `10.9.0.0/24` |
| `dns` | то же | dnsmasq на шлюзах `10.8.0.1` / `10.9.0.1`, upstream `1.1.1.1`/`1.0.0.1` **через main**, не через WARP |
| `warp` | то же | wgcf → wireproxy → tun2socks `warp0`; hideIp → table `51820`. DNS (:53) остаётся на main |
| `telemetry` | то же | `POST /api/upload-log` с телефона |

Один `host_id` (начиная с **2**, `.1` — шлюз) даёт клиенту оба адреса: `10.8.0.{id}` и `10.9.0.{id}`.  
WARP — не третий путь подключения, а **egress** выбранных пользователей.

---

## Путь 1 — деплой из Android

### UI

1. Настройки → разблокировать режим администратора (долгий тап / PIN).
2. Вкладка **Серверы** → «Добавить сервер» (первый раз) или карточка VPS → **Обновить деплой** / **Установить деплой**, если стека ещё нет.
3. SSH host, порт, user (`root` по умолчанию), пароль **или** PEM. Каскад: те же поля для второго VPS.
4. Публичный host (`ARDTT_PUBLIC_HOST`) — то, что попадёт в endpoint профиля; обычно = IP VPS.
5. Порты: по умолчанию включён **автовыбор** свободных UDP Direct / Bypass на VPS. Выключите переключатель «Автовыбор портов», чтобы задать 51820 / 56003 (или свои) вручную.
6. «Сохранить сервер» только пишет цель в encrypted prefs. Установка — **«Установить на VPS»**.
7. **«Удалить»** на карточке не снимает её сразу. Плашка предупреждает, что стек будет стёрт на VPS (контейнеры, `/opt/ardtt`, профили клиентов). После подтверждения `DeployEngine` по SSH делает uninstall (каскад: сначала выход, потом вход). Карточка во вкладке **Сервера** пропадает только если удалённый wipe завершился успешно. Если SSH не проходит, карточка остаётся.

Креды лежат в `EncryptedSharedPreferences` (`ardtt_servers`), не в профиле VPN.

Список серверов зондрует `http://{publicHost}:9100/health`. Если `/health` молчит, карточка пробует SSH с сохранёнными кредами. Сравнение `deployVersion` с версией стека, которую знает это приложение (`assets/deploy/DEPLOY_VERSION`):

- зелёный — установленный стек = стек в этом приложении;
- оранжевый — VPS онлайн, но версия старая → «Обновить деплой»;
- нет связи — «Нет связи»;
- SSH проходит, а `/health` нет — «Не установлено» и кнопка «Установить деплой».
- каскад — на строке IP `вход → выход`, обычный сервер — один адрес.

### Что делает `DeployEngine`

Класс: `android/.../deploy/DeployEngine.kt`. Таймаут установщика — 45 минут.

1. HTTPS: `DeployStackFetcher` скачивает стек с GitHub (см. [Откуда телефон берёт стек](#откуда-телефон-берёт-стек)).
2. SSH (JSch). Не-root: команда через `sudo -S` с паролем.
3. `mkdir -p /opt/ardtt`
4. Загрузка архива стека и `install.sh` (оба из GitHub, не из APK).
5. Запуск:

```bash
ARDTT_PUBLIC_HOST='…' ARDTT_DIRECT_PORT=51820 ARDTT_BYPASS_PORT=56003 ARDTT_AUTO_PORTS=1 \
ARDTT_DEPLOY_VERSION='1.0.39' ARDTT_GIT_REF='v0.5.247' \
ARDTT_GIT_REPO='https://github.com/2kristalls36-hue/ARDTT.git' \
bash /opt/ardtt/install.sh
```

При `ARDTT_AUTO_PORTS=1` установщик оставляет предпочтительные порты, если они свободны; иначе подбирает ближайшие свободные UDP и пишет фактические значения в `ARDTT_DONE|…|direct_port=…|bypass_port=…`. Приложение сохраняет их в карточке сервера. `ARDTT_AUTO_PORTS=0` — прежнее поведение: занятый порт = ошибка установки.

Каскад (два VPS): телефон **отдельно** SSH на выход, потом на вход. Пароль второго сервера в `.env` входа не пишется.

```bash
# 1) выход (DNS + WARP)
ARDTT_ROLE=exit ARDTT_PUBLIC_HOST='2.26.125.160' ARDTT_DEPLOY_VERSION='1.0.39' \
  ARDTT_GIT_REF='v0.5.247' ARDTT_AUTO_PORTS=1 bash /opt/ardtt/install.sh
# stdout: ARDTT_CASCADE_PUBLIC_KEY|<base64>

# 2) вход (клиенты), пир = ключ выхода
ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=1 \
  ARDTT_CASCADE_PEER_ENDPOINT='2.26.125.160:51820' \
  ARDTT_CASCADE_PEER_PUBLIC_KEY='…' \
  ARDTT_PUBLIC_HOST='45.129.2.3' ARDTT_DEPLOY_VERSION='1.0.39' \
  ARDTT_GIT_REF='v0.5.247' ARDTT_AUTO_PORTS=1 bash /opt/ardtt/install.sh

# 3) ключ входа → /opt/ardtt/stack/data/cascade.peer.pub на выходе
```

Повторный «Обновить деплой» **входа** без `ARDTT_CASCADE_ENABLED=1` больше не сбрасывает живой каскад: `install.sh` копирует флаги из `stack/.env` и `data/cascade.peer.endpoint`. Снять каскад явно: `ARDTT_CASCADE_FORCE_DISABLE=1`.

На standalone-входе `ARDTT_CASCADE_DNS` в `.env` должен быть пустым. Иначе provision отдаёт профилям `10.10.0.2`, хотя `ardtt-cascade` не запущен и DNS на `10.8.0.1` / `10.9.0.1`.

Порядок обновления каскада: сначала **выход** до этого стека, потом вход. Если обновить только вход и включить hop, Hide-IP-выкл уедет в старый blanket WARP на выходе — 2ip.ru снова покажет Cloudflare. Не поднимайте hop, пока `/health` выхода не покажет тот же `deployVersion`, что и вход.

6. Разбор stdout построчно (UTF-8, без ANSI):
   - `ARDTT_PROGRESS|<0..1>|<шаг>` — полоса, процент и подпись в UI (каскад: два ряда VPS 1 / VPS 2 с IP);
   - `ARDTT_ERROR|<текст>` — ошибка даже при exit 0;
   - `ARDTT_DONE|…` — успех (`direct_port` / `bypass_port` / `cascade_listen_port` — фактические UDP);
   - `ARDTT_WARN|…` — в лог, деплой продолжается.
7. После успеха: prune образов на хосте, `lastDeployedAtMs` в prefs.
8. После ошибки: `tail` удалённого `/opt/ardtt/install.log` в телеметрию (если включена запись).

Отмена = `session.disconnect()`.

### Удаление сервера из приложения

Класс: `android/.../deploy/ServerUninstall.kt` (команда в APK, **без** бампа `DEPLOY_VERSION`).

1. Плашка «Удалить сервер?» — предупреждение, что стек снимется с VPS.
2. `docker compose down -v` в `/opt/ardtt/stack` (и legacy `/opt/nonamevpn`), `docker rm -f` контейнеров `ardtt` / `ardtt-*` / `nvpn-*`, снятие leftover TUN/`ip rule`/iptables с комментариями ARDTT, правила ufw/firewalld на порты стека, `rm -rf /opt/ardtt /opt/nonamevpn`. Образы `stack-ardtt`. Если на хосте **нет чужих контейнеров** — `apt-get purge` Docker, `docker0`, `/var/lib/docker`, `/swapfile` установщика и docker.list. Чужой стек на shared VPS не трогается.
3. Каскад: сначала выходной VPS, затем вход. Ошибка на любом хосте оставляет карточку, чтобы можно было повторить (команда идемпотентна).
4. Маркер stdout `ARDTT_UNINSTALLED` и exit 0. Только после этого `ServersRepository.delete`.
5. Отмена = обрыв SSH; карточка не удаляется.

### Откуда телефон берёт стек

Исходники стека живут в `server/` этого репозитория и **не** пакуются в APK. На телефоне в assets остаётся только метка `deploy/DEPLOY_VERSION` (чтобы карточка сервера сравнивала `/health`).

Порядок загрузки (`DeployStackFetcher`):

1. GitHub Release тега `v<versionName>` — актив `ardtt-stack-<DEPLOY_VERSION>.tar.gz` (кладёт [android-build.yml](../.github/workflows/android-build.yml) через `scripts/pack-stack.sh` на стабильной версии без `test` в `versionName`).
2. Тот же актив в списке релизов, если имя совпадает с ожидаемой версией стека.
3. Source-tarball GitHub (`/repos/…/tarball/<ref>` или `archive/refs/tags/…`) — `install.sh` сам находит `server/` внутри префикса `ARDTT-<tag>/`.
4. Ветка `main`, если тега ещё нет (debug-сборка).
5. Если в APK всё же лежит локальный `stack.tar.gz` (ручная упаковка) — запасной офлайн-путь.

`install.sh` сначала вынимается из того же gzip-tar (корень релизного актива или `…/server/install.sh` в архиве тега). Запас — `raw.githubusercontent.com/…/<ref>/server/install.sh`. APK ≤0.5.245 узнаёт скрипт по первым 400 символам: маркеры `ARDTT_PROGRESS|` / `ARDTT_DONE|` должны быть в этой шапке.

В архиве стека (корень tar = содержимое `server/`, без `data/`):

```
docker-compose.yml  Dockerfile  entrypoint.sh  .env.example  DEPLOY_VERSION  README.md  install.sh  scripts/
provision/  direct/  bypass/  dns/  warp/  telemetry-upload/
```

Проверка согласованности (без Docker):

```bash
./scripts/check-deploy-bundle.sh
./scripts/test-install-unpack.sh   # распаковка, GitHub-layout, сохранение data/, повтор без tar
./scripts/pack-stack.sh            # dist/ardtt-stack-1.0.39.tar.gz
```

### Обновление vs первая установка

`install.sh` перед распаковкой копирует `stack/data` в `/tmp/ardtt-data-bak` и возвращает после `tar`. Сохраняются:

- `users.json` / `config.json` / ключи;
- состояние WARP (`data/warp/`, учётка wgcf);
- счётчики трафика bypass.

Пересобираются образы и контейнеры. Неуправляемые контейнеры с именами `ardtt` / `ardtt-*` (без compose-label) снимаются, чтобы не конфликтовать с `container_name`. Имена `nvpn-*` с прошлых установок тоже удаляются. После остановки старого host-network стека установщик снимает leftover `awg0` / `wdttraw0` / `warp0` / `cascade0` и **свои** `ip rule` lookup 51820 (подсети 10.8/10.9/10.10/10.99) **с хоста**, иначе они продолжают ломать nginx и чужой Docker. Чужой WireGuard с таблицей 51820 не трогается.

Если предыдущий запуск **стёр** `stack.tar.gz` (скрипт удаляет архив и при ошибке), повторный запуск **без** новой заливки идёт по уже распакованному `stack/docker-compose.yml` — так можно добить упавшую сборку Docker. Если нет ни tar, ни `stack/`, но задан `ARDTT_GIT_REF`, установщик сам клонирует репозиторий.

Место: нужно ≥ **1800 МБ** свободно на `/`, иначе установщик выходит с понятной ошибкой.

### Повторный деплой

Один и тот же стек на уже стоящем VPS обновляется так, чтобы `data/` (пользователи, ключи, WARP) пережили замену образов.

**С телефона (предпочтительный путь после публикации репозитория)**

1. Обновите клиент с [Releases](https://github.com/2kristalls36-hue/ARDTT/releases), если карточка показывает «нужно обновить».
2. Режим администратора → вкладка **Серверы** → карточка VPS → **Обновить деплой** (или **Переустановить деплой** в параметрах сервера).
3. Телефон заново скачивает стек **этой** версии с GitHub и заливает по сохранённым SSH-данным. Пароль/PEM вводить повторно не нужно.
4. Каскад: сначала обновите **выход**, дождитесь `/health` с новым `deployVersion`, затем вход.
5. Пользователей заново создавать не нужно. Профили клиентов остаются, пока не сменились UDP-порты (при автовыборе установщик старается оставить прежние).

**С shell на VPS**

```bash
TAG=v0.5.247
install -d -m 755 /opt/ardtt
curl -fsSL "https://raw.githubusercontent.com/2kristalls36-hue/ARDTT/${TAG}/server/install.sh" \
  -o /opt/ardtt/install.sh
chmod +x /opt/ardtt/install.sh
export ARDTT_PUBLIC_HOST=IP_ЭТОГО_VPS
export ARDTT_DEPLOY_VERSION=1.0.39
export ARDTT_GIT_REF="$TAG"
export ARDTT_GIT_REPO=https://github.com/2kristalls36-hue/ARDTT.git
# каскад: на выходе ARDTT_ROLE=exit; на входе не опускайте ARDTT_CASCADE_ENABLED=1
bash /opt/ardtt/install.sh
curl -s http://127.0.0.1:9100/health
```

Не делайте `rm -rf /opt/ardtt/stack/data` «для чистоты»: это снимет всех клиентов. Полный снос — только кнопка **Удалить** в приложении или вручную `rm -rf /opt/ardtt`.

---

## Путь 2 — git + Compose

Тот же стек, что ставит приложение. Нужны Docker, `NET_ADMIN`, `/dev/net/tun`. Сборка тянет `amneziawg-go` / `amneziawg-tools` и RAW-сервер Path B.

Клонируйте **тег релиза** (`v0.5.247` = клиент 0.5.247 и стек 1.0.39), не скользящий `main`. Репозиторий публичный: HTTPS clone, `raw.githubusercontent.com` и GitHub Releases читаются без PAT.

```bash
TAG=v0.5.247

# Вариант A — тот же install.sh, что из приложения (/opt/ardtt, data/ сохраняется)
install -d -m 755 /opt/ardtt
curl -fsSL "https://raw.githubusercontent.com/2kristalls36-hue/ARDTT/${TAG}/server/install.sh" \
  -o /opt/ardtt/install.sh
export ARDTT_PUBLIC_HOST=IP_ЭТОГО_VPS ARDTT_DEPLOY_VERSION=1.0.39 ARDTT_GIT_REF="$TAG"
bash /opt/ardtt/install.sh

# Вариант B — compose прямо в клоне (без /opt/ardtt)
git clone --depth 1 --branch "$TAG" \
  https://github.com/2kristalls36-hue/ARDTT.git /tmp/ardtt
cd /tmp/ardtt/server
cp .env.example .env          # ARDTT_PUBLIC_HOST=IP_VPS; COMPOSE_PROFILES=isolated
docker compose --profile isolated up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice
```

Host network (если UDP через Docker DNAT не работает): `ARDTT_NETWORK_MODE=hostnet` в `.env` и `docker compose --profile hostnet up -d --build`.

`ARDTT_DEPLOY_VERSION` подхватывается из `.env` / `DEPLOY_VERSION` и отдаётся в `GET /health`.

Телефон больше не несёт копию стека в APK: и админ-деплой, и git-путь читают один репозиторий. Клиент всё равно нужен для туннеля и админки.

---

## Что делает `install.sh` на VPS

Канонический файл: [`server/install.sh`](../server/install.sh). Каталог установки: `/opt/ardtt` (`ARDTT_INSTALL_DIR`).

| Progress | Шаг |
|----------|-----|
| 0.05 | root |
| 0.10 | каталог |
| 0.15 | распаковка tar / GitHub-архива, уже лежащий `stack/`, либо `ARDTT_GIT_REF` |
| 0.22 | в архиве есть все build-контексты compose |
| 0.25 | Docker + compose plugin, если их не было (`get.docker.com`). Чужие контейнеры: dockerd не перезапускаем, кэш BuildKit всё равно чистим |
| 0.28 | orphan snapshots/leases BuildKit + `builder prune -af` + split-образы + json-логи; swap если RAM < 1.8 ГБ (на shared VPS swapfile не сжимаем) |
| 0.40 | `.env`, `DEPLOY_VERSION` на хосте и в `stack/data/` (`COMPOSE_PROFILES=isolated`) |
| 0.45 | проверка места |
| 0.48 | на VPS < 1.8 ГБ RAM: `compose down` нашего стека; сброс BuildKit (stop docker, umount executor rootfs, `rm` без abort при busy) **только если нет чужих контейнеров** |
| 0.50 | `compose build ardtt` (один образ; повтор `--no-cache` после сброса BuildKit) → `compose up -d` |
| 0.74 | leftover TUN/iptables/ip-rule с хоста |
| 0.80 | снос build-cache и apt-архивов |
| 0.85 | `curl` provision `:9100` и telemetry на `ARDTT_TELEMETRY_PORT` (по умолчанию 9200) |
| 0.96 | ufw / firewalld: Direct UDP, Bypass UDP, 9100/tcp, telemetry TCP. `iptables -I INPUT` — только в `hostnet` |
| 1.00 | `ARDTT_DONE` |

Лог при ошибке: `/opt/ardtt/install.log`. При успехе временный лог и tar удаляются.

---

## Раскладка на диске

```
/opt/ardtt/
  install.sh              # последняя заливка с GitHub (через телефон или curl)
  DEPLOY_VERSION          # то же число, что в /health
  install.log             # только если последний прогон упал
  stack/
    docker-compose.yml
    .env
    DEPLOY_VERSION
    provision/ direct/ bypass/ dns/ warp/ telemetry-upload/
    data/                 # volume; не из tar
      users.json          # config + users (ключи, hideIp, presence)
      config.json
      server_public.key
      DEPLOY_VERSION
      bypass-traffic.json # снимок счётчиков RAW
      warp/               # wgcf-account.toml, профиль warp0
```

После успешного деплоя не должно оставаться `stack.staging`, `stack.old`, `stack.tar.gz`, `install-live.log`, `install-run.log`. На 8–10 ГБ VPS установщик держит `/swapfile` ≈ 1 ГБ (не 2 ГБ) и не делает `docker image prune -af` — иначе пропадают неиспользуемые `stack-*` образы. `/opt/ardtt-distribution` — раздача APK, не мусор стека.

Не коммитить `server/data/` с боевыми секретами (уже в `.gitignore`).

---

## Как стек работает после деплоя

Источник истины — **provision** (`users.json`). Остальные сервисы **ждут** появления файла (до ~2 мин) и дальше следят за mtime.

```
provision ──writes──► /data/users.json
    │
    ├─ direct-sync  → awg0.conf  → amneziawg-go   (каждые 5 с при изменении)
    ├─ bypass-sync  → passwords.json + raw_ip     (SIGHUP wdtt-server)
    └─ warp         → ip rule from 10.8/10.9.{id} → table 51820, если hideIp
                      (на каскаде правило ставит выход, не вход)
```

### Direct (Path A)

UDP `ARDTT_DIRECT_PORT`. Клиенты — peers AmneziaWG с `AllowedIPs=10.8.0.{hostId}/32`.  
FORWARD + MASQUERADE в WAN. Смена пользователей → `awg syncconf` без рестарта контейнера.

### Bypass (Path B)

Клиент доходит до VPS **уже после TURN** (звонок на телефоне). На сервере слушается RAW UDP `ARDTT_BYPASS_PORT`, без DTLS. Пароль и `10.9.0.{hostId}` берутся из того же `users.json`. Трафик в админке — из `bypass-traffic.json`.

Hash звонка на сервер **не** кладётся.

### DNS

Клиентский DNS в профиле — шлюз туннеля (`10.8.0.1` / `10.9.0.1`). dnsmasq резолвит через Cloudflare по **main**.  
Если Hide IP включён, `ip rule` prio 100 (`iif awg0|wdttraw0 dport 53 → main`) не пускает :53 в `warp0`.

### Hide IP / WARP

`POST /v1/hide-ip` → `users.json.hideIp`. На standalone вход warp-entrypoint дебаунсит ~1 с и вешает `from 10.8.0.{id}/32`. На каскаде вход в `passthrough`, а выход опрашивает `GET /v1/hide-ip-prefixes` у `10.10.0.1` и вешает те же `/32` на своём `warp0`. DNS и подсети туннелей остаются на main. Клиент **не** рестартует TUN.

### Provision API (телефон бьёт в `:9100`)

| Метод | Путь | Зачем |
|-------|------|--------|
| GET | `/health` | `{ ok, service, deployVersion }` — статус карточки сервера |
| GET/POST | `/v1/users` | список / создать (тело создания = JSON профиля) |
| POST | `/v1/users/update` | срок, устройства, деактивация, лимит трафика |
| POST | `/v1/users/unbind-device` | снять deviceId |
| POST | `/v1/users/delete` | удалить пользователя |
| GET | `/v1/profile/{name}` | тот же JSON, что импортирует клиент |
| POST | `/v1/presence` | online в админке |
| POST | `/v1/hide-ip` | WARP egress |
| GET/POST | `/v1/egress-ip` | публичный IP (через `warp0`, если hideIp) |

Авторизации на provision нет: это ваш VPS. Порт 9100 должен быть доступен с телефона админа (и с клиента для presence/hide-ip).

Профиль, который выдаёт provision:

```json
{
  "name": "alice",
  "hostId": 2,
  "direct": { "endpoint": "VPS:51820", "address": "10.8.0.2/32", "…": "…" },
  "bypass": { "peer": "VPS:56003", "address": "10.9.0.2/32", "password": "…", "mode": "raw" }
}
```

Клиенты создаются из приложения (Сервер → Клиенты) или `./scripts/create-user.sh`.

---

## Порты

| Порт | Протокол | Кто | Обязательно снаружи |
|------|----------|-----|---------------------|
| SSH (22) | TCP | деплой из приложения | да, для админ-деплоя; git-путь можно с локальной машины |
| 51820 | UDP | direct (предпочтительно) | да, Path A; при автовыборе может стать другим свободным UDP |
| 56003 | UDP | bypass RAW (предпочтительно) | да, Path B; то же для автовыбора |
| 9100 | TCP | provision | да, health / профили / hide-ip |
| 9200 | TCP | telemetry | если нужен приём логов с телефонов |

`ARDTT_AUTO_PORTS=1` (кнопка в форме деплоя по умолчанию): если 51820/56003 заняты другим сервисом, установщик берёт следующие свободные UDP и синхронизирует их в `.env`, `users.json` (через provision из env) и `ARDTT_DONE`. При обновлении с автовыбором сначала пробуются порты прошлого деплоя. `ARDTT_AUTO_PORTS=0` + занятый порт → ошибка, как раньше.

TURN VK — на стороне **клиента**, на VPS отдельного TURN нет.

---

## Когда бампать `DEPLOY_VERSION`

Файл: `server/DEPLOY_VERSION`. Синхронно обновить:

1. `android/app/src/main/assets/deploy/DEPLOY_VERSION`
2. `DeployBundle.FALLBACK_VERSION`
3. При релизе CI сам соберёт `ardtt-stack-<версия>.tar.gz` (`scripts/pack-stack.sh`)

Бамп нужен, если изменились `docker-compose.yml`, Dockerfiles, entrypoint'ы, `install.sh` или vendored bypass. Правка только UI телефона — нет.

Формат — semver стека (`1.0.x`), не версия APK.

---

## Снятие и данные

```bash
cd /opt/ardtt/stack
docker compose down          # контейнеры; data/ остаётся
# полный снос (ключи тоже):
# rm -rf /opt/ardtt
```

Кнопка **Удалить** в приложении снимает стек с VPS по SSH (см. [Удаление сервера](#удаление-сервера-из-приложения)). Повторный деплой без удаления карточки `data/` не трогает.

---

## Чек-лист после установки

```bash
cd /opt/ardtt/stack
COMPOSE_PROFILES=isolated docker compose ps
curl -s http://127.0.0.1:9100/health
# ожидается: "ok": true, "deployVersion": "1.0.39"

ss -ulnp | grep -E '51820|56003'
ss -tlnp | grep -E '9100|9200'
docker exec ardtt provision -cmd create-user -name smoke -data /data
```

С телефона: карточка VPS — ОС справа, «Онлайн» под ней, деплой 1.0.39 слева (или «Требуется обновление · …»), создание клиента, импорт профиля, Connect.

---

## Типичные отказы

| Симптом | Что проверить |
|---------|----------------|
| «Не удалось скачать стек … из GitHub» | Сеть на телефоне до github.com. Репозиторий публичный, PAT не нужен. Запас: [Путь 2](#путь-2--git--compose) |
| `git clone`: Authentication failed | Проверьте URL `https://github.com/2kristalls36-hue/ARDTT.git` и тег релиза. PAT не требуется |
| Диск растёт от деплоя к деплою | Docker 29 кладёт слои сборки в containerd snapshots. Живой образ ~300 МБ, а кэш BuildKit + Active-снимки сорванной сборки — гигабайты, и `builder prune` их не берёт (Reclaimable:false), пока жив чужой контейнер и dockerd нельзя рестартнуть. Стек ≥**1.0.41** снимает orphan snapshots/leases и будит GC. Не копите именованные APK в `/opt/ardtt/apk`. |
| `install.sh` + «Мало места» / ENOSPC | На 8–10 ГБ VPS порог обновления ≥900 МБ (после swap оставляем ≥1100 МБ). Установщик режет огромные json-логи Docker, дропает старые split-образы `stack-direct` и т.п., но не трогает чужие контейнеры и не делает `docker image prune -af`. |
| `install.sh exit=1`, `Device or resource busy` в `/var/lib/docker/buildkit/.../rootfs` | Стек ≥**1.0.34**: umount + повтор, установка не падает. На 1 ГБ VPS старый `rm -rf` после `stop docker` обрывал каскад. Обновите APK и снова «Установить». |
| SSH timeout / permission | user/порт/ключ; для не-root нужен sudo-пароль |
| `/health` не отвечает после DONE | `docker compose --profile isolated logs`; `ARDTT_PUBLIC_HOST` и публикация `:9100` |
| telemetry не принимает логи, `:9200` занят | Стек ≥**1.0.39**: `:9200` busy → publish `:9199`; если заняты и `:9200`, и `:9199` (nginx + socat), gunicorn всё равно стартует внутри контейнера, без `ARDTT_SKIP_TELEMETRY=1` |
| Чужие сайты/контейнеры на VPS отвалились после деплоя | Нужен стек ≥1.0.32 (isolated). Обновите деплой из приложения. Запасной `ARDTT_NETWORK_MODE=hostnet` снова шарит host netns |
| UDP Direct не коннектится, TCP :9100 жив | Docker UDP DNAT. Попробуйте `ARDTT_NETWORK_MODE=hostnet` |
| Карточка «нужно обновить» | APK новее стека на VPS — «Обновить деплой» (телефон снова скачает стек с GitHub); или рассинхрон `DEPLOY_VERSION` |
| «Удалить» не снимает карточку | SSH до VPS не прошёл или uninstall оборвался — карточка специально остаётся. Повторите или поправьте креды |
| Hide IP: ping есть, HTTPS нет | MSS clamp на warp0 (уже в entrypoint); DNS не через WARP |
| Повторный деплой «нет tar» | С 1.0.12 установщик продолжает с уже распакованного `stack/`; при `ARDTT_GIT_REF` может скачать заново с GitHub |

Телеметрия категории `deploy` (старт, SSH, прогресс, хвост `install.log`) — [TELEMETRY.md](TELEMETRY.md).
